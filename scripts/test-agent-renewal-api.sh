#!/usr/bin/env bash
# Acceptance fixture only: creates one server in an already initialized isolated installation.
# Credentials are kept in a private result directory; never enable shell tracing.
set -Eeuo pipefail
umask 077
base_url="${1:?Isolated API origin}"
admin_file="${2:?Existing isolated administrator JSON}"
state_dir="${3:?New private evidence directory}"
mode="${4:-create}"
[[ "$base_url" == http://127.0.0.1:* && -f "$admin_file" ]] || exit 2
[[ "$state_dir" == /* && "$state_dir" != / ]] || exit 2
if [[ "$mode" == create ]]; then
  [[ ! -e "$state_dir" ]] || exit 2
  mkdir -m 0700 "$state_dir"
elif [[ "$mode" == verify ]]; then
  [[ -f "$state_dir/renew-1.json" && -f "$state_dir/renew-request.json" ]] || exit 2
else exit 2; fi
request() { curl -sS --connect-timeout 5 --max-time 30 "$@"; }
expect() {
  local expected="$1" actual="$2"
  [[ "$actual" == "$expected" ]] || { printf 'Unexpected HTTP status: expected %s, received %s; private evidence retained.\n' "$expected" "$actual" >&2; exit 1; }
}
jq '{username,password}' "$admin_file" >"$state_dir/login.json"
expect 200 "$(request -o "$state_dir/session.json" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "@$state_dir/login.json" "$base_url/api/auth/login")"
access_token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
if [[ "$mode" == verify ]]; then
  server_id="$(jq -er '.data.server.id' "$state_dir/renew-1.json")"
  # Check persisted audit before replaying: the replay itself would create a new audit event.
  expect 200 "$(request -o "$state_dir/audit-after-restore.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" "$base_url/api/audit?action=RENEW_AGENT_TOKEN&size=100")"
  jq -e --arg id "$server_id" '.data.items | any(.[]; .action == "RENEW_AGENT_TOKEN" and .resourceId == $id and .result == "SUCCESS")' "$state_dir/audit-after-restore.json" >/dev/null
  jq -e --slurpfile issued "$state_dir/renew-1.json" 'tostring | contains($issued[0].data.agentToken) | not' "$state_dir/audit-after-restore.json" >/dev/null
  expect 200 "$(request -o "$state_dir/replayed-after-restart.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/renew-request.json" "$base_url/api/servers/$server_id/registration")"
  jq -es '.[0].data.agentToken == .[1].data.agentToken and .[0].data.server.id == .[1].data.server.id' "$state_dir/renew-1.json" "$state_dir/replayed-after-restart.json" >/dev/null
  expect 200 "$(request -o "$state_dir/reregistered-after-restart.json" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "@$state_dir/register-new.json" "$base_url/api/agent/register")"
  expect 401 "$(request -o "$state_dir/rejected-after-restart.json" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "@$state_dir/register-old.json" "$base_url/api/agent/register")"
  printf '%s\n' 'Verified after restart/restore: persisted renewal audit without plaintext token, same encrypted renewal result, new registration accepted and old registration rejected.'
  exit 0
fi
create_id="$(cat /proc/sys/kernel/random/uuid)"
jq -n --arg id "$create_id" '{name:("renewal-"+$id),requestId:$id}' >"$state_dir/create-request.json"
for attempt in 1 2; do
  expect 200 "$(request -o "$state_dir/create-$attempt.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/create-request.json" "$base_url/api/servers")"
done
jq -es '.[0].data.server.id == .[1].data.server.id and .[0].data.agentToken == .[1].data.agentToken' "$state_dir/create-1.json" "$state_dir/create-2.json" >/dev/null
server_id="$(jq -er '.data.server.id' "$state_dir/create-1.json")"
endpoint="$base_url/api/servers/$server_id/registration"
expect 200 "$(request -o "$state_dir/revision.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" "$endpoint")"
jq -n --arg id "$(cat /proc/sys/kernel/random/uuid)" --arg revision "$(jq -er '.data.revision' "$state_dir/revision.json")" '{requestId:$id,expectedRevision:$revision,confirmed:true}' >"$state_dir/renew-request.json"
# Two actual concurrent HTTP requests: both must return the same issued credential.
request -o "$state_dir/renew-1.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/renew-request.json" "$endpoint" >"$state_dir/status-1" &
first_pid=$!
request -o "$state_dir/renew-2.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/renew-request.json" "$endpoint" >"$state_dir/status-2" &
second_pid=$!
wait "$first_pid"
wait "$second_pid"
expect 200 "$(cat "$state_dir/status-1")"
expect 200 "$(cat "$state_dir/status-2")"
jq -es '.[0].data.agentToken == .[1].data.agentToken and .[0].data.server.id == .[1].data.server.id' "$state_dir/renew-1.json" "$state_dir/renew-2.json" >/dev/null
jq -es '.[0].data.server.id == .[1].data.server.id and .[0].data.agentToken != .[1].data.agentToken' "$state_dir/create-1.json" "$state_dir/renew-1.json" >/dev/null
jq --arg id "$(cat /proc/sys/kernel/random/uuid)" '.requestId=$id' "$state_dir/renew-request.json" >"$state_dir/stale-request.json"
expect 409 "$(request -o "$state_dir/stale-response.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/stale-request.json" "$endpoint")"
expect 409 "$(request -o "$state_dir/revoked-create-response.json" -w '%{http_code}' -H "Authorization: Bearer $access_token" -H 'Content-Type: application/json' --data-binary "@$state_dir/create-request.json" "$base_url/api/servers")"
for variant in old new; do
  credential_file="$state_dir/create-1.json"
  expected=401
  if [[ "$variant" == new ]]; then credential_file="$state_dir/renew-1.json"; expected=200; fi
  jq '{token:.data.agentToken,hostname:"renewal-host",ip:"127.0.0.1",os:"Linux",kernel:"fixture",arch:"arm64",agentVersion:"acceptance",cpuModel:"fixture",cpuCores:1,memoryTotal:1024,diskTotal:1024}' "$credential_file" >"$state_dir/register-$variant.json"
  expect "$expected" "$(request -o "$state_dir/register-$variant-response.json" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "@$state_dir/register-$variant.json" "$base_url/api/agent/register")"
done
jq -e --arg id "$server_id" '.data.serverId == $id' "$state_dir/register-new-response.json" >/dev/null
printf '%s\n' 'Verified: MySQL-backed creation replay, concurrent renewal replay, stale revision rejection, revoked creation refusal, old registration rejection and new registration on the same server.'
printf 'Private fixture evidence retained at %s; server ID %s.\n' "$state_dir" "$server_id"
