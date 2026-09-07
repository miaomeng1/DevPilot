#!/usr/bin/env bash
# Verification against an isolated installation. --seed-binding creates only
# an application reference to an existing container; never deploys or restarts.
set -Eeuo pipefail
umask 077
base_url="${1:?Loopback API origin}"
identity_dir="${2:?Private persistence identity directory}"
state_dir="${3:?New private result directory}"
seed_binding="${4:-}"
[[ -z "$seed_binding" || "$seed_binding" == --seed-binding ]] || exit 2
[[ "$base_url" =~ ^http://127\.0\.0\.1:[0-9]+$ && "$state_dir" == /* && "$state_dir" != / ]] || exit 2
[[ -f "$identity_dir/admin.json" && ! -e "$state_dir" ]] || exit 2
mkdir -m 0700 "$state_dir"
jq '{username,password}' "$identity_dir/admin.json" >"$state_dir/login.json"
curl -fsS --connect-timeout 5 --max-time 30 -H 'Content-Type: application/json' \
  --data-binary "@$state_dir/login.json" "$base_url/api/auth/login" >"$state_dir/session.json"
token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
if [[ "$seed_binding" == --seed-binding ]]; then
  server_id="$(jq -er '.data.server.id' "$identity_dir/server.json")"
  curl -fsS --connect-timeout 5 --max-time 30 -H "Authorization: Bearer $token" \
    "$base_url/api/docker/containers?serverId=$server_id" >"$state_dir/containers.json"
  snapshot_id="$(jq -er --arg server "$server_id" '[.data[] | select((.serverId | tostring) == $server and .state == "running")][0].id' "$state_dir/containers.json")"
  jq -n --arg server "$server_id" --arg snapshot "$snapshot_id" \
    '{name:"Runtime observation fixture",code:"runtime-observation-fixture",environment:"TEST",serverId:$server,containerSnapshotId:$snapshot}' >"$state_dir/application-request.json"
  curl -fsS --connect-timeout 5 --max-time 30 -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    --data-binary "@$state_dir/application-request.json" "$base_url/api/applications" >"$state_dir/application-created.json"
  jq -e '.code == 0 and .data.containerObservedAt != null' "$state_dir/application-created.json" >/dev/null
fi
for resource in applications dashboard; do
  curl -fsS --connect-timeout 5 --max-time 30 -H "Authorization: Bearer $token" \
    "$base_url/api/$resource" >"$state_dir/$resource.json"
  jq -e '.code == 0' "$state_dir/$resource.json" >/dev/null
done
jq -e '.data.summary | .applicationTotal > 0 and .applicationHealthy >= 0 and .applicationUnhealthy >= 0 and .applicationUnknown >= 0
  and .applicationTotal == (.applicationHealthy + .applicationUnhealthy + .applicationUnknown)' "$state_dir/dashboard.json" >/dev/null
jq -e '.data | length > 0 and all(.agentStatus != null)
  and any(.containerObservedAt != null)
  and any(.containerSnapshotId == null and .status == "UNKNOWN" and (.runtimeObservationMessage | length > 0))' "$state_dir/applications.json" >/dev/null
jq '.data.summary | {applicationTotal,applicationHealthy,applicationUnhealthy,applicationUnknown}' "$state_dir/dashboard.json"
printf 'Verified: real dashboard classification partition, Agent observation fields and unbound application UNKNOWN.\n'
