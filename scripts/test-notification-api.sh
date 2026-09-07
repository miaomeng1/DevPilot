#!/usr/bin/env bash
# Isolated Linux acceptance fixture; no external deployment or repository writes.
set -Eeuo pipefail
umask 077
mode="${1:?seed, verify, retry, enqueue-recovery or retry-recovery}"
base_url="${2:?Loopback API origin}"
identity_dir="${3:?Private persistence identity directory}"
state_dir="${4:?Private notification evidence directory}"
[[ "$base_url" =~ ^http://127\.0\.0\.1:[0-9]+$ && "$state_dir" == /* && "$state_dir" != / ]] || exit 2
[[ -f "$identity_dir/admin.json" && -f "$identity_dir/server.json" ]] || exit 2
if [[ "$mode" == seed ]]; then
  [[ ! -e "$state_dir" ]] || { printf 'Evidence already exists; use verify.\n' >&2; exit 2; }
  mkdir -m 0700 "$state_dir"
elif [[ "$mode" == verify || "$mode" == retry || "$mode" == enqueue-recovery || "$mode" == retry-recovery ]]; then
  [[ -f "$state_dir/subscription.json" ]] || exit 2
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
  jq -n '{name:"Stability notification fixture",endpointUrl:"http://127.0.0.1:18889/notify",eventTypes:["BUILD_FAILED"]}' >"$state_dir/subscription-request.json"
  request POST /automation/webhooks "$state_dir/subscription.json" "$state_dir/subscription-request.json"
  jq -er '.data.oneTimeSecret' "$state_dir/subscription.json" >"$state_dir/secret"
  jq -n --arg server "$(jq -er '.data.server.id' "$identity_dir/server.json")" \
    '{name:"Notification fixture",code:"notification-fixture",environment:"PRODUCTION",serverId:$server}' >"$state_dir/application-request.json"
  request POST /applications "$state_dir/application.json" "$state_dir/application-request.json"
  app_id="$(jq -er '.data.id' "$state_dir/application.json")"
  jq -n '{repositoryProvider:"GITHUB",repositoryUrl:"https://github.com/example/notification-fixture",branchName:"main",deploymentProvider:"COOLIFY",deploymentWebhookUrl:"http://127.0.0.1:9/deploy",autoDeploy:false,productionApproval:true,rotateCallbackSecret:false}' >"$state_dir/config-request.json"
  request PUT "/cicd/configurations/$app_id" "$state_dir/config.json" "$state_dir/config-request.json"
  jq -n '{externalRunId:"build:notification-fixture-1",commitSha:("c"*40),branchName:"main",status:"FAILED",testStatus:"FAILED",securityStatus:"SKIPPED"}' >"$state_dir/build-request.json"
fi
release_key="$(jq -er '.data.oneTimeCallbackSecret' "$state_dir/config.json")"
build_key="$(printf '%s' devpilot-build-status-v1 | openssl dgst -sha256 -hmac "$release_key" | awk '{print $NF}')"
signature="sha256=$(openssl dgst -sha256 -hmac "$build_key" "$state_dir/build-request.json" | awk '{print $NF}')"
request POST /cicd/webhooks/notification-fixture/builds "$state_dir/build-current.json" "$state_dir/build-request.json" "$signature"
request GET /automation/webhooks/deliveries "$state_dir/deliveries-current.json"
jq --arg subject "build/$(jq -er '.data.id' "$state_dir/build-current.json")" \
  '[.data[] | select(.subscriptionName == "Stability notification fixture" and .subject == $subject)]' "$state_dir/deliveries-current.json" >"$state_dir/delivery-current.json"
jq -e 'length == 1 and .[0].eventType == "BUILD_FAILED"' "$state_dir/delivery-current.json" >/dev/null
if [[ "$mode" == seed ]]; then
  cp "$state_dir/delivery-current.json" "$state_dir/delivery-original.json"
else
  jq -e --slurpfile original "$state_dir/delivery-original.json" '.[0].id == $original[0][0].id and .[0].eventId == $original[0][0].eventId' "$state_dir/delivery-current.json" >/dev/null
fi
if [[ "$mode" == retry ]]; then
  jq -e '.[0].status == "FAILED"' "$state_dir/delivery-current.json" >/dev/null
  delivery_id="$(jq -er '.[0].id' "$state_dir/delivery-current.json")"
  request POST "/automation/webhooks/deliveries/$delivery_id/retry" "$state_dir/retry.json"
fi
if [[ "$mode" == verify ]] && jq -e '.[0].status == "SUCCEEDED"' "$state_dir/delivery-current.json" >/dev/null; then
  delivery_id="$(jq -er '.[0].id' "$state_dir/delivery-current.json")"
  status="$(curl -sS --connect-timeout 5 --max-time 30 -X POST -H "Authorization: Bearer $token" \
    -o "$state_dir/success-retry-rejected.json" -w '%{http_code}' "$base_url/api/automation/webhooks/deliveries/$delivery_id/retry")"
  [[ "$status" == 409 ]] || { printf 'Successful delivery retry was not rejected.\n' >&2; exit 1; }
  jq -e '.code == 40973' "$state_dir/success-retry-rejected.json" >/dev/null
fi
if [[ "$mode" == enqueue-recovery ]]; then
  [[ ! -e "$state_dir/recovery-build.json" ]] || { printf 'Recovery fixture already exists.\n' >&2; exit 2; }
  jq '.externalRunId = "build:notification-recovery-1"' "$state_dir/build-request.json" >"$state_dir/recovery-request.json"
  signature="sha256=$(openssl dgst -sha256 -hmac "$build_key" "$state_dir/recovery-request.json" | awk '{print $NF}')"
  request POST /cicd/webhooks/notification-fixture/builds "$state_dir/recovery-build.json" "$state_dir/recovery-request.json" "$signature"
fi
if [[ "$mode" == enqueue-recovery || "$mode" == retry-recovery ]]; then
  request GET /automation/webhooks/deliveries "$state_dir/recovery-deliveries.json"
  jq --arg subject "build/$(jq -er '.data.id' "$state_dir/recovery-build.json")" \
    '[.data[] | select(.subscriptionName == "Stability notification fixture" and .subject == $subject)]' "$state_dir/recovery-deliveries.json" >"$state_dir/recovery-current.json"
  jq -e 'length == 1' "$state_dir/recovery-current.json" >/dev/null
  if [[ "$mode" == enqueue-recovery ]]; then
    cp "$state_dir/recovery-current.json" "$state_dir/recovery-original.json"
  else
    jq -e --slurpfile original "$state_dir/recovery-original.json" \
      '.[0].id == $original[0][0].id and .[0].eventId == $original[0][0].eventId and .[0].status == "FAILED"' "$state_dir/recovery-current.json" >/dev/null
    delivery_id="$(jq -er '.[0].id' "$state_dir/recovery-current.json")"
    request POST "/automation/webhooks/deliveries/$delivery_id/retry" "$state_dir/recovery-retry.json"
  fi
fi
app_id="$(jq -er '.data.id' "$state_dir/application.json")"
request GET "/cicd/applications/$app_id/deployments" "$state_dir/deployments-current.json"
jq -e '.data | length == 0' "$state_dir/deployments-current.json" >/dev/null
printf 'Verified: signed failed-build callback, one stable notification event, zero deployments.\n'
