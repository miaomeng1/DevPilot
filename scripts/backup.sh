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
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%S)"
TIMESTAMP="${CREATED_AT//[-:]/}Z"
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
(cd "$BACKUP_DIR" && sha256sum "$(basename "$ARCHIVE")") >"$ARCHIVE.sha256"
chmod 0600 "$ARCHIVE.sha256"
(cd "$BACKUP_DIR" && sha256sum -c "$(basename "$ARCHIVE").sha256") >/dev/null

read_env() {
  sed -n "s/^${1}=//p" "$INSTALL_DIR/.env" | head -n1
}

DESTINATION_TYPE="LOCAL"
REMOTE_FAILED=0
S3_URI="$(read_env BACKUP_S3_URI)"
if [[ -n "$S3_URI" ]]; then
  if [[ ! "$S3_URI" =~ ^s3://[^/[:space:]]+(/[^[:space:]]*)?$ ]]; then
    printf '%s\n' "Warning: BACKUP_S3_URI is invalid; local backup remains valid." >&2
    REMOTE_FAILED=1
  elif ! command -v aws >/dev/null; then
    printf '%s\n' "Warning: AWS CLI is required for the configured S3 backup; local backup remains valid." >&2
    REMOTE_FAILED=1
  else
    S3_ENDPOINT="$(read_env BACKUP_S3_ENDPOINT_URL)"
    S3_REGION="$(read_env BACKUP_S3_REGION)"
    S3_ACCESS_KEY="$(read_env BACKUP_S3_ACCESS_KEY_ID)"
    S3_SECRET_KEY="$(read_env BACKUP_S3_SECRET_ACCESS_KEY)"
    [[ -z "$S3_REGION" ]] || export AWS_DEFAULT_REGION="$S3_REGION"
    [[ -z "$S3_ACCESS_KEY" ]] || export AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY"
    [[ -z "$S3_SECRET_KEY" ]] || export AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY"
    AWS_ARGS=(--no-cli-pager)
    [[ -z "$S3_ENDPOINT" ]] || AWS_ARGS+=(--endpoint-url "$S3_ENDPOINT")
    REMOTE_ARCHIVE="${S3_URI%/}/$(basename "$ARCHIVE")"
    REMOTE_CHECKSUM="${REMOTE_ARCHIVE}.sha256"
    S3_PATH="${REMOTE_ARCHIVE#s3://}"
    S3_BUCKET="${S3_PATH%%/*}"
    S3_KEY="${S3_PATH#*/}"
    if aws "${AWS_ARGS[@]}" s3 cp "$ARCHIVE" "$REMOTE_ARCHIVE" --only-show-errors \
        && aws "${AWS_ARGS[@]}" s3 cp "$ARCHIVE.sha256" "$REMOTE_CHECKSUM" --only-show-errors; then
      LOCAL_SIZE="$(stat -c '%s' "$ARCHIVE")"
      REMOTE_SIZE="$(aws "${AWS_ARGS[@]}" s3api head-object --bucket "$S3_BUCKET" --key "$S3_KEY" \
        --query ContentLength --output text 2>/dev/null || true)"
      if [[ "$REMOTE_SIZE" == "$LOCAL_SIZE" ]]; then
        DESTINATION_TYPE="S3"
        printf 'Off-host copy verified: %s (%s bytes)\n' "$REMOTE_ARCHIVE" "$REMOTE_SIZE"
      else
        printf '%s\n' "Warning: S3 object size verification failed; local backup remains valid." >&2
        REMOTE_FAILED=1
      fi
    else
      printf '%s\n' "Warning: S3 upload failed; local backup remains valid." >&2
      REMOTE_FAILED=1
    fi
  fi
fi

REPORT_SECRET="$(read_env MAINTENANCE_REPORT_SECRET)"
PUBLIC_URL="$(read_env DEV_PILOT_PUBLIC_URL)"
if [[ -n "$REPORT_SECRET" && -n "$PUBLIC_URL" ]] && command -v openssl >/dev/null && command -v curl >/dev/null; then
  PAYLOAD_FILE="$WORK_DIR/report.json"
  CHECKSUM="$(awk '{print $1}' "$ARCHIVE.sha256")"
  SIZE_BYTES="$(stat -c '%s' "$ARCHIVE")"
  printf '{"fileName":"%s","sizeBytes":%s,"sha256":"%s","destinationType":"%s","createdAt":"%s"}' \
    "$(basename "$ARCHIVE")" "$SIZE_BYTES" "$CHECKSUM" "$DESTINATION_TYPE" "$CREATED_AT" >"$PAYLOAD_FILE"
  SIGNATURE="$(openssl dgst -sha256 -hmac "$REPORT_SECRET" "$PAYLOAD_FILE" | awk '{print $NF}')"
  if ! curl -fsS --connect-timeout 5 --max-time 15 \
    -H 'Content-Type: application/json' -H "X-DevPilot-Signature: sha256=${SIGNATURE}" \
    --data-binary "@$PAYLOAD_FILE" "${PUBLIC_URL%/}/api/maintenance/backups/report" >/dev/null; then
    printf '%s\n' "Warning: backup is valid, but DevPilot did not accept the status report." >&2
  fi
fi
printf 'Backup created: %s\nChecksum: %s.sha256\n' "$ARCHIVE" "$ARCHIVE"
if [[ "$DESTINATION_TYPE" == "LOCAL" ]]; then
  printf '%s\n' "The archive contains production secrets. Store it encrypted and off-host."
fi
((REMOTE_FAILED == 0)) || exit 1
