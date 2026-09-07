#!/usr/bin/env bash
set -Eeuo pipefail

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
PUBLIC_PORT="${PUBLIC_PORT:-8080}"
PUBLIC_URL="${DEV_PILOT_PUBLIC_URL:-}"
SERVER_IMAGE="${DEVPILOT_SERVER_IMAGE:-devpilot/server:1.0.0}"
WEB_IMAGE="${DEVPILOT_WEB_IMAGE:-devpilot/web:1.0.0}"
OFFLINE=false
RESUME=false
OVERRIDES=false

while (($#)); do
  case "$1" in
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --port) PUBLIC_PORT="${2:-}"; OVERRIDES=true; shift 2 ;;
    --public-url) PUBLIC_URL="${2:-}"; OVERRIDES=true; shift 2 ;;
    --server-image) SERVER_IMAGE="${2:-}"; OVERRIDES=true; shift 2 ;;
    --web-image) WEB_IMAGE="${2:-}"; OVERRIDES=true; shift 2 ;;
    --offline) OFFLINE=true; shift ;;
    --resume) RESUME=true; shift ;;
    -h|--help) printf '%s\n' "Usage: install.sh [--install-dir PATH] [--port 8080] [--public-url URL] [--server-image IMAGE] [--web-image IMAGE] [--offline] | --resume --install-dir PATH [--offline]"; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

