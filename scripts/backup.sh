#!/usr/bin/env bash
set -Eeuo pipefail

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
BACKUP_DIR="${DEVPILOT_BACKUP_DIR:-/var/backups/devpilot}"

while (($#)); do
  case "$1" in
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --backup-dir) BACKUP_DIR="${2:-}"; shift 2 ;;
    -h|--help) printf '%s\n' "Usage: backup.sh [--install-dir /opt/devpilot] [--backup-dir /var/backups/devpilot]"; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this script as root." >&2; exit 1; }
for path in "$INSTALL_DIR" "$BACKUP_DIR"; do
  [[ "$path" == /* && "$path" != "/" && "$path" != *[[:space:]]* ]] || {
    printf 'Path must be a non-root absolute path without whitespace: %s\n' "$path" >&2; exit 2;
  }
done
[[ -f "$INSTALL_DIR/docker-compose.yml" && -f "$INSTALL_DIR/.env" ]] || {
  printf 'DevPilot is not installed in %s.\n' "$INSTALL_DIR" >&2; exit 1;
}
command -v gzip >/dev/null && command -v tar >/dev/null || { printf '%s\n' "gzip and tar are required." >&2; exit 1; }

install -d -m 0700 "$BACKUP_DIR"
WORK_DIR="$(mktemp -d "$BACKUP_DIR/.devpilot-backup.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE="$BACKUP_DIR/devpilot-${TIMESTAMP}.tar.gz"

docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines --triggers --events "$MYSQL_DATABASE"' \
  | gzip -9 >"$WORK_DIR/database.sql.gz"
install -m 0600 "$INSTALL_DIR/.env" "$WORK_DIR/environment.env"
install -m 0600 "$INSTALL_DIR/docker-compose.yml" "$WORK_DIR/docker-compose.yml"
if [[ -f "$INSTALL_DIR/nginx/default.conf" ]]; then
  install -D -m 0600 "$INSTALL_DIR/nginx/default.conf" "$WORK_DIR/nginx/default.conf"
fi
cat >"$WORK_DIR/manifest.txt" <<EOF
format=devpilot-backup-v1
created_at=${TIMESTAMP}
includes=mysql,environment,compose,nginx
redis=excluded_ephemeral_raw_metrics
EOF

tar -C "$WORK_DIR" -czf "$ARCHIVE" .
chmod 0600 "$ARCHIVE"
sha256sum "$ARCHIVE" >"$ARCHIVE.sha256"
chmod 0600 "$ARCHIVE.sha256"
printf 'Backup created: %s\nChecksum: %s.sha256\n' "$ARCHIVE" "$ARCHIVE"
printf '%s\n' "The archive contains production secrets. Store it encrypted and off-host."
