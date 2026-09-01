#!/usr/bin/env bash
set -euo pipefail

required=(
  DEVPILOT_CICD_CALLBACK_URL
  DEVPILOT_CICD_CALLBACK_SECRET
  CI_EXTERNAL_RUN_ID
  CI_COMMIT_SHA
  CI_BRANCH
  CI_PIPELINE_STATUS
  CI_TEST_STATUS
  CI_SECURITY_STATUS
)

for name in "${required[@]}"; do
  test -n "${!name:-}" || { echo "$name is required" >&2; exit 1; }
done

case "$CI_PIPELINE_STATUS" in RUNNING|SUCCEEDED|FAILED|CANCELLED) ;; *) exit 64 ;; esac
case "$CI_TEST_STATUS" in PENDING|PASSED|FAILED|SKIPPED) ;; *) exit 64 ;; esac
case "$CI_SECURITY_STATUS" in PENDING|PASSED|FAILED|SKIPPED) ;; *) exit 64 ;; esac

payload="$(jq -cn \
  --arg externalRunId "$CI_EXTERNAL_RUN_ID" \
  --arg status "$CI_PIPELINE_STATUS" \
  --arg testStatus "$CI_TEST_STATUS" \
  --arg securityStatus "$CI_SECURITY_STATUS" \
  --arg commitSha "$CI_COMMIT_SHA" \
  --arg branchName "$CI_BRANCH" \
  --arg imageUri "${CI_IMAGE_URI:-}" \
  --arg imageDigest "${CI_IMAGE_DIGEST:-}" \
  --arg runUrl "${CI_RUN_URL:-}" \
  --arg summary "${CI_SUMMARY:-}" \
  '{externalRunId:$externalRunId,status:$status,testStatus:$testStatus,securityStatus:$securityStatus,
    commitSha:$commitSha,branchName:$branchName,runUrl:$runUrl,summary:$summary}
   + (if $imageUri == "" then {} else {imageUri:$imageUri} end)
   + (if $imageDigest == "" then {} else {imageDigest:$imageDigest} end)')"

signature="$(printf '%s' "$payload" \
  | openssl dgst -sha256 -hmac "$DEVPILOT_CICD_CALLBACK_SECRET" -hex \
  | awk '{print $NF}')"

curl --fail --silent --show-error --retry 3 --retry-all-errors \
  -H 'Content-Type: application/json' \
  -H "X-DevPilot-Signature: sha256=$signature" \
  --data-binary "$payload" \
  "$DEVPILOT_CICD_CALLBACK_URL"

