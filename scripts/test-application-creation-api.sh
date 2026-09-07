#!/usr/bin/env bash
# Isolated acceptance only: creates one metadata-only app, never a provider deployment.
set -Eeuo pipefail
umask 077
mode="${1:?seed or verify}"
base="${2:?Loopback URL}"
identity="${3:?Private existing identity directory}"
evidence="${4:?Private evidence directory}"
[[ "$mode" == seed || "$mode" == verify ]] || exit 2
[[ "$base" =~ ^http://127\.0\.0\.1:[0-9]+$ && "$evidence" == /* && "$evidence" != / ]] || exit 2
[[ -f "$identity/admin.json" && -f "$identity/server.json" ]] || exit 2
if [[ "$mode" == seed ]]; then
  [[ ! -e "$evidence" ]] || { printf '%s\n' 'Evidence exists; use verify, never replace the request.' >&2; exit 2; }
  mkdir -m 0700 "$evidence"
else
  [[ -f "$evidence/request.json" && -f "$evidence/original.json" ]] || exit 2
fi
jq '{username,password}' "$identity/admin.json" >"$evidence/login.json"
curl -fsS --connect-timeout 5 --max-time 30 -H 'Content-Type: application/json' \
  --data-binary "@$evidence/login.json" "$base/api/auth/login" >"$evidence/session.json"
token="$(jq -er '.data.accessToken' "$evidence/session.json")"
request() {
  local file="$1"
  curl -fsS --connect-timeout 5 --max-time 30 -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" --data-binary "@$evidence/request.json" "$base/api/applications" >"$file"
  jq -e '.code == 0 and .data.id != null' "$file" >/dev/null
}
if [[ "$mode" == seed ]]; then
  request_id="$(cat /proc/sys/kernel/random/uuid)"
  server_id="$(jq -er '.data.server.id' "$identity/server.json")"
  jq -n --arg requestId "$request_id" --arg serverId "$server_id" --arg code "creation-${request_id:0:8}" \
    '{requestId:$requestId,serverId:$serverId,code:$code,name:"Application creation persistence fixture",environment:"TEST"}' >"$evidence/request.json"
  pids=()
  for index in 1 2 3 4; do request "$evidence/parallel-$index.json" & pids+=("$!"); done
  for pid in "${pids[@]}"; do wait "$pid"; done
  jq -s -e 'map(.data.id) | unique | length == 1' "$evidence"/parallel-*.json >/dev/null
  cp "$evidence/parallel-1.json" "$evidence/original.json"
fi
request "$evidence/replay.json"
jq -e --slurpfile original "$evidence/original.json" '.data.id == $original[0].data.id' "$evidence/replay.json" >/dev/null
curl -fsS --connect-timeout 5 --max-time 30 -H "Authorization: Bearer $token" "$base/api/applications" >"$evidence/applications.json"
jq -e --slurpfile original "$evidence/original.json" \
  '[.data[] | select(.code == $original[0].data.code)] | length == 1' "$evidence/applications.json" >/dev/null
jq '.name = "Must not replace original"' "$evidence/request.json" >"$evidence/conflicting-request.json"
status="$(curl -sS --connect-timeout 5 --max-time 30 -o "$evidence/conflict.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
  --data-binary "@$evidence/conflicting-request.json" "$base/api/applications")"
[[ "$status" == 409 ]] || exit 1
jq -e '.code == 40978' "$evidence/conflict.json" >/dev/null
printf '%s\n' 'Verified: exact request replay returns original application, one matching app, changed parameters rejected. No provider deployment requested.'
