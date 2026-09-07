#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
install_dir="$test_root/install"
backup_dir="$test_root/backups"
fake_bin="$test_root/bin"
fake_log="$test_root/docker.log"
mkdir -p "$install_dir/nginx" "$backup_dir" "$fake_bin"

cat >"$install_dir/.env" <<'EOF'
MYSQL_ROOT_PASSWORD=test-root
MYSQL_DATABASE=devpilot
DEV_PILOT_MASTER_KEY=test-master-key
EOF
printf '%s\n' 'name: devpilot' 'services: {mysql: {}, devpilot-server: {}, nginx: {}}' >"$install_dir/docker-compose.yml"
printf '%s\n' 'server { listen 80; }' >"$install_dir/nginx/default.conf"

cat >"$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"$DEVPILOT_TEST_DOCKER_LOG"
[[ -z "${COMPOSE_PROJECT_NAME:-}${COMPOSE_FILE:-}${COMPOSE_PROFILES:-}${COMPOSE_ENV_FILES:-}${COMPOSE_DISABLE_ENV_FILE:-}" ]] || exit 21
[[ "$*" == *'--project-name devpilot '* && "$*" == *' -f '* ]] || exit 22
[[ -z "${MYSQL_DATABASE:-}" && -z "${DEV_PILOT_MASTER_KEY:-}" ]] || exit 19
if [[ "$*" == *' down '* && "${DEVPILOT_TEST_FAIL_DOWN:-false}" == true ]]; then exit 20; fi
if [[ "$*" == *mysqldump* ]]; then
  # Compose exec attaches stdin unless the caller explicitly isolates it.
  if [[ "${DEVPILOT_TEST_CONSUME_STDIN:-false}" == true ]]; then cat >/dev/null; fi
  printf '%s\n' 'DROP TABLE IF EXISTS example;' 'CREATE TABLE example (id BIGINT);' 'INSERT INTO example VALUES (1);'
elif [[ "$*" == *'mysql -u root "$MYSQL_DATABASE"'* ]]; then
  cat >"$DEVPILOT_TEST_IMPORTED_SQL"
  [[ "${DEVPILOT_TEST_FAIL_IMPORT:-false}" != true ]] || exit 17
elif [[ "$*" == *'up -d --wait'* ]]; then
  [[ "${DEVPILOT_TEST_FAIL_HEALTH:-false}" != true ]] || exit 18
fi
EOF
chmod 0755 "$fake_bin/docker"

export PATH="$fake_bin:$PATH"
export DEVPILOT_TEST_DOCKER_LOG="$fake_log"
export DEVPILOT_TEST_IMPORTED_SQL="$test_root/imported.sql"
export MYSQL_DATABASE=must-not-override-saved-database
export DEV_PILOT_MASTER_KEY=must-not-override-saved-key
export COMPOSE_PROJECT_NAME=unrelated-project
export COMPOSE_FILE=/unrelated/compose.yml
export COMPOSE_PROFILES=unrelated-profile
export COMPOSE_ENV_FILES=/unrelated/environment.env
export COMPOSE_DISABLE_ENV_FILE=true

bash "$root_dir/scripts/backup.sh" --install-dir "$install_dir" --backup-dir "$backup_dir"
archive="$(find "$backup_dir" -maxdepth 1 -name 'devpilot-*.tar.gz' -print -quit)"
[[ -n "$archive" && -s "$archive" && -s "$archive.sha256" ]]
tar -tzf "$archive" | grep -q './database.sql.gz'
tar -tzf "$archive" | grep -q './environment.env'

