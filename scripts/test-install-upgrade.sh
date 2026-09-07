#!/usr/bin/env bash
set -Eeuo pipefail
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
mkdir "$test_root/bin"
export DP_TEST_LOG="$test_root/docker.log"
export PATH="$test_root/bin:$PATH"
cat >"$test_root/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"$DP_TEST_LOG"
if [[ "$*" == *'--env-file'* ]]; then
  [[ -z "${COMPOSE_PROJECT_NAME:-}${COMPOSE_FILE:-}${COMPOSE_PROFILES:-}${COMPOSE_ENV_FILES:-}${COMPOSE_DISABLE_ENV_FILE:-}" ]] || exit 21
  [[ "$*" == *'--project-name devpilot '* && "$*" == *' -f '* ]] || exit 22
fi
if [[ "${DP_TEST_NO_ENV_OVERRIDE:-false}" == true && "$*" == *'--env-file'* ]]; then
  [[ -z "${DEVPILOT_SERVER_IMAGE:-}" && -z "${DEV_PILOT_MASTER_KEY:-}" ]] || exit 19
fi
case "$*" in
  *'up --help'*) printf '%s\n' '--wait-timeout' ;;
  'ps -aq'*) [[ "${DP_TEST_EXISTING:-false}" != true ]] || printf existing ;;
  *mysqldump*) [[ "${DP_TEST_BACKUP_FAIL:-false}" != true ]] || exit 1; printf 'SELECT 1;\n' ;;
  *'up -d'*) [[ "${DP_TEST_HEALTH_FAIL:-false}" != true ]] || exit 1 ;;
esac
exit 0
EOF
cat >"$test_root/bin/ss" <<'EOF'
#!/usr/bin/env bash
if [[ "${DP_TEST_PORT_BUSY:-false}" == true ]]; then printf 'LISTEN 0 128 0.0.0.0:18081 0.0.0.0:*\n'; fi
EOF
cat >"$test_root/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"$DP_TEST_LOG"
[[ "${DP_TEST_REPORT_FAIL:-false}" != true ]] || exit 1
exit 0
EOF
cat >"$test_root/bin/timedatectl" <<'EOF'
#!/usr/bin/env bash
[[ "$*" == 'show --property=NTPSynchronized --property=LocalRTC' ]] || exit 90
case "${DP_TEST_CLOCK:-synced}" in
  synced) printf 'NTPSynchronized=yes\nLocalRTC=no\n' ;;
  unsynced) printf 'NTPSynchronized=no\nLocalRTC=no\n' ;;
  localrtc) printf 'NTPSynchronized=yes\nLocalRTC=yes\n' ;;
  unavailable) exit 1 ;;
  incomplete) printf 'NTPSynchronized=yes\n' ;;
esac
EOF
chmod +x "$test_root/bin/"*
export COMPOSE_PROJECT_NAME=unrelated-project
export COMPOSE_FILE=/unrelated/compose.yml
export COMPOSE_PROFILES=unrelated-profile
export COMPOSE_ENV_FILES=/unrelated/environment.env
export COMPOSE_DISABLE_ENV_FILE=true

install_args=(--install-dir "$test_root/install" --port 18081 --public-url http://localhost:18081 --server-image example/server:1 --web-image example/web:1)
# Clock checks are read-only advisories, including for hosts without systemd.
# Use an invalid port to stop before host locks, files or Docker operations.
for clock_case in synced unsynced localrtc unavailable incomplete; do
  if DP_TEST_CLOCK="$clock_case" bash "$root_dir/scripts/install.sh" "${install_args[@]}" --port invalid >"$test_root/clock.log" 2>&1; then exit 1; fi
  grep -q 'Invalid public port' "$test_root/clock.log"
  if [[ "$clock_case" == synced ]]; then
    if grep -q 'Host clock synchronization' "$test_root/clock.log"; then exit 1; fi
  else
    grep -q 'Host clock synchronization' "$test_root/clock.log"
    grep -q 'does not adjust the clock' "$test_root/clock.log"
  fi
  [[ ! -e "$test_root/install" && ! -s "$DP_TEST_LOG" ]]
done
# A host-wide holder blocks a fresh installation before even Docker preflight;
# an unrelated installation directory cannot bypass the lock.
(umask 077; touch /run/devpilot-maintenance.lock)
exec 6>>/run/devpilot-maintenance.lock
flock -n 6
if bash "$root_dir/scripts/install.sh" "${install_args[@]}"; then exit 1; fi
[[ ! -e "$test_root/install" && ! -s "$DP_TEST_LOG" ]]
exec 6>&-
if DP_TEST_PORT_BUSY=true bash "$root_dir/scripts/install.sh" "${install_args[@]}"; then exit 1; fi
[[ ! -e "$test_root/install" ]]
if DP_TEST_EXISTING=true bash "$root_dir/scripts/install.sh" "${install_args[@]}"; then exit 1; fi
[[ ! -e "$test_root/install" ]]
bash "$root_dir/scripts/install.sh" "${install_args[@]}"
original_checksum="$(sha256sum "$test_root/install/.env")"
if bash "$root_dir/scripts/install.sh" "${install_args[@]}"; then exit 1; fi
[[ "$(sha256sum "$test_root/install/.env")" == "$original_checksum" ]]
grep -q 'wget -qO- http://127.0.0.1:8080/actuator/health' "$test_root/install/docker-compose.yml"
grep -q 'up -d --wait --wait-timeout 240' "$DP_TEST_LOG"

# A failed first startup preserves generated keys; resume uses only those files.
resume_dir="$test_root/resume-install"
if DP_TEST_HEALTH_FAIL=true bash "$root_dir/scripts/install.sh" --install-dir "$resume_dir" --port 18082 --public-url http://localhost:18082 --server-image example/server:1 --web-image example/web:1; then exit 1; fi
[[ -f "$resume_dir/.install-resume.sha256" && ! -e "$resume_dir/.installation-complete" ]]
saved_env="$(sha256sum "$resume_dir/.env")"
saved_compose="$(sha256sum "$resume_dir/docker-compose.yml")"
: >"$DP_TEST_LOG"
exec 6>>/run/devpilot-maintenance.lock
flock -n 6
if bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" && "$(sha256sum "$resume_dir/.env")" == "$saved_env" ]]
exec 6>&-
if bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir" --port 18083; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" && "$(sha256sum "$resume_dir/.env")" == "$saved_env" ]]
exec 8>>"$resume_dir/.devpilot-maintenance.lock"
flock -n 8
if bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" ]]
exec 8>&-
cp "$resume_dir/nginx/default.conf" "$test_root/saved-nginx.conf"
printf '# changed\n' >>"$resume_dir/nginx/default.conf"
if bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" ]]
cp "$test_root/saved-nginx.conf" "$resume_dir/nginx/default.conf"
DP_TEST_NO_ENV_OVERRIDE=true DEVPILOT_SERVER_IMAGE=unexpected/image DEV_PILOT_MASTER_KEY=unexpected-key bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir" --offline
[[ -f "$resume_dir/.installation-complete" && "$(sha256sum "$resume_dir/.env")" == "$saved_env" && "$(sha256sum "$resume_dir/docker-compose.yml")" == "$saved_compose" ]]
if grep -Eq '(^| )pull($| )' "$DP_TEST_LOG"; then exit 1; fi
grep -q -- '--pull never' "$DP_TEST_LOG"
: >"$DP_TEST_LOG"
if bash "$root_dir/scripts/install.sh" --resume --install-dir "$resume_dir"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" ]]

