#!/usr/bin/env bash
# Isolated acceptance fixture only. Does not trigger a provider deployment.
set -Eeuo pipefail
umask 077
mode="${1:?seed or verify}"
base_url="${2:?Loopback test origin}"
identity_dir="${3:?Existing private persistence fixture directory}"
state_dir="${4:?Private approval evidence directory}"
[[ "$base_url" =~ ^http://127\.0\.0\.1:[0-9]+$ && "$state_dir" == /* && "$state_dir" != / ]] || exit 2
[[ -f "$identity_dir/admin.json" && -f "$identity_dir/server.json" ]] || exit 2
if [[ "$mode" == seed ]]; then
  [[ ! -e "$state_dir" ]] || { printf '%s\n' 'Evidence exists; use verify.' >&2; exit 2; }
  mkdir -m 0700 "$state_dir"
elif [[ "$mode" == verify ]]; then
  [[ -f "$state_dir/approval-original.json" ]] || exit 2
else exit 2; fi
request() {
  local method="$1" path="$2" output="$3" input="${4:-}" signature="${5:-}"
  local args=(-sS --connect-timeout 5 --max-time 30 -X "$method" -H 'Content-Type: application/json')
  [[ -z "${token:-}" ]] || args+=(-H "Authorization: Bearer $token")
  [[ -z "$input" ]] || args+=(--data-binary "@$input")
  [[ -z "$signature" ]] || args+=(-H "X-DevPilot-Signature: $signature")
  local status
  status="$(curl "${args[@]}" -o "$output" -w '%{http_code}' "$base_url/api$path")"
  [[ "$status" == 200 ]] || { printf 'HTTP %s at %s %s; private response retained.\n' "$status" "$method" "$path" >&2; exit 1; }
  jq -e '.code == 0' "$output" >/dev/null
}
jq '{username,password}' "$identity_dir/admin.json" >"$state_dir/login.json"
request POST /auth/login "$state_dir/session.json" "$state_dir/login.json"
token="$(jq -er '.data.accessToken' "$state_dir/session.json")"
if [[ "$mode" == seed ]]; then
  jq -n --arg server "$(jq -er '.data.server.id' "$identity_dir/server.json")" \
    '{name:"Approval persistence fixture",code:"approval-persistence",environment:"PRODUCTION",serverId:$server}' >"$state_dir/application-request.json"
  request POST /applications "$state_dir/application.json" "$state_dir/application-request.json"
fi
app_id="$(jq -er '.data.id' "$state_dir/application.json")"
if [[ "$mode" == seed ]]; then
  jq -n '{repositoryProvider:"GITHUB",repositoryUrl:"https://github.com/example/approval-fixture",branchName:"main",
    deploymentProvider:"COOLIFY",deploymentWebhookUrl:"http://127.0.0.1:9/deploy",autoDeploy:false,
    productionApproval:true,rotateCallbackSecret:false}' >"$state_dir/config-request.json"
  request PUT "/cicd/configurations/$app_id" "$state_dir/config.json" "$state_dir/config-request.json"
  release_key="$(jq -er '.data.oneTimeCallbackSecret' "$state_dir/config.json")"
  build_key="$(printf '%s' devpilot-build-status-v1 | openssl dgst -sha256 -hmac "$release_key" | awk '{print $NF}')"
  jq -n '{externalRunId:"build:approval-fixture-1",commitSha:("a"*40),branchName:"main",status:"SUCCEEDED",
    testStatus:"PASSED",securityStatus:"PASSED",imageUri:("ghcr.io/example/approval-fixture@sha256:"+("b"*64))}' >"$state_dir/build-request.json"
  signature="sha256=$(openssl dgst -sha256 -hmac "$build_key" "$state_dir/build-request.json" | awk '{print $NF}')"
  request POST /cicd/webhooks/approval-persistence/builds "$state_dir/build.json" "$state_dir/build-request.json" "$signature"
  build_id="$(jq -er '.data.id' "$state_dir/build.json")"
  request GET "/cicd/applications/$app_id/builds/$build_id/approval-context" "$state_dir/context.json"
  jq --arg requestId "$(cat /proc/sys/kernel/random/uuid)" '.data | {requestId:$requestId,commitSha,imageUri,expectedFingerprint:.fingerprint,confirmed:true}' \
    "$state_dir/context.json" >"$state_dir/approval-request.json"
  request POST "/cicd/applications/$app_id/builds/$build_id/approval" "$state_dir/approval-original.json" "$state_dir/approval-request.json"
fi
build_id="$(jq -er '.data.id' "$state_dir/build.json")"
if [[ "$mode" == verify ]]; then
  # Signature validation must decrypt the restored callback credential, not just read metadata.
  release_key="$(jq -er '.data.oneTimeCallbackSecret' "$state_dir/config.json")"
  build_key="$(printf '%s' devpilot-build-status-v1 | openssl dgst -sha256 -hmac "$release_key" | awk '{print $NF}')"
  signature="sha256=$(openssl dgst -sha256 -hmac "$build_key" "$state_dir/build-request.json" | awk '{print $NF}')"
  request POST /cicd/webhooks/approval-persistence/builds "$state_dir/build-replay.json" "$state_dir/build-request.json" "$signature"
  jq -e --arg id "$build_id" '.data.id == $id and .data.status == "SUCCEEDED"' "$state_dir/build-replay.json" >/dev/null
fi
request POST "/cicd/applications/$app_id/builds/$build_id/approval" "$state_dir/approval-replay.json" "$state_dir/approval-request.json"
jq -e --slurpfile original "$state_dir/approval-original.json" \
  '.data | .id == $original[0].data.id and .approvedBy == $original[0].data.approvedBy and .approvedUsername == "stability-admin" and .approvedAt == $original[0].data.approvedAt and .expiresAt == $original[0].data.expiresAt and .imageUri == $original[0].data.imageUri' \
  "$state_dir/approval-replay.json" >/dev/null
approval_id="$(jq -er '.data.id' "$state_dir/approval-original.json")"
if [[ "$mode" == seed ]]; then
  request DELETE "/cicd/applications/$app_id/release-approvals/$approval_id" "$state_dir/approval-revoked.json"
fi
request GET "/cicd/applications/$app_id/release-approvals" "$state_dir/approvals-current.json"
jq -e --slurpfile revoked "$state_dir/approval-revoked.json" \
  '.data | length == 1 and .[0] == $revoked[0].data and .[0].revokedAt != null and .[0].consumedByRunId == null' "$state_dir/approvals-current.json" >/dev/null
request GET "/cicd/applications/$app_id/runs" "$state_dir/runs-current.json"
jq -e --slurpfile build "$state_dir/build.json" '.data | length == 1 and .[0].id == $build[0].data.id and .[0].imageUri == $build[0].data.imageUri and .[0].status == "SUCCEEDED"' "$state_dir/runs-current.json" >/dev/null
request GET "/cicd/applications/$app_id/deployments" "$state_dir/deployments-current.json"
jq -e '.data | length == 0' "$state_dir/deployments-current.json" >/dev/null
printf '%s\n' 'Verified: persisted build digest, original approval identity/time, idempotent replay, revoked history and zero deployments.'
