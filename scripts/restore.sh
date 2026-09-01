#!/usr/bin/env bash
set -Eeuo pipefail

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

if [[ -f "$ARCHIVE.sha256" ]]; then
  (cd "$(dirname "$ARCHIVE")" && sha256sum -c "$(basename "$ARCHIVE").sha256")
fi
if tar -tzf "$ARCHIVE" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  printf '%s\n' "Archive contains an unsafe path." >&2; exit 1
fi
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT
tar -C "$WORK_DIR" -xzf "$ARCHIVE"
grep -qx 'format=devpilot-backup-v1' "$WORK_DIR/manifest.txt" || { printf '%s\n' "Unsupported backup format." >&2; exit 1; }
[[ -s "$WORK_DIR/database.sql.gz" && -s "$WORK_DIR/environment.env" ]] || { printf '%s\n' "Backup is incomplete." >&2; exit 1; }

current_key="$(sed -n 's/^DEV_PILOT_MASTER_KEY=//p' "$INSTALL_DIR/.env" | head -n1)"
backup_key="$(sed -n 's/^DEV_PILOT_MASTER_KEY=//p' "$WORK_DIR/environment.env" | head -n1)"
[[ -n "$current_key" && "$current_key" == "$backup_key" ]] || {
  printf '%s\n' "DEV_PILOT_MASTER_KEY does not match; encrypted provider credentials would be unreadable." >&2; exit 1;
}

compose=(docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml")
database_name="$(sed -n 's/^MYSQL_DATABASE=//p' "$INSTALL_DIR/.env" | head -n1)"
[[ "$database_name" =~ ^[A-Za-z0-9_]+$ ]] || { printf '%s\n' "MYSQL_DATABASE is not a safe identifier." >&2; exit 1; }
"${compose[@]}" stop nginx devpilot-server
restore_failed="true"
trap 'if [[ "$restore_failed" == true ]]; then "${compose[@]}" up -d devpilot-server nginx >/dev/null 2>&1 || true; fi; rm -rf -- "$WORK_DIR"' EXIT
"${compose[@]}" exec -T -e DP_RESTORE_DATABASE="$database_name" mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -e "DROP DATABASE IF EXISTS \`$DP_RESTORE_DATABASE\`; CREATE DATABASE \`$DP_RESTORE_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
gzip -dc "$WORK_DIR/database.sql.gz" | "${compose[@]}" exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root "$MYSQL_DATABASE"'
restore_failed="false"
"${compose[@]}" up -d devpilot-server nginx
"${compose[@]}" ps
printf '%s\n' "Database restore completed. Verify login, Agents, applications, CI/CD credentials and deployment history."
