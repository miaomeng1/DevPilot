#!/usr/bin/env bash
# Run only against an isolated acceptance installation; seed creates an administrator.
set -Eeuo pipefail
umask 077
mode="${1:?seed, seed-business, seed-setup or verify}"
base_url="${2:?Isolated instance URL}"
state_dir="${3:?Private state directory}"
[[ "$mode" == seed || "$mode" == seed-business || "$mode" == seed-setup || "$mode" == verify ]] || exit 2
[[ "$state_dir" == /* && "$state_dir" != / ]] || exit 2
request() { curl -fsS --connect-timeout 5 --max-time 30 "$@"; }
if [[ "$mode" == seed ]]; then
  [[ ! -e "$state_dir" ]] || { printf '%s\n' 'State directory already exists; use verify.' >&2; exit 1; }
  request "$base_url/api/auth/setup/status" | jq -e '.data.setupRequired == true' >/dev/null
  mkdir -m 0700 "$state_dir"
  password="Dp9$(openssl rand -hex 24)"
  jq -n --arg password "$password" '{username:"stability-admin",password:$password,confirmPassword:$password,displayName:"Stability acceptance"}' >"$state_dir/admin.json"
  request -H 'Content-Type: application/json' --data-binary "@$state_dir/admin.json" "$base_url/api/auth/setup" >"$state_dir/session.json"
  token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
  request -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data '{"name":"stability-linux"}' "$base_url/api/servers" >"$state_dir/server.json"
  jq -e '.data.server.id != null and (.data.agentToken | length > 10)' "$state_dir/server.json" >/dev/null
  printf 'server:\n  url: %s\nagent:\n  token: %s\ncollect:\n  interval: 10\nnginx:\n  enabled: false\n' "$base_url" "$(jq -r '.data.agentToken' "$state_dir/server.json")" >"$state_dir/agent.yaml"
else
  [[ -f "$state_dir/admin.json" && -f "$state_dir/server.json" ]] || exit 1
fi
request "$base_url/api/auth/setup/status" | jq -e '.data.setupRequired == false' >/dev/null
jq '{username,password}' "$state_dir/admin.json" >"$state_dir/login.json"
request -H 'Content-Type: application/json' --data-binary "@$state_dir/login.json" "$base_url/api/auth/login" >"$state_dir/session.json"
token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
request -H "Authorization: Bearer $token" "$base_url/api/auth/me" | jq -e '.data.username == "stability-admin"' >/dev/null
request -H "Authorization: Bearer $token" "$base_url/api/servers" >"$state_dir/servers-current.json"
server_id="$(jq -r '.data.server.id' "$state_dir/server.json")"
jq -e --arg id "$server_id" '.data | any(.[]; (.id | tostring) == $id and .name == "stability-linux")' "$state_dir/servers-current.json" >/dev/null
if [[ "$mode" == seed-business ]]; then
  [[ ! -e "$state_dir/application.json" ]] || { printf '%s\n' 'Business fixture exists; use verify.' >&2; exit 1; }
  jq -n --arg id "$server_id" '{name:"Persistence fixture",code:"persistence-fixture",environment:"TEST",serverId:$id}' >"$state_dir/application-request.json"
  request -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$state_dir/application-request.json" "$base_url/api/applications" >"$state_dir/application.json"
  application_id="$(jq -er '.data.id' "$state_dir/application.json")"
  jq -n --arg secret "$(openssl rand -hex 24)" '{expectedRevision:0,variables:[{key:"PUBLIC_URL",value:"https://persistence.example",secret:false},{key:"API_KEY",value:$secret,secret:true}]}' >"$state_dir/environment-request.json"
  request -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$state_dir/environment-request.json" "$base_url/api/cicd/applications/$application_id/environment" >"$state_dir/environment.json"
fi
if [[ -f "$state_dir/application.json" ]]; then
  application_id="$(jq -er '.data.id' "$state_dir/application.json")"
  request -H "Authorization: Bearer $token" "$base_url/api/cicd/applications/$application_id/environment" >"$state_dir/environment.json"
  jq -e '.data.revision == 1 and (.data.variables | any(.[]; .key == "PUBLIC_URL" and .value == "https://persistence.example")) and (.data.variables | any(.[]; .key == "API_KEY" and .configured == true and .value == null))' "$state_dir/environment.json" >/dev/null
  printf '%s\n' 'Verified: application environment revision, encrypted public-value decryption and masked secret presence.'
fi
if [[ "$mode" == seed-setup ]]; then
  [[ ! -e "$state_dir/setup-request.json" ]] || { printf '%s\n' 'Setup fixture exists; use verify.' >&2; exit 1; }
  request -H "Authorization: Bearer $token" "$base_url/api/setup" >"$state_dir/setup-current.json"
  jq -e '.data.providerStatus == "NOT_CONFIGURED" and .data.serverId == null' "$state_dir/setup-current.json" >/dev/null
  # Synthetic key, never sent to an external platform. Do not verify-provider in this fixture.
  jq -n --arg revision "$(jq -er '.data.revision' "$state_dir/setup-current.json")" --arg publicUrl "$base_url" \
    --arg id "$server_id" --arg key "fixture-$(openssl rand -hex 24)" \
    '{revision:$revision,publicUrl:$publicUrl,serverId:$id,providerUrl:"http://127.0.0.1:9",providerApiToken:$key}' >"$state_dir/setup-request.json"
  request -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    --data-binary "@$state_dir/setup-request.json" "$base_url/api/setup" >"$state_dir/setup-saved.json"
  jq -e '.data.providerStatus == "SAVED_UNVERIFIED"' "$state_dir/setup-saved.json" >/dev/null
fi
if [[ -f "$state_dir/setup-saved.json" ]]; then
  request -H "Authorization: Bearer $token" "$base_url/api/setup" >"$state_dir/setup-current.json"
  jq -e --arg id "$server_id" --arg publicUrl "$base_url" --slurpfile saved "$state_dir/setup-saved.json" \
    '.data | .publicUrl == $publicUrl and (.serverId | tostring) == $id and .providerTokenConfigured == true and (.providerStatus == "SAVED_UNVERIFIED" or .providerStatus == "VERIFIED" or .providerStatus == "FAILED") and .revision == $saved[0].data.revision and (has("providerApiToken") | not)' "$state_dir/setup-current.json" >/dev/null
  printf '%s\n' 'Verified: saved setup revision, server selection and masked synthetic platform credential (not a real platform connection).'
fi
if [[ "${DEVPILOT_TEST_REQUIRE_AGENT:-false}" == true ]]; then
  jq -e --arg id "$server_id" '.data | any(.[]; (.id | tostring) == $id and .status == "ONLINE" and .lastHeartbeat != null)' "$state_dir/servers-current.json" >/dev/null
  request -H "Authorization: Bearer $token" "$base_url/api/docker/containers?serverId=$server_id" >"$state_dir/containers-current.json"
  jq -e '.data | length >= 5' "$state_dir/containers-current.json" >/dev/null
  printf '%s\n' 'Verified: Agent online and at least five actual container snapshots.'
fi
printf '%s\n' 'Verified: initialized state, administrator login and saved server identity.'
