#!/usr/bin/env bash
set -Eeuo pipefail

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
PUBLIC_PORT="${PUBLIC_PORT:-8080}"
PUBLIC_URL="${DEV_PILOT_PUBLIC_URL:-}"
SERVER_IMAGE="${DEVPILOT_SERVER_IMAGE:-devpilot/server:1.0.0}"
WEB_IMAGE="${DEVPILOT_WEB_IMAGE:-devpilot/web:1.0.0}"

while (($#)); do
  case "$1" in
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --port) PUBLIC_PORT="${2:-}"; shift 2 ;;
    --public-url) PUBLIC_URL="${2:-}"; shift 2 ;;
    --server-image) SERVER_IMAGE="${2:-}"; shift 2 ;;
    --web-image) WEB_IMAGE="${2:-}"; shift 2 ;;
    -h|--help) printf '%s\n' "Usage: install.sh [--port 8080] [--public-url URL] [--server-image IMAGE] [--web-image IMAGE]"; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == "Linux" ]] || { printf '%s\n' "DevPilot Server supports Linux only." >&2; exit 1; }
[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this installer as root." >&2; exit 1; }
[[ "$PUBLIC_PORT" =~ ^[0-9]+$ ]] && ((PUBLIC_PORT >= 1 && PUBLIC_PORT <= 65535)) || { printf '%s\n' "Invalid public port." >&2; exit 2; }
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "--install-dir must be a non-root absolute path without whitespace." >&2; exit 2; }

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
command -v openssl >/dev/null || { printf '%s\n' "openssl is required." >&2; exit 1; }
command -v curl >/dev/null || { printf '%s\n' "curl is required." >&2; exit 1; }

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

install -d -m 0750 "$INSTALL_DIR/nginx"
umask 077
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
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/actuator/health | grep '\"status\":\"UP\"'"]
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

cd "$INSTALL_DIR"
docker compose --env-file .env pull
docker compose --env-file .env up -d

printf '%s\n' "Waiting for DevPilot health checks..."
for attempt in {1..60}; do
  if curl -fsS "http://127.0.0.1:${PUBLIC_PORT}/healthz" >/dev/null 2>&1; then
    printf '\n=======================================\nDevPilot installed successfully!\n\nURL: %s\nCreate the first administrator in the browser.\n=======================================\n' "$PUBLIC_URL"
    exit 0
  fi
  sleep 2
done

docker compose --env-file .env ps >&2
printf '%s\n' "DevPilot did not become healthy in time. Inspect: docker compose -f ${INSTALL_DIR}/docker-compose.yml logs" >&2
exit 1