# A streamed script must finish the archive even when Compose consumes stdin.
# Checking only exit status misses the original false-success failure.
stream_backup_dir="$test_root/stream-backups"
DEVPILOT_TEST_CONSUME_STDIN=true bash -s -- --install-dir "$install_dir" --backup-dir "$stream_backup_dir" <"$root_dir/scripts/backup.sh"
stream_archive="$(find "$stream_backup_dir" -maxdepth 1 -name 'devpilot-*.tar.gz' -print -quit)"
[[ -n "$stream_archive" && -s "$stream_archive" && -s "$stream_archive.sha256" ]]
(cd "$stream_backup_dir" && sha256sum -c "$(basename "$stream_archive").sha256")
tar -xOzf "$stream_archive" ./database.sql.gz | gzip -dc | grep -q 'INSERT INTO example VALUES (1)'

# An independent holder must block all maintenance before Docker or env writes.
exec 8>>"$install_dir/.devpilot-maintenance.lock"
flock -n 8
: >"$fake_log"
before_env="$(sha256sum "$install_dir/.env")"
if bash "$root_dir/scripts/backup.sh" --install-dir "$install_dir" --backup-dir "$test_root/blocked-backup"; then exit 1; fi
if bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$archive" --yes; then exit 1; fi
digest="$(printf '%064d' 1)"
if bash "$root_dir/scripts/upgrade.sh" --install-dir "$install_dir" --server-image "example/server@sha256:$digest" --web-image "example/web@sha256:$digest" --migrations-reviewed --yes; then exit 1; fi
[[ ! -s "$fake_log" && ! -e "$test_root/blocked-backup" && "$(sha256sum "$install_dir/.env")" == "$before_env" ]]
exec 8>&-

# A copied configuration directory has a different directory-lock inode but
# still targets the same Docker project. The host lock must cover both paths.
other_install="$test_root/other-install"
cp -a "$install_dir" "$other_install"
exec 6>>/run/devpilot-maintenance.lock
flock -n 6
: >"$fake_log"
for target in "$install_dir" "$other_install"; do
  checksum_before="$(sha256sum "$target/.env")"
  if bash "$root_dir/scripts/backup.sh" --install-dir "$target" --backup-dir "$test_root/host-blocked-backup"; then exit 1; fi
  if bash "$root_dir/scripts/restore.sh" --install-dir "$target" --archive "$archive" --yes; then exit 1; fi
  if bash "$root_dir/scripts/upgrade.sh" --install-dir "$target" --server-image "example/server@sha256:$digest" --web-image "example/web@sha256:$digest" --migrations-reviewed --yes; then exit 1; fi
  if bash "$root_dir/scripts/uninstall.sh" --install-dir "$target" --purge --yes; then exit 1; fi
  [[ "$(sha256sum "$target/.env")" == "$checksum_before" ]]
done
[[ ! -s "$fake_log" && ! -e "$test_root/host-blocked-backup" ]]
exec 6>&-

bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$archive" --yes
grep -q 'CREATE TABLE example' "$DEVPILOT_TEST_IMPORTED_SQL"
grep -q 'stop nginx devpilot-server' "$fake_log"
grep -q 'DROP DATABASE IF EXISTS' "$fake_log"
grep -q 'up -d --wait --wait-timeout 180 devpilot-server nginx' "$fake_log"

# A renamed project must be rejected, not silently operated as devpilot.
sed -i 's/^name: devpilot$/name: unrelated-project/' "$other_install/docker-compose.yml"
: >"$fake_log"
if bash "$root_dir/scripts/uninstall.sh" --install-dir "$other_install" --purge --yes; then exit 1; fi
[[ ! -s "$fake_log" ]]

expect_preflight_failure() {
  local candidate="$1"
  : >"$fake_log"
  if bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$candidate" --yes; then
    printf '%s\n' 'Expected restore rejection.' >&2; exit 1
  fi
  [[ ! -s "$fake_log" ]] || { printf '%s\n' 'Preflight failure called Docker.' >&2; exit 1; }
}

cp "$archive" "$backup_dir/missing-checksum.tar.gz"
expect_preflight_failure "$backup_dir/missing-checksum.tar.gz"
printf '%064d  ignored-name\n' 0 >"$backup_dir/missing-checksum.tar.gz.sha256"
expect_preflight_failure "$backup_dir/missing-checksum.tar.gz"