compose_saved() (
  grep -qx 'name: devpilot' "$INSTALL_DIR/docker-compose.yml" || {
    printf '%s\n' 'Expected installer-managed Compose project name: devpilot. Refusing a renamed or custom project.' >&2; exit 1;
  }
  # Installer-managed services always belong to devpilot. A parent shell must
  # not redirect maintenance to another project, file, profile or env file.
  unset COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_DISABLE_ENV_FILE
  # Compose gives exported shell values precedence over --env-file. Clear only
  # keys present in the installer-generated file, retaining Docker connection settings.
  while IFS='=' read -r config_key _; do
    [[ "$config_key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    unset "$config_key"
  done < .env
  docker compose --project-name devpilot --env-file .env -f docker-compose.yml "$@"
)

start_saved_installation() {
  cd "$INSTALL_DIR"
  compose_saved config -q
  local pull_policy=()
  if [[ "$OFFLINE" != true ]]; then compose_saved pull; else pull_policy=(--pull never); fi
  compose_saved up -d --wait --wait-timeout 240 "${pull_policy[@]}"
  printf '%s\n' 'Waiting for DevPilot health checks...'
  for attempt in {1..60}; do
    if curl -fsS "http://127.0.0.1:${PUBLIC_PORT}/healthz" >/dev/null 2>&1; then
      touch .installation-complete
      printf '\nDevPilot installed successfully!\nURL: %s\nCreate the first administrator in the browser.\n' "$PUBLIC_URL"
      return 0
    fi
    sleep 2
  done
  compose_saved ps >&2
  printf '%s\n' 'DevPilot did not become healthy. Inspect Compose logs before retrying.' >&2
  return 1
}

installation_exit() {
  local result=$?
  if ((result != 0)); then
    printf 'Installation did not complete; existing files and volumes were preserved at %s.\n' "$INSTALL_DIR" >&2
    if [[ -f "$INSTALL_DIR/.install-resume.sha256" ]]; then
      printf 'After fixing the reported problem, retry: bash install.sh --resume --install-dir %s\nDo not regenerate encryption keys or remove database volumes.\n' "$INSTALL_DIR" >&2
    else
      printf '%s\n' 'Configuration generation was incomplete. Automatic resume is unavailable; preserve the directory and inspect it before recovery.' >&2
    fi
  fi
}

[[ "$(uname -s)" == "Linux" ]] || { printf '%s\n' "DevPilot Server supports Linux only." >&2; exit 1; }
[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this installer as root." >&2; exit 1; }
case "$(uname -m)" in
  x86_64|aarch64|arm64) ;;
  *) printf '%s\n' "Supported CPU architectures: amd64 (x86_64) and arm64 (aarch64)." >&2; exit 1 ;;
esac
# Advisory only: containers and non-systemd hosts may use another time service.
# Never change the clock or claim that NTP status proves UTC/RTC correctness.
clock_status=""
if command -v timedatectl >/dev/null && command -v timeout >/dev/null; then
  clock_status="$(timeout 5 timedatectl show --property=NTPSynchronized --property=LocalRTC 2>/dev/null)" || clock_status=""
fi
if ! grep -qx 'NTPSynchronized=yes' <<<"$clock_status" || ! grep -qx 'LocalRTC=no' <<<"$clock_status"; then
  printf '%s\n' 'WARNING: Host clock synchronization/UTC RTC could not be confirmed (时间同步或 UTC 硬件时钟未确认).' \
    'Before starting DevPilot, verify date -u, timedatectl (or your NTP service), and the host/VM RTC. Clock rollback can break login and database writes. This installer does not adjust the clock.' >&2
fi
[[ "$PUBLIC_PORT" =~ ^[0-9]{1,5}$ ]] && ((10#$PUBLIC_PORT >= 1 && 10#$PUBLIC_PORT <= 65535)) || { printf '%s\n' "Invalid public port." >&2; exit 2; }
# Host-wide lock: different install directories can target the same Compose
# project. FD 7 is inherited by upgrade -> backup; never unlink this inode.
command -v flock >/dev/null || { printf '%s\n' 'flock is required for safe maintenance.' >&2; exit 1; }
host_lock=/run/devpilot-maintenance.lock
[[ ! -L "$host_lock" && ( ! -e "$host_lock" || -f "$host_lock" ) ]] || { printf '%s\n' 'Unsafe host maintenance lock.' >&2; exit 1; }
umask 077
if [[ ! /proc/$$/fd/7 -ef "$host_lock" ]]; then
  exec 7>>"$host_lock"
fi
[[ "$(stat -c '%u:%a:%h' "$host_lock")" == '0:600:1' ]] || { printf '%s\n' 'Host maintenance lock must be root-owned, mode 0600, with one link.' >&2; exit 1; }
flock -n 7 || { printf '%s\n' 'Another DevPilot installation or maintenance operation is running on this host. No service changes made; retry after it finishes.' >&2; exit 1; }

PUBLIC_PORT="$((10#$PUBLIC_PORT))"
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "--install-dir must be a non-root absolute path without whitespace." >&2; exit 2; }
if [[ "$RESUME" == true ]]; then
  [[ "$OVERRIDES" == false ]] || { printf '%s\n' '--resume uses saved configuration; port, URL and image overrides are not allowed.' >&2; exit 2; }
  [[ -d "$INSTALL_DIR" && ! -L "$INSTALL_DIR" && -f "$INSTALL_DIR/.install-resume.sha256" && ! -e "$INSTALL_DIR/.installation-complete" ]] || {
    printf '%s\n' 'This is not a resumable incomplete installation. Use upgrade/restore for existing installations.' >&2; exit 1;
  }
  command -v flock >/dev/null && command -v curl >/dev/null || { printf '%s\n' 'flock and curl are required.' >&2; exit 1; }
  [[ ! -L "$INSTALL_DIR/.devpilot-maintenance.lock" ]] || exit 1
  exec 9>>"$INSTALL_DIR/.devpilot-maintenance.lock"
  flock -n 9 || { printf '%s\n' 'Another installation or maintenance operation is running.' >&2; exit 1; }
  # Recheck under lock; another process may have finished before we acquired it.
  [[ ! -e "$INSTALL_DIR/.installation-complete" ]] || exit 1
  (cd "$INSTALL_DIR" && sha256sum -c .install-resume.sha256 >/dev/null) || {
    printf '%s\n' 'Saved configuration changed or is incomplete; refusing automatic resume.' >&2; exit 1;
  }
  PUBLIC_PORT="$(sed -n 's/^PUBLIC_PORT=//p' "$INSTALL_DIR/.env")"
  PUBLIC_URL="$(sed -n 's/^DEV_PILOT_PUBLIC_URL=//p' "$INSTALL_DIR/.env")"
  [[ "$PUBLIC_PORT" =~ ^[0-9]{1,5}$ ]] || exit 1
  docker compose up --help | grep -q -- '--wait-timeout' || exit 1
  trap installation_exit EXIT
  start_saved_installation
  exit 0
fi
[[ ! -e "$INSTALL_DIR" && ! -L "$INSTALL_DIR" ]] || {
  printf '%s\n' "Install directory already exists. Refusing to overwrite configuration or encryption keys. Use the upgrade/restore procedure, or choose a new empty installation path." >&2; exit 1;
}
for image in "$SERVER_IMAGE" "$WEB_IMAGE"; do
  [[ "$image" =~ ^[a-zA-Z0-9][a-zA-Z0-9._/@:-]+$ ]] || { printf '%s\n' "Invalid image reference." >&2; exit 2; }
done
disk_parent="$(dirname "$INSTALL_DIR")"
while [[ ! -d "$disk_parent" ]]; do disk_parent="$(dirname "$disk_parent")"; done
[[ -w "$disk_parent" ]] || { printf '%s\n' "Installation parent directory is not writable." >&2; exit 1; }
available_kb="$(df -Pk "$disk_parent" | awk 'END {print $4}')"
[[ "$available_kb" =~ ^[0-9]+$ ]] && ((available_kb >= 5242880)) || {
  printf '%s\n' "At least 5 GiB free space is required on the installation filesystem." >&2; exit 1;
}

install_docker() {
  printf '%s\n' "Docker was not found; installing distribution packages..."
  if command -v apt-get >/dev/null; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 curl openssl ca-certificates
  elif command -v dnf >/dev/null; then
    dnf install -y docker docker-compose-plugin curl openssl ca-certificates
  elif command -v yum >/dev/null; then
    yum install -y docker docker-compose-plugin curl openssl ca-certificates
  elif command -v apk >/dev/null; then
    apk add --no-cache docker docker-cli-compose curl openssl ca-certificates
  else
    printf '%s\n' "Install Docker Engine and the Compose plugin, then rerun this script." >&2
    exit 1
  fi
  systemctl enable --now docker 2>/dev/null || service docker start
}

command -v docker >/dev/null || install_docker
docker compose version >/dev/null 2>&1 || { printf '%s\n' "Docker Compose v2 is required." >&2; exit 1; }
docker compose up --help | grep -q -- '--wait-timeout' || { printf '%s\n' "Upgrade Compose: up --wait and --wait-timeout are required." >&2; exit 1; }
docker info >/dev/null 2>&1 || { printf '%s\n' "Docker daemon is unavailable or access is denied." >&2; exit 1; }
existing_project="$(docker ps -aq --filter label=com.docker.compose.project=devpilot)"
[[ -z "$existing_project" ]] || { printf '%s\n' "A DevPilot Compose project already exists. Refusing a second install against its data." >&2; exit 1; }
existing_volumes="$(docker volume ls -q --filter label=com.docker.compose.project=devpilot)"
[[ -z "$existing_volumes" ]] || { printf '%s\n' "DevPilot data volumes already exist. Use the documented recovery procedure; new keys must not be generated for old data." >&2; exit 1; }
command -v ss >/dev/null || { printf '%s\n' "ss (iproute2) is required to check listening TCP ports." >&2; exit 1; }
listeners="$(ss -H -ltn)"
if awk -v port="$PUBLIC_PORT" '$4 ~ (":" port "$") {found=1} END {exit !found}' <<<"$listeners"; then
  printf 'TCP port %s is already listening. Choose another --port.\n' "$PUBLIC_PORT" >&2; exit 1
fi
published_ports="$(docker ps --format '{{.Ports}}')"
if grep -Eq ":${PUBLIC_PORT}->" <<<"$published_ports"; then
  printf 'Docker already publishes port %s. Choose another --port.\n' "$PUBLIC_PORT" >&2; exit 1
fi
command -v openssl >/dev/null || { printf '%s\n' "openssl is required." >&2; exit 1; }
command -v curl >/dev/null || { printf '%s\n' "curl is required." >&2; exit 1; }
command -v flock >/dev/null || { printf '%s\n' "flock (util-linux) is required for safe backup, upgrade and restore." >&2; exit 1; }
if [[ "$OFFLINE" == true ]]; then
  for image in "$SERVER_IMAGE" "$WEB_IMAGE" mysql:8.4 redis:7.4-alpine nginx:1.29-alpine; do
    docker image inspect "$image" >/dev/null 2>&1 || { printf 'Offline image is missing: %s\n' "$image" >&2; exit 1; }
  done
fi

if [[ -z "$PUBLIC_URL" ]]; then
  HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  HOST_IP="${HOST_IP:-127.0.0.1}"
  PUBLIC_URL="http://${HOST_IP}:${PUBLIC_PORT}"
fi
PUBLIC_URL_PATTERN='^https?://[^/$?#[:space:]]+/?$'
[[ "$PUBLIC_URL" =~ $PUBLIC_URL_PATTERN ]] || { printf '%s\n' "Public URL must be an HTTP(S) origin without a path, query, or fragment." >&2; exit 2; }
PUBLIC_URL="${PUBLIC_URL%/}"
COOKIE_SECURE="false"
[[ "$PUBLIC_URL" == https://* ]] && COOKIE_SECURE="true"

umask 077
mkdir -p "$(dirname "$INSTALL_DIR")"
# Atomic claim: a racing installer must not overwrite files after preflight.
mkdir -m 0750 "$INSTALL_DIR" || { printf '%s\n' 'Installation path was created concurrently; refusing to overwrite it.' >&2; exit 1; }
exec 9>>"$INSTALL_DIR/.devpilot-maintenance.lock"
flock -n 9 || exit 1
trap installation_exit EXIT
install -d -m 0750 "$INSTALL_DIR/nginx"
MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
MYSQL_PASSWORD="$(openssl rand -hex 24)"
REDIS_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 48)"
MASTER_KEY="$(openssl rand -base64 32 | tr -d '\n')"
MAINTENANCE_REPORT_SECRET="$(openssl rand -hex 48)"
PROMETHEUS_SCRAPE_TOKEN="$(openssl rand -hex 48)"

cat >"$INSTALL_DIR/.env" <<EOF
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_DATABASE=devpilot
MYSQL_USER=devpilot
MYSQL_PASSWORD=${MYSQL_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
JWT_SECRET=${JWT_SECRET}
DEV_PILOT_MASTER_KEY=${MASTER_KEY}
MAINTENANCE_REPORT_SECRET=${MAINTENANCE_REPORT_SECRET}
PROMETHEUS_SCRAPE_TOKEN=${PROMETHEUS_SCRAPE_TOKEN}
OTEL_METRICS_ENABLED=false
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://otel-collector:4318/v1/metrics
OTEL_SERVICE_NAME=devpilot-server
OTEL_METRIC_EXPORT_INTERVAL=60s
BACKUP_S3_URI=
BACKUP_S3_ENDPOINT_URL=
BACKUP_S3_REGION=
BACKUP_S3_ACCESS_KEY_ID=
BACKUP_S3_SECRET_ACCESS_KEY=
PUBLIC_PORT=${PUBLIC_PORT}
DEV_PILOT_PUBLIC_URL=${PUBLIC_URL}
AUTH_COOKIE_SECURE=${COOKIE_SECURE}
DEVPILOT_SERVER_IMAGE=${SERVER_IMAGE}
DEVPILOT_WEB_IMAGE=${WEB_IMAGE}
EOF

cat >"$INSTALL_DIR/docker-compose.yml" <<'EOF'
name: devpilot
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci"]
    volumes: ["mysql-data:/var/lib/mysql"]
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u root -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s
    restart: unless-stopped
  redis:
    image: redis:7.4-alpine
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASSWORD}"]
    volumes: ["redis-data:/data"]
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_PASSWORD\" ping | grep PONG"]
      interval: 10s
      timeout: 5s
      retries: 12
    restart: unless-stopped
  devpilot-server:
    image: ${DEVPILOT_SERVER_IMAGE}
    environment:
      SERVER_PORT: 8080
      DB_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
      DB_USERNAME: ${MYSQL_USER}
      DB_PASSWORD: ${MYSQL_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      DEV_PILOT_MASTER_KEY: ${DEV_PILOT_MASTER_KEY}
      MAINTENANCE_REPORT_SECRET: ${MAINTENANCE_REPORT_SECRET}
      PROMETHEUS_SCRAPE_TOKEN: ${PROMETHEUS_SCRAPE_TOKEN}
      OTEL_METRICS_ENABLED: ${OTEL_METRICS_ENABLED:-false}
      OTEL_EXPORTER_OTLP_METRICS_ENDPOINT: ${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:-http://otel-collector:4318/v1/metrics}
      OTEL_SERVICE_NAME: ${OTEL_SERVICE_NAME:-devpilot-server}
      OTEL_METRIC_EXPORT_INTERVAL: ${OTEL_METRIC_EXPORT_INTERVAL:-60s}
      DEV_PILOT_PUBLIC_URL: ${DEV_PILOT_PUBLIC_URL}
      AUTH_COOKIE_SECURE: ${AUTH_COOKIE_SECURE}
    depends_on:
      mysql: {condition: service_healthy}
      redis: {condition: service_healthy}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/actuator/health | grep '\"status\":\"UP\"'"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s
    restart: unless-stopped
  devpilot-web:
    image: ${DEVPILOT_WEB_IMAGE}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/healthz | grep ok"]
      interval: 10s
      timeout: 5s
      retries: 6
    restart: unless-stopped
  nginx:
    image: nginx:1.29-alpine
    ports: ["${PUBLIC_PORT}:80"]
    volumes: ["./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro"]
    depends_on:
      devpilot-server: {condition: service_healthy}
      devpilot-web: {condition: service_healthy}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/healthz | grep ok"]
      interval: 10s
      timeout: 5s
      retries: 6
    restart: unless-stopped
volumes:
  mysql-data:
  redis-data:
EOF

cat >"$INSTALL_DIR/nginx/default.conf" <<'EOF'
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
upstream devpilot_server { server devpilot-server:8080; keepalive 32; }
upstream devpilot_web { server devpilot-web:8080; keepalive 16; }
server {
  listen 80;
  server_name _;
  client_max_body_size 20m;
  location /api/ { proxy_pass http://devpilot_server; proxy_http_version 1.1; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header X-Forwarded-Proto $scheme; }
  location /ws/ { proxy_pass http://devpilot_server; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection $connection_upgrade; proxy_set_header Host $host; proxy_read_timeout 1h; }
  location = /actuator/prometheus { proxy_pass http://devpilot_server; proxy_http_version 1.1; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header X-Forwarded-Proto $scheme; }
  location / { proxy_pass http://devpilot_web; proxy_http_version 1.1; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; }
  location = /healthz { access_log off; add_header Content-Type text/plain; return 200 'ok'; }
}
EOF

(cd "$INSTALL_DIR" && sha256sum .env docker-compose.yml nginx/default.conf > .install-resume.sha256)
start_saved_installation
