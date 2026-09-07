#!/usr/bin/env bash
# Local acceptance only: privileged nested Docker, no host socket or public ports.
set -Eeuo pipefail
lab_name="${1:?Usage: start-rootless-install-lab.sh devpilot-rootless-NAME}"
[[ $# == 1 && "$lab_name" =~ ^devpilot-rootless-[a-z0-9][a-z0-9-]{0,40}$ ]] || {
  echo 'Use one unique devpilot-rootless-NAME (lowercase letters, digits, hyphens).' >&2; exit 2;
}
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docker info >/dev/null
docker image inspect devpilot/install-lab:rootless-candidate >/dev/null
if docker container inspect "$lab_name" >/dev/null 2>&1; then
  echo 'Lab already exists; inspect it instead of replacing its data.' >&2; exit 1
fi
# /run is volatile on a real host. Persisting rootless containerd PID/socket
# state here can prevent the outer container from starting a second time.
docker run -d --name "$lab_name" --privileged --network none --cpus 2 --memory 2g \
  --tmpfs /run/user/1000:rw,nosuid,nodev,mode=0700,uid=1000,gid=1000 \
  -v "$repo_dir:/workspace:ro" devpilot/install-lab:rootless-candidate
echo 'Retained local lab created; wait for devpilot-lab-exec docker info before use.'
echo 'No host port is published. Do not remove the lab until configuration and backups are exported.'