malicious="$test_root/malicious"
mkdir "$malicious"
ln -s /etc/passwd "$malicious/environment.env"
tar -C "$malicious" -czf "$backup_dir/link.tar.gz" .
sha256sum "$backup_dir/link.tar.gz" >"$backup_dir/link.tar.gz.sha256"
expect_preflight_failure "$backup_dir/link.tar.gz"

mkdir "$test_root/corrupt"
tar -C "$test_root/corrupt" -xzf "$archive"
printf '%s\n' 'not gzip' >"$test_root/corrupt/database.sql.gz"
tar -C "$test_root/corrupt" -czf "$backup_dir/corrupt.tar.gz" .
sha256sum "$backup_dir/corrupt.tar.gz" >"$backup_dir/corrupt.tar.gz.sha256"
expect_preflight_failure "$backup_dir/corrupt.tar.gz"

printf '' | gzip >"$test_root/corrupt/database.sql.gz"
tar -C "$test_root/corrupt" -czf "$backup_dir/empty.tar.gz" .
sha256sum "$backup_dir/empty.tar.gz" >"$backup_dir/empty.tar.gz.sha256"
expect_preflight_failure "$backup_dir/empty.tar.gz"

tar -C "$test_root/corrupt" -czf "$backup_dir/duplicate.tar.gz" ./environment.env ./environment.env
sha256sum "$backup_dir/duplicate.tar.gz" >"$backup_dir/duplicate.tar.gz.sha256"
expect_preflight_failure "$backup_dir/duplicate.tar.gz"

: >"$fake_log"
if DEVPILOT_TEST_FAIL_IMPORT=true bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$archive" --yes; then
  printf '%s\n' 'Expected SQL import failure.' >&2; exit 1
fi
grep -q 'stop nginx devpilot-server' "$fake_log"
if grep -q 'up -d' "$fake_log"; then
  printf '%s\n' 'Failed import must not restart services.' >&2; exit 1
fi

: >"$fake_log"
if DEVPILOT_TEST_FAIL_HEALTH=true bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$archive" --yes; then
  printf '%s\n' 'Expected service health failure.' >&2; exit 1
fi
[[ "$(grep -c 'stop nginx devpilot-server' "$fake_log")" -eq 2 ]]
tail -n1 "$fake_log" | grep -q 'stop nginx devpilot-server'

# No real containers or volumes are removed: Docker remains the local stub.
: >"$fake_log"
bash "$root_dir/scripts/uninstall.sh" --install-dir "$install_dir" --yes
grep -q 'down --remove-orphans' "$fake_log"
if grep -q -- '--volumes' "$fake_log"; then exit 1; fi
[[ "$(sha256sum "$install_dir/.env")" == "$before_env" ]]
: >"$fake_log"
printf 'n\n' | bash "$root_dir/scripts/uninstall.sh" --install-dir "$install_dir" --purge
[[ ! -s "$fake_log" ]]
exec 8>>"$install_dir/.devpilot-maintenance.lock"
flock -n 8
if bash "$root_dir/scripts/uninstall.sh" --install-dir "$install_dir" --purge --yes; then exit 1; fi
[[ ! -s "$fake_log" ]]
exec 8>&-
bash "$root_dir/scripts/uninstall.sh" --install-dir "$install_dir" --purge --yes
grep -q 'down --volumes --remove-orphans' "$fake_log"
[[ "$(sha256sum "$install_dir/.env")" == "$before_env" ]]
if DEVPILOT_TEST_FAIL_DOWN=true bash "$root_dir/scripts/uninstall.sh" --install-dir "$install_dir" --yes >"$test_root/uninstall-failed.log"; then exit 1; fi
if grep -q 'containers removed' "$test_root/uninstall-failed.log"; then exit 1; fi

printf '%s\n' 'Maintenance backup/restore verification passed.'
