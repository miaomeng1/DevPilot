#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
BACKUP_DIR="${DEVPILOT_BACKUP_DIR:-/var/backups/devpilot}"
SERVER_IMAGE=""
WEB_IMAGE=""
CONFIRM=false
MIGRATIONS_REVIEWED=false
while (($#)); do
  case "$1" in
    --install-dir) INSTALL_DIR="${2:?Missing install directory}"; shift 2 ;;
    --backup-dir) BACKUP_DIR="${2:?Missing backup directory}"; shift 2 ;;
    --server-image) SERVER_IMAGE="${2:?Missing server image}"; shift 2 ;;
    --web-image) WEB_IMAGE="${2:?Missing web image}"; shift 2 ;;
    --yes) CONFIRM=true; shift ;;
    --migrations-reviewed) MIGRATIONS_REVIEWED=true; shift ;;
    -h|--help) printf '%s\n' 'Usage: upgrade.sh --server-image IMAGE@sha256:DIGEST --web-image IMAGE@sha256:DIGEST --migrations-reviewed --yes [--install-dir DIR] [--backup-dir DIR]'; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ "${EUID}" -eq 0 ]] || { printf '%s\n' 'Run this script as root.' >&2; exit 1; }
[[ "$CONFIRM" == true && "$MIGRATIONS_REVIEWED" == true ]] || {
  printf '%s\n' 'Review target release migrations and downtime/recovery requirements, then pass --migrations-reviewed --yes. This does not certify backward compatibility.' >&2; exit 2;
}
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != / && "$INSTALL_DIR" != *[[:space:]]* ]] || exit 2
for image in "$SERVER_IMAGE" "$WEB_IMAGE"; do
  [[ "$image" =~ ^[a-zA-Z0-9][a-zA-Z0-9._/:-]*@sha256:[a-f0-9]{64}$ ]] || {
    printf '%s\n' 'Both target images must use immutable sha256 digests.' >&2; exit 2;
  }
done
[[ -f "$INSTALL_DIR/docker-compose.yml" && -f "$INSTALL_DIR/.env" ]] || { printf '%s\n' 'Installation files are missing.' >&2; exit 1; }

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

# Keep this descriptor open through the child backup and service health checks.
command -v flock >/dev/null || { printf '%s\n' 'flock is required for safe maintenance.' >&2; exit 1; }
[[ ! -L "$INSTALL_DIR/.devpilot-maintenance.lock" ]] || { printf '%s\n' 'Maintenance lock must not be a symlink.' >&2; exit 1; }
if [[ ! /proc/$$/fd/9 -ef "$INSTALL_DIR/.devpilot-maintenance.lock" ]]; then
  exec 9>>"$INSTALL_DIR/.devpilot-maintenance.lock"
fi
flock -n 9 || { printf '%s\n' 'Another backup, upgrade or restore is running for this installation. No changes made; retry after it finishes.' >&2; exit 1; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$script_dir/backup.sh" ]] || { printf '%s\n' 'Install backup.sh alongside upgrade.sh.' >&2; exit 1; }
for key in DEVPILOT_SERVER_IMAGE DEVPILOT_WEB_IMAGE DEV_PILOT_MASTER_KEY; do
  [[ "$(grep -c "^${key}=" "$INSTALL_DIR/.env")" == 1 ]] || {
    printf 'Exactly one %s is required. This tool supports installer-managed Compose installations.\n' "$key" >&2; exit 1;
  }
done
compose_saved() (
  grep -qx 'name: devpilot' "$INSTALL_DIR/docker-compose.yml" || {
    printf '%s\n' 'Expected installer-managed Compose project name: devpilot. Refusing a renamed or custom project.' >&2; exit 1;
  }
  # Installer-managed services always belong to devpilot. A parent shell must
  # not redirect maintenance to another project, file, profile or env file.
  unset COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_DISABLE_ENV_FILE
  while IFS='=' read -r config_key _; do
    [[ "$config_key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    unset "$config_key"
  done < "$INSTALL_DIR/.env"
  docker compose --project-name devpilot --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" "$@"
)
compose=(compose_saved)
"${compose[@]}" config -q
"${compose[@]}" up --help | grep -q -- '--wait-timeout' || { printf '%s\n' 'Compose health waiting support is required.' >&2; exit 1; }
# Pull exact application artifacts first; never implicitly upgrade MySQL/Redis.
docker pull "$SERVER_IMAGE"
docker pull "$WEB_IMAGE"
upgrade_started=false
upgrade_done=false
trap 'if [[ "$upgrade_started" == true && "$upgrade_done" != true ]]; then "${compose[@]}" stop nginx devpilot-server >/dev/null 2>&1 || true; printf "%s\n" "Upgrade failed; gateway and server remain stopped. Database migrations may have run. Do not just switch to an old image. Inspect the error and use the pre-upgrade backup and documented recovery procedure." >&2; fi' EXIT
upgrade_started=true
"${compose[@]}" stop nginx devpilot-server
install -d -m 0700 "$BACKUP_DIR"
report_dir="$(mktemp -d "$BACKUP_DIR/.upgrade-report.XXXXXX")"
report_file="$report_dir/report.json"
bash "$script_dir/backup.sh" --install-dir "$INSTALL_DIR" --backup-dir "$BACKUP_DIR" --defer-report "$report_file"
# The backup includes the old image references and master key before changing .env.
sed -i "s|^DEVPILOT_SERVER_IMAGE=.*|DEVPILOT_SERVER_IMAGE=${SERVER_IMAGE}|; s|^DEVPILOT_WEB_IMAGE=.*|DEVPILOT_WEB_IMAGE=${WEB_IMAGE}|" "$INSTALL_DIR/.env"
"${compose[@]}" config -q
"${compose[@]}" up -d --no-deps --wait --wait-timeout 240 devpilot-server devpilot-web nginx
upgrade_done=true
if ! bash "$script_dir/backup.sh" --install-dir "$INSTALL_DIR" --report-only "$report_file"; then
  printf 'Upgrade is healthy, but backup reporting is pending. After checking platform connectivity, retry: bash %q --install-dir %q --report-only %q\n' "$script_dir/backup.sh" "$INSTALL_DIR" "$report_file" >&2
fi
printf '%s\n' 'Upgrade services are healthy. Verify administrator login, Agent heartbeats, encrypted credentials and release history before declaring acceptance. Retain the pre-upgrade backup.'
