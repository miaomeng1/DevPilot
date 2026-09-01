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
printf '%s\n' 'services: {mysql: {}, devpilot-server: {}, nginx: {}}' >"$install_dir/docker-compose.yml"
printf '%s\n' 'server { listen 80; }' >"$install_dir/nginx/default.conf"

cat >"$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"$DEVPILOT_TEST_DOCKER_LOG"
if [[ "$*" == *mysqldump* ]]; then
  printf '%s\n' 'DROP TABLE IF EXISTS example;' 'CREATE TABLE example (id BIGINT);' 'INSERT INTO example VALUES (1);'
elif [[ "$*" == *'mysql -u root "$MYSQL_DATABASE"'* ]]; then
  cat >"$DEVPILOT_TEST_IMPORTED_SQL"
fi
EOF
chmod 0755 "$fake_bin/docker"

export PATH="$fake_bin:$PATH"
export DEVPILOT_TEST_DOCKER_LOG="$fake_log"
export DEVPILOT_TEST_IMPORTED_SQL="$test_root/imported.sql"

bash "$root_dir/scripts/backup.sh" --install-dir "$install_dir" --backup-dir "$backup_dir"
archive="$(find "$backup_dir" -maxdepth 1 -name 'devpilot-*.tar.gz' -print -quit)"
[[ -n "$archive" && -s "$archive" && -s "$archive.sha256" ]]
tar -tzf "$archive" | grep -q './database.sql.gz'
tar -tzf "$archive" | grep -q './environment.env'

bash "$root_dir/scripts/restore.sh" --install-dir "$install_dir" --archive "$archive" --yes
grep -q 'CREATE TABLE example' "$DEVPILOT_TEST_IMPORTED_SQL"
grep -q 'stop nginx devpilot-server' "$fake_log"
grep -q 'DROP DATABASE IF EXISTS' "$fake_log"
grep -q 'up -d devpilot-server nginx' "$fake_log"

printf '%s\n' 'Maintenance backup/restore verification passed.'