digest="$(printf '%064d' 1)"
upgrade_args=(--install-dir "$test_root/install" --backup-dir "$test_root/backups" --server-image "example/server@sha256:$digest" --web-image "example/web@sha256:$digest" --migrations-reviewed --yes)
cp "$test_root/install/.env" "$test_root/original.env"
export DP_TEST_NO_ENV_OVERRIDE=true
export DEVPILOT_SERVER_IMAGE=must-not-override-upgrade-target
export DEV_PILOT_MASTER_KEY=must-not-override-saved-key
: >"$DP_TEST_LOG"
if DP_TEST_BACKUP_FAIL=true bash "$root_dir/scripts/upgrade.sh" "${upgrade_args[@]}"; then exit 1; fi
cmp "$test_root/original.env" "$test_root/install/.env"
if grep -q 'up -d' "$DP_TEST_LOG"; then exit 1; fi

: >"$DP_TEST_LOG"
bash "$root_dir/scripts/upgrade.sh" "${upgrade_args[@]}"
grep -q "^DEVPILOT_SERVER_IMAGE=example/server@sha256:$digest$" "$test_root/install/.env"
backup="$(find "$test_root/backups" -name '*.tar.gz' -size +0c -print -quit)"
tar -xOzf "$backup" ./environment.env | cmp "$test_root/original.env" -
awk '/stop nginx devpilot-server/ {stopped=1} /mysqldump/ {if (!stopped) exit 1; backed=1} /up -d/ {if (!backed) exit 1; started=1} END {if (!started) exit 1}' "$DP_TEST_LOG"
awk '/stop nginx devpilot-server/ {stopped=1} /up -d/ {started=1} /backups\/report/ {if (stopped && !started) exit 1; reported=1} END {if (!reported) exit 1}' "$DP_TEST_LOG"

# Reporting failure does not stop an otherwise healthy upgraded platform.
: >"$DP_TEST_LOG"
DP_TEST_REPORT_FAIL=true bash "$root_dir/scripts/upgrade.sh" "${upgrade_args[@]}"
[[ "$(grep -c 'stop nginx devpilot-server' "$DP_TEST_LOG")" == 1 ]]
report="$(find "$test_root/backups" -name report.json -print -quit)"
[[ -s "$report" && "$(stat -c '%a' "$report")" == 600 ]]
: >"$DP_TEST_LOG"
bash "$root_dir/scripts/backup.sh" --install-dir "$test_root/install" --report-only "$report"
if grep -qE 'mysqldump|up -d|stop nginx' "$DP_TEST_LOG"; then exit 1; fi
ln -s "$report" "$test_root/report-link.json"
: >"$DP_TEST_LOG"
if bash "$root_dir/scripts/backup.sh" --install-dir "$test_root/install" --report-only "$test_root/report-link.json"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" ]]
if bash "$root_dir/scripts/backup.sh" --install-dir "$test_root/install" --defer-report "$report"; then exit 1; fi
[[ ! -s "$DP_TEST_LOG" ]]

: >"$DP_TEST_LOG"
if DP_TEST_HEALTH_FAIL=true bash "$root_dir/scripts/upgrade.sh" "${upgrade_args[@]}"; then exit 1; fi
tail -n1 "$DP_TEST_LOG" | grep -q 'stop nginx devpilot-server'
printf '%s\n' 'Install/upgrade lifecycle tests passed (simulated Docker).'
