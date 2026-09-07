#!/usr/bin/env bash
# Isolated acceptance only. Synthetic credential; no pending build or external lookup.
set -Eeuo pipefail
umask 077
mode="${1:?seed or verify}"
base_url="${2:?Loopback URL}"
identity_dir="${3:?Private identity directory}"
state_dir="${4:?Private observer evidence directory}"
[[ "$base_url" =~ ^http://127\.0\.0\.1:[0-9]+$ && "$state_dir" == /* && "$state_dir" != / ]] || exit 2
[[ -f "$identity_dir/admin.json" && -f "$identity_dir/server.json" ]] || exit 2
if [[ "$mode" == seed ]]; then
  [[ ! -e "$state_dir" ]] || { printf '%s\n' 'Evidence exists; use verify.' >&2; exit 2; }
  mkdir -m 0700 "$state_dir"
elif [[ "$mode" == verify ]]; then
  [[ -f "$state_dir/observer-original.json" ]] || exit 2
else exit 2; fi
request() {
  local method="$1" path="$2" output="$3" input="${4:-}"
  local args=(-fsS --connect-timeout 5 --max-time 30 -X "$method" -H 'Content-Type: application/json')
  [[ -z "${token:-}" ]] || args+=(-H "Authorization: Bearer $token")
  [[ -z "$input" ]] || args+=(--data-binary "@$input")
  curl "${args[@]}" "$base_url/api$path" -o "$output"
  jq -e '.code == 0' "$output" >/dev/null
}
jq '{username,password}' "$identity_dir/admin.json" >"$state_dir/login.json"
request POST /auth/login "$state_dir/session.json" "$state_dir/login.json"
token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
if [[ "$mode" == seed ]]; then
  jq -n --arg server "$(jq -er '.data.server.id' "$identity_dir/server.json")" \
    '{name:"Observer persistence fixture",code:"observer-persistence",environment:"TEST",serverId:$server}' >"$state_dir/application-request.json"
  request POST /applications "$state_dir/application.json" "$state_dir/application-request.json"
fi
app_id="$(jq -er '.data.id' "$state_dir/application.json")"
path="/cicd/applications/$app_id/github-observer"
if [[ "$mode" == seed ]]; then
  jq -n '{repositoryProvider:"GITHUB",repositoryUrl:"https://github.com/example/observer-fixture",branchName:"main",
    deploymentProvider:"COOLIFY",deploymentWebhookUrl:"http://127.0.0.1:9/deploy",autoDeploy:false,
    productionApproval:true,rotateCallbackSecret:false}' >"$state_dir/config-request.json"
  request PUT "/cicd/configurations/$app_id" "$state_dir/config.json" "$state_dir/config-request.json"
  request GET "$path" "$state_dir/observer-initial.json"
  jq -e '.data.state == "DISABLED" and .data.revision == "initial"' "$state_dir/observer-initial.json" >/dev/null
  jq -n --arg expiry "$(date -u -d '+1 day' '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg secret "fixture_$(openssl rand -hex 24)" \
    '{revision:"initial",consent:true,repositoryToken:$secret,expiresAt:$expiry}' >"$state_dir/observer-request.json"
  request PUT "$path" "$state_dir/observer-original.json" "$state_dir/observer-request.json"
fi
request GET "$path" "$state_dir/observer-current.json"
jq -e --slurpfile original "$state_dir/observer-original.json" \
  '(.data | del(.nextCheckAt)) == ($original[0].data | del(.nextCheckAt)) and .data.state == "SAVED_UNVERIFIED" and .data.credentialConfigured == true and (.data | has("repositoryToken") | not)' \
  "$state_dir/observer-current.json" >/dev/null
request GET "/cicd/applications/$app_id/runs" "$state_dir/runs-current.json"
request GET "/cicd/applications/$app_id/deployments" "$state_dir/deployments-current.json"
jq -e '.data | length == 0' "$state_dir/runs-current.json" >/dev/null
jq -e '.data | length == 0' "$state_dir/deployments-current.json" >/dev/null
printf '%s\n' 'Verified: observer revision/expiry retained, synthetic credential presence masked, zero builds/deployments. Does not prove GitHub authentication or credential decryption.'
