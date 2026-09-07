#!/usr/bin/env bash
set -Eeuo pipefail

# Execute installer/maintenance inside the same user and mount namespaces as
# the rootless daemon. Creating 0700 config outside them as outer UID 0 makes
# the directory inaccessible to nested containers; never fix that with chmod.
[[ "$(id -u)" == 1000 ]] || { echo 'Run as the lab default UID 1000, not outer root.' >&2; exit 1; }
[[ $# -gt 0 ]] || { echo 'Usage: devpilot-lab-exec COMMAND [ARGS...]' >&2; exit 2; }
export DOCKER_HOST=unix:///run/user/1000/docker.sock
mapfile -t daemon_pids < <(pgrep -x dockerd)
[[ ${#daemon_pids[@]} == 1 ]] || { echo 'Expected exactly one local rootless daemon.' >&2; exit 1; }
daemon_pid="${daemon_pids[0]}"
[[ "$(stat -c %u "/proc/$daemon_pid")" == 1000 ]] || exit 1
awk '$1 == 0 && $2 == 1000 && $3 == 1 { found=1 } END { exit !found }' "/proc/$daemon_pid/uid_map"
docker info --format '{{json .SecurityOptions}}' | grep -q '"name=rootless"'
exec nsenter -t "$daemon_pid" -U -m -n -S 0 -G 0 -- "$@"
