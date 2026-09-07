#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
ARCHIVE=""
CONFIRM="false"

while (($#)); do
  case "$1" in
    --archive) ARCHIVE="${2:-}"; shift 2 ;;
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --yes) CONFIRM="true"; shift ;;
    -h|--help) printf '%s\n' "Usage: restore.sh --archive FILE [--install-dir /opt/devpilot] --yes"; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this script as root." >&2; exit 1; }
[[ "$CONFIRM" == "true" ]] || { printf '%s\n' "Restore replaces the current database. Re-run with --yes after verifying the archive." >&2; exit 2; }
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "Invalid install directory." >&2; exit 2; }
[[ -n "$ARCHIVE" && -f "$ARCHIVE" ]] || { printf '%s\n' "A readable --archive is required." >&2; exit 2; }
[[ -f "$INSTALL_DIR/docker-compose.yml" && -f "$INSTALL_DIR/.env" ]] || { printf 'DevPilot is not installed in %s.\n' "$INSTALL_DIR" >&2; exit 1; }

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

# Share the same kernel lock with backup/upgrade; inherited descriptor 9 is
# reused only when it references this installation's lock inode.
command -v flock >/dev/null || { printf '%s\n' 'flock is required for safe maintenance.' >&2; exit 1; }
[[ ! -L "$INSTALL_DIR/.devpilot-maintenance.lock" ]] || { printf '%s\n' 'Maintenance lock must not be a symlink.' >&2; exit 1; }
if [[ ! /proc/$$/fd/9 -ef "$INSTALL_DIR/.devpilot-maintenance.lock" ]]; then
  exec 9>>"$INSTALL_DIR/.devpilot-maintenance.lock"
fi
flock -n 9 || { printf '%s\n' 'Another backup, upgrade or restore is running for this installation. No changes made; retry after it finishes.' >&2; exit 1; }

[[ -s "$ARCHIVE.sha256" ]] || { printf '%s\n' "Checksum sidecar is required." >&2; exit 1; }
# Verify this archive, not arbitrary paths supplied by the sidecar.
expected_checksum="$(awk 'NR == 1 {print $1}' "$ARCHIVE.sha256")"
[[ "$expected_checksum" =~ ^[a-fA-F0-9]{64}$ ]] || { printf '%s\n' "Invalid checksum." >&2; exit 1; }
actual_checksum="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
[[ "${expected_checksum,,}" == "$actual_checksum" ]] || { printf '%s\n' "Archive checksum mismatch." >&2; exit 1; }
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
tar -tzf "$ARCHIVE" >"$WORK_DIR/members"
while IFS= read -r member; do
  case "$member" in
    ./|./nginx/|./database.sql.gz|./environment.env|./docker-compose.yml|./manifest.txt|./nginx/default.conf) ;;
    *) printf '%s\n' "Archive contains an unsupported path." >&2; exit 1 ;;
  esac
done <"$WORK_DIR/members"
[[ -z "$(sort "$WORK_DIR/members" | uniq -d)" ]] || { printf '%s\n' "Duplicate archive entries." >&2; exit 1; }
# Reject symlinks, hardlinks and device entries before extraction.
LC_ALL=C tar -tvzf "$ARCHIVE" >"$WORK_DIR/types"
if grep -qvE '^[-d]' "$WORK_DIR/types"; then
  printf '%s\n' "Archive must contain only regular files and directories." >&2; exit 1
fi
tar --no-same-owner --no-same-permissions -C "$WORK_DIR" -xzf "$ARCHIVE"
grep -qx 'format=devpilot-backup-v1' "$WORK_DIR/manifest.txt" || { printf '%s\n' "Unsupported backup format." >&2; exit 1; }
[[ -s "$WORK_DIR/database.sql.gz" && -s "$WORK_DIR/environment.env" ]] || { printf '%s\n' "Backup is incomplete." >&2; exit 1; }
gzip -t "$WORK_DIR/database.sql.gz"
[[ "$(gzip -dc "$WORK_DIR/database.sql.gz" | wc -c)" -gt 0 ]] || { printf '%s\n' "Database dump is empty." >&2; exit 1; }

current_key="$(sed -n 's/^DEV_PILOT_MASTER_KEY=//p' "$INSTALL_DIR/.env" | head -n1)"
backup_key="$(sed -n 's/^DEV_PILOT_MASTER_KEY=//p' "$WORK_DIR/environment.env" | head -n1)"
[[ -n "$current_key" && "$current_key" == "$backup_key" ]] || {
  printf '%s\n' "DEV_PILOT_MASTER_KEY does not match; encrypted provider credentials would be unreadable." >&2; exit 1;
}

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
database_name="$(sed -n 's/^MYSQL_DATABASE=//p' "$INSTALL_DIR/.env" | head -n1)"
[[ "$database_name" =~ ^[A-Za-z0-9_]+$ ]] || { printf '%s\n' "MYSQL_DATABASE is not a safe identifier." >&2; exit 1; }
"${compose[@]}" stop nginx devpilot-server
restore_failed="true"
trap 'if [[ "$restore_failed" == true ]]; then printf "%s\n" "Restore failed. Server and gateway remain stopped: the database may be incomplete. Correct the cause and repeat the restore before starting services." >&2; fi; rm -rf -- "$WORK_DIR"' EXIT
"${compose[@]}" exec -T -e DP_RESTORE_DATABASE="$database_name" mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -e "DROP DATABASE IF EXISTS \`$DP_RESTORE_DATABASE\`; CREATE DATABASE \`$DP_RESTORE_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
gzip -dc "$WORK_DIR/database.sql.gz" | "${compose[@]}" exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root "$MYSQL_DATABASE"'
"${compose[@]}" up -d --wait --wait-timeout 180 devpilot-server nginx || {
  "${compose[@]}" stop nginx devpilot-server || true
  exit 1
}
restore_failed="false"
"${compose[@]}" ps
printf '%s\n' "Database restore completed. Verify login, Agents, applications, CI/CD credentials and deployment history."
