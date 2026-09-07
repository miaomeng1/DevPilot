import type { RepositoryProvider } from '@/api/cicd'

export type RuntimePreset = 'NODE' | 'JAVA' | 'GO' | 'DOCKER'

export interface WorkflowOptions {
  provider: RepositoryProvider
  runtime: RuntimePreset
  branch: string
  imageRepository: string
  callbackUrl: string
  previewEnabled: boolean
  previewCallbackUrl: string
  previewUrlTemplate: string
  previewTtlHours: number
  applicationCode: string
}

export interface GeneratedWorkflow {
  fileName: string
  content: string
  secrets: string[]
  notes: string[]
}

const yaml = (value: string) => `'${value.replace(/'/g, "''")}'`
const safeKey = (value: string) => value.replace(/[^a-zA-Z0-9_-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || 'application'

function githubQuality(runtime: RuntimePreset) {
  if (runtime === 'NODE') return `      - uses: actions/setup-node@v5
        with:
          node-version: '22'
          cache: npm
      - run: npm ci
      - run: npm test`
  if (runtime === 'JAVA') return `      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B test`
  if (runtime === 'GO') return `      - uses: actions/setup-go@v6
        with:
          go-version: stable
      - run: go test -race ./...`
  return `      - run: docker build --target test .`
}

function github(options: WorkflowOptions): GeneratedWorkflow {
  const branch = yaml(options.branch)
  const image = yaml(options.imageRepository.toLowerCase())
  const callback = yaml(options.callbackUrl)
  const previewCallback = yaml(options.previewCallbackUrl)
  const applicationKey = safeKey(options.applicationCode)
  const verifyBuildJobs = `          timeout 60s gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/actions/runs/$BUILD_RUN_ID/attempts/$BUILD_RUN_ATTEMPT/jobs?per_page=100" > build-jobs.json
          jq -e --arg run "$BUILD_RUN_ID" --arg commit "$(jq -r .head_sha build-run.json)" '
            [.[].jobs[]] as $jobs |
            all(["quality", "security", "image"][]; . as $name |
              [$jobs[] | select(.name == $name)] as $matches |
              ($matches | length) == 1 and
              all($matches[]; (.run_id | tostring) == $run and .head_sha == $commit and
                .status == "completed" and .conclusion == "success"))' build-jobs.json`
  const buildReporter = (finished: boolean) => `    runs-on: ubuntu-24.04
    environment: build-status-${applicationKey}
    permissions: {}
    env:
      DEVPILOT_BUILD_CALLBACK_URL: \${{ secrets.DEVPILOT_BUILD_CALLBACK_URL }}
      DEVPILOT_BUILD_CALLBACK_SECRET: \${{ secrets.DEVPILOT_BUILD_CALLBACK_SECRET }}
      TEST_RESULT: ${finished ? '${{ needs.quality.result }}' : 'pending'}
      SECURITY_RESULT: ${finished ? '${{ needs.security.result }}' : 'pending'}
      BUILD_RESULT: ${finished ? '${{ needs.image.result }}' : 'pending'}
      DIGEST: ${finished ? '${{ needs.image.outputs.digest }}' : "''"}
    steps:
      - name: Report build state without deployment authority
        shell: bash
        run: |
          set -euo pipefail
          test -n "$DEVPILOT_BUILD_CALLBACK_URL" && test -n "$DEVPILOT_BUILD_CALLBACK_SECRET"
          STATUS=${finished ? 'FAILED' : 'RUNNING'}
          TEST_STATUS=PENDING; SECURITY_STATUS=PENDING; IMAGE_URI=""
          if [[ "$TEST_RESULT" == success ]]; then TEST_STATUS=PASSED; elif [[ "$TEST_RESULT" == failure ]]; then TEST_STATUS=FAILED; elif [[ "$TEST_RESULT" != pending ]]; then TEST_STATUS=SKIPPED; fi
          if [[ "$SECURITY_RESULT" == success ]]; then SECURITY_STATUS=PASSED; elif [[ "$SECURITY_RESULT" == failure ]]; then SECURITY_STATUS=FAILED; elif [[ "$SECURITY_RESULT" != pending ]]; then SECURITY_STATUS=SKIPPED; fi
          if [[ "$TEST_RESULT $SECURITY_RESULT $BUILD_RESULT" == *cancelled* && "$TEST_RESULT $SECURITY_RESULT $BUILD_RESULT" != *failure* ]]; then STATUS=CANCELLED; fi
          if [[ "$BUILD_RESULT" == success && "$TEST_STATUS" == PASSED && "$SECURITY_STATUS" == PASSED ]]; then
            [[ "$DIGEST" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Missing or invalid build digest' >&2; exit 1; }
            STATUS=SUCCEEDED; IMAGE_URI="$IMAGE_REPOSITORY@$DIGEST"
          fi
          BODY="$(jq -nc --arg externalRunId "build:github-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT" \\
            --arg commitSha "$GITHUB_SHA" --arg branchName "$GITHUB_REF_NAME" --arg status "$STATUS" \\
            --arg testStatus "$TEST_STATUS" --arg securityStatus "$SECURITY_STATUS" --arg imageUri "$IMAGE_URI" \\
            --arg runUrl "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" \\
            --arg summary "Build result only; manual production confirmation required" '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_BUILD_CALLBACK_SECRET" | awk '{print $NF}')"
          RESPONSE="$(curl --fail --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$DEVPILOT_BUILD_CALLBACK_URL")"
          echo "$RESPONSE" | jq -e --arg run "build:github-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT" \\
            --arg commit "$GITHUB_SHA" --arg status "$STATUS" --arg image "$IMAGE_URI" \\
            '.code == 0 and .data.externalRunId == $run and .data.commitSha == $commit and
             (if $status == "RUNNING" then (.data.status | IN("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
              else .data.status == $status end) and
             (if $status == "SUCCEEDED" then .data.imageUri == $image and
               .data.testStatus == "PASSED" and .data.securityStatus == "PASSED" else true end)' >/dev/null
`
  const previewJobs = options.previewEnabled ? `
  preview:
    needs: [quality, security, image]
    if: github.event_name == 'pull_request' && github.event.action != 'closed' && github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-24.04
    environment: preview
    env:
      DEVPILOT_PREVIEW_CALLBACK_URL: \${{ secrets.DEVPILOT_PREVIEW_CALLBACK_URL }}
      DEVPILOT_PREVIEW_CALLBACK_SECRET: \${{ secrets.DEVPILOT_PREVIEW_CALLBACK_SECRET }}
      PR_NUMBER: \${{ github.event.pull_request.number }}
      PR_TITLE: \${{ github.event.pull_request.title }}
      PR_HEAD: \${{ github.head_ref }}
      PR_BASE: \${{ github.base_ref }}
      SOURCE_SHA: \${{ github.event.pull_request.head.sha }}
    steps:
      - name: Register isolated preview
        env:
          CALLBACK_FALLBACK: ${previewCallback}
        run: |
          CALLBACK_URL="\${DEVPILOT_PREVIEW_CALLBACK_URL:-$CALLBACK_FALLBACK}"
          IMAGE_URI="\${IMAGE_REPOSITORY}:sha-\${SOURCE_SHA}"
          BODY="$(jq -nc --arg action DEPLOY --argjson pullRequestId "$PR_NUMBER" \\
            --arg baseBranch "$PR_BASE" --arg externalRunId "github-\${GITHUB_RUN_ID}-\${GITHUB_RUN_ATTEMPT}" \\
            --arg title "$PR_TITLE" --arg branchName "$PR_HEAD" \\
            --arg commitSha "$SOURCE_SHA" --arg status SUCCEEDED --arg testStatus PASSED \\
            --arg securityStatus PASSED --arg imageUri "$IMAGE_URI" \\
            --arg runUrl "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 \\
            -hmac "$DEVPILOT_PREVIEW_CALLBACK_SECRET" | awk '{print $NF}')"
          RESPONSE="$(curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL")"
          echo "$RESPONSE" | jq -e '.data.status == "DEPLOYING" or .data.status == "READY"'

  cleanup_preview:
    if: github.event_name == 'pull_request' && github.event.action == 'closed' && github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-24.04
    environment: preview
    env:
      DEVPILOT_PREVIEW_CALLBACK_URL: \${{ secrets.DEVPILOT_PREVIEW_CALLBACK_URL }}
      DEVPILOT_PREVIEW_CALLBACK_SECRET: \${{ secrets.DEVPILOT_PREVIEW_CALLBACK_SECRET }}
      PR_NUMBER: \${{ github.event.pull_request.number }}
      PR_BASE: \${{ github.base_ref }}
    steps:
      - name: Remove closed preview
        env:
          CALLBACK_FALLBACK: ${previewCallback}
        run: |
          CALLBACK_URL="\${DEVPILOT_PREVIEW_CALLBACK_URL:-$CALLBACK_FALLBACK}"
          BODY="$(jq -nc --arg action CLOSE --argjson pullRequestId "$PR_NUMBER" \\
            --arg baseBranch "$PR_BASE" '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 \\
            -hmac "$DEVPILOT_PREVIEW_CALLBACK_SECRET" | awk '{print $NF}')"
          RESPONSE="$(curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL")"
          echo "$RESPONSE" | jq -e '.data.status == "DELETED"'
` : ''
  const content = `name: DevPilot delivery

on:
  pull_request:
    branches: [${branch}]
    types: [opened, synchronize, reopened, closed]
  push:
    branches: [${branch}]
  workflow_dispatch:
    inputs:
      operation:
        description: 'release 发布；recover_build 仅恢复构建记录，不部署'
        required: true
        type: choice
        default: release
        options: [release, recover_build]
      build_run_id:
        description: '已通过的 push 构建 Run ID（发布原始 digest，不重新构建）'
        required: true
        type: string
      manual_approval_id:
        description: 'DevPilot 发布中心确认此构建后生成的确认 ID（24 小时内有效）'
        required: false
        type: string

permissions:
  contents: read
  packages: write
  actions: read

concurrency:
  group: devpilot-${applicationKey}-\${{ github.event_name }}-\${{ github.ref }}
  cancel-in-progress: \${{ github.event_name != 'workflow_dispatch' }}

env:
  IMAGE_REPOSITORY: ${image}
  SOURCE_SHA: \${{ github.event.pull_request.head.sha || github.sha }}

jobs:
  build_started:
    if: github.event_name == 'push'
${buildReporter(false)}
  build_finished:
    needs: [quality, security, image]
    if: always() && github.event_name == 'push'
${buildReporter(true)}
  quality:
    if: github.event.action != 'closed' && github.event_name != 'workflow_dispatch'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
        with:
          ref: \${{ env.SOURCE_SHA }}
${githubQuality(options.runtime)}

  security:
    if: github.event.action != 'closed' && github.event_name != 'workflow_dispatch'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
        with:
          ref: \${{ env.SOURCE_SHA }}
      - name: Trivy source, dependency, secret and IaC gate
        run: |
          docker run --rm -v "$PWD:/workspace" -w /workspace aquasec/trivy:0.65.0 fs \\
            --scanners vuln,secret,misconfig --include-dev-deps --severity HIGH,CRITICAL \\
            --exit-code 1 .

  image:
    needs: [quality, security]
    outputs:
      digest: \${{ steps.build.outputs.digest }}
    if: github.event_name != 'workflow_dispatch' && github.event.action != 'closed' && (github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository)
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
        with:
          ref: \${{ env.SOURCE_SHA }}
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: \${{ github.actor }}
          password: \${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        id: build
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: \${{ env.IMAGE_REPOSITORY }}:sha-\${{ env.SOURCE_SHA }}
          labels: org.opencontainers.image.revision=\${{ env.SOURCE_SHA }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
      - name: Record immutable release evidence
        if: github.event_name == 'push'
        env:
          IMAGE_DIGEST: \${{ steps.build.outputs.digest }}
        run: |
          [[ "$IMAGE_DIGEST" =~ ^sha256:[a-f0-9]{64}$ ]]
          jq -n --arg digest "$IMAGE_DIGEST" --arg image "$IMAGE_REPOSITORY" \\
            --arg commit "$GITHUB_SHA" --arg runId "$GITHUB_RUN_ID" \\
            --arg runAttempt "$GITHUB_RUN_ATTEMPT" --arg repository "$GITHUB_REPOSITORY" \\
            --arg branch "$GITHUB_REF_NAME" \\
            '{digest:$digest,image:$image,commit:$commit,runId:$runId,runAttempt:$runAttempt,repository:$repository,branch:$branch,event:"push"}' > release.json
      - uses: actions/upload-artifact@v4
        if: github.event_name == 'push'
        with:
          name: devpilot-release-${applicationKey}-\${{ github.run_attempt }}
          path: release.json
          retention-days: 90
          if-no-files-found: error

  recover_build:
    if: github.event_name == 'workflow_dispatch' && inputs.operation == 'recover_build' && github.ref_name == ${branch}
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    environment: build-status-${applicationKey}
    permissions:
      actions: read
    env:
      DEVPILOT_BUILD_CALLBACK_URL: \${{ secrets.DEVPILOT_BUILD_CALLBACK_URL }}
      DEVPILOT_BUILD_CALLBACK_SECRET: \${{ secrets.DEVPILOT_BUILD_CALLBACK_SECRET }}
      BUILD_RUN_ID: \${{ inputs.build_run_id }}
      GH_TOKEN: \${{ github.token }}
      EXPECTED_BRANCH: ${branch}
    steps:
      - name: Verify original build and successful gate jobs
        shell: bash
        run: |
          set -euo pipefail
          [[ "$BUILD_RUN_ID" =~ ^[1-9][0-9]*$ ]]
          timeout 30s gh api "repos/$GITHUB_REPOSITORY/actions/runs/$BUILD_RUN_ID" > build-run.json
          timeout 30s gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" > recovery-run.json
          jq -e --arg run "$BUILD_RUN_ID" --arg branch "$EXPECTED_BRANCH" --arg repo "$GITHUB_REPOSITORY" \\
            --slurpfile recovery recovery-run.json \\
            '.event == "push" and .status == "completed" and (.id | tostring) == $run and
             .head_branch == $branch and .repository.full_name == $repo and .head_repository.full_name == $repo and
             .workflow_id == $recovery[0].workflow_id' build-run.json
          BUILD_RUN_ATTEMPT="$(jq -r .run_attempt build-run.json)"
          [[ "$BUILD_RUN_ATTEMPT" =~ ^[1-9][0-9]*$ ]]
${verifyBuildJobs}
          echo "BUILD_RUN_ATTEMPT=$BUILD_RUN_ATTEMPT" >> "$GITHUB_ENV"
      - uses: actions/download-artifact@v4
        with:
          name: devpilot-release-${applicationKey}-\${{ env.BUILD_RUN_ATTEMPT }}
          path: recovered-release
          run-id: \${{ inputs.build_run_id }}
          github-token: \${{ github.token }}
      - name: Validate and resend build evidence only
        shell: bash
        run: |
          set -euo pipefail
          jq -e --arg image "$IMAGE_REPOSITORY" --arg run "$BUILD_RUN_ID" \\
            --arg commit "$(jq -r .head_sha build-run.json)" --arg attempt "$BUILD_RUN_ATTEMPT" \\
            --arg repo "$GITHUB_REPOSITORY" --arg branch "$EXPECTED_BRANCH" \\
            '.image == $image and .runId == $run and .commit == $commit and
             .runAttempt == $attempt and .repository == $repo and .branch == $branch and .event == "push" and
             (.digest | test("^sha256:[a-f0-9]{64}$"))' recovered-release/release.json
          test -n "$DEVPILOT_BUILD_CALLBACK_URL" && test -n "$DEVPILOT_BUILD_CALLBACK_SECRET"
          BODY="$(jq -nc --arg externalRunId "build:github-$BUILD_RUN_ID-$BUILD_RUN_ATTEMPT" \\
            --arg commitSha "$(jq -r .commit recovered-release/release.json)" --arg branchName "$EXPECTED_BRANCH" \\
            --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED \\
            --arg imageUri "$IMAGE_REPOSITORY@$(jq -r .digest recovered-release/release.json)" \\
            --arg runUrl "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$BUILD_RUN_ID" \\
            --arg summary "Recovered original build evidence; manual confirmation still required" '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_BUILD_CALLBACK_SECRET" | awk '{print $NF}')"
          RESPONSE="$(curl --fail --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$DEVPILOT_BUILD_CALLBACK_URL")"
          echo "$RESPONSE" | jq -e --arg run "build:github-$BUILD_RUN_ID-$BUILD_RUN_ATTEMPT" \\
            --arg commit "$(jq -r .commit recovered-release/release.json)" \\
            --arg image "$IMAGE_REPOSITORY@$(jq -r .digest recovered-release/release.json)" \\
            '.code == 0 and .data.externalRunId == $run and .data.status == "SUCCEEDED" and .data.deployStatus == "AWAITING_APPROVAL" and
             .data.commitSha == $commit and .data.imageUri == $image'

  production:
    if: github.event_name == 'workflow_dispatch' && inputs.operation == 'release' && github.ref_name == ${branch}
    runs-on: ubuntu-24.04
    environment: production
    permissions:
      contents: read
      actions: read
    concurrency:
      group: production-${applicationKey}
      cancel-in-progress: false
    env:
      DEVPILOT_CICD_CALLBACK_URL: \${{ secrets.DEVPILOT_CICD_CALLBACK_URL }}
      DEVPILOT_CICD_CALLBACK_SECRET: \${{ secrets.DEVPILOT_CICD_CALLBACK_SECRET }}
      BUILD_RUN_ID: \${{ inputs.build_run_id }}
      MANUAL_APPROVAL_ID: \${{ inputs.manual_approval_id }}
      GH_TOKEN: \${{ github.token }}
    steps:
      - name: Verify successful build provenance
        env:
          EXPECTED_BRANCH: ${branch}
        run: |
          [[ "$BUILD_RUN_ID" =~ ^[0-9]+$ ]]
          [[ "$MANUAL_APPROVAL_ID" =~ ^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$ ]]
          gh api "repos/$GITHUB_REPOSITORY/actions/runs/$BUILD_RUN_ID" > build-run.json
          gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" > release-run.json
          jq -e --arg run "$GITHUB_RUN_ID" --arg attempt "$GITHUB_RUN_ATTEMPT" \\
            --arg branch "$EXPECTED_BRANCH" --arg repo "$GITHUB_REPOSITORY" \\
            '.event == "workflow_dispatch" and (.id | tostring) == $run and
             (.run_attempt | tostring) == $attempt and .head_branch == $branch and
             .repository.full_name == $repo' release-run.json
          jq -e --arg branch "$EXPECTED_BRANCH" --arg repo "$GITHUB_REPOSITORY" \\
            --slurpfile release release-run.json \\
            '.event == "push" and .status == "completed" and
             .head_branch == $branch and .repository.full_name == $repo and
             .workflow_id == $release[0].workflow_id' build-run.json
          BUILD_RUN_ATTEMPT="$(jq -r .run_attempt build-run.json)"
          [[ "$BUILD_RUN_ATTEMPT" =~ ^[1-9][0-9]*$ ]]
${verifyBuildJobs}
          echo "BUILD_RUN_ATTEMPT=$BUILD_RUN_ATTEMPT" >> "$GITHUB_ENV"
      - uses: actions/download-artifact@v4
        with:
          name: devpilot-release-${applicationKey}-\${{ env.BUILD_RUN_ATTEMPT }}
          path: verified-release
          run-id: \${{ inputs.build_run_id }}
          github-token: \${{ github.token }}
      - name: Validate immutable image evidence
        run: |
          jq -e --arg image "$IMAGE_REPOSITORY" --arg run "$BUILD_RUN_ID" \\
            --arg commit "$(jq -r .head_sha build-run.json)" \\
            --arg attempt "$BUILD_RUN_ATTEMPT" --arg repo "$GITHUB_REPOSITORY" \\
            --arg branch "$(jq -r .head_branch build-run.json)" \\
            '.image == $image and .runId == $run and .commit == $commit and
             .runAttempt == $attempt and .repository == $repo and .branch == $branch and .event == "push" and
             (.digest | test("^sha256:[a-f0-9]{64}$"))' verified-release/release.json
          echo "IMAGE_URI=$IMAGE_REPOSITORY@$(jq -r .digest verified-release/release.json)" >> "$GITHUB_ENV"
          echo "IMAGE_DIGEST=$(jq -r .digest verified-release/release.json)" >> "$GITHUB_ENV"
          echo "RELEASE_COMMIT=$(jq -r .commit verified-release/release.json)" >> "$GITHUB_ENV"
      - name: Send signed deployment evidence
        env:
          CALLBACK_FALLBACK: ${callback}
        run: |
          CALLBACK_URL="\${DEVPILOT_CICD_CALLBACK_URL:-$CALLBACK_FALLBACK}"
          test -n "$DEVPILOT_CICD_CALLBACK_SECRET" || { echo 'Missing DevPilot callback secret'; exit 1; }
          BODY="$(jq -nc \\
            --arg externalRunId "github-\${GITHUB_RUN_ID}-\${GITHUB_RUN_ATTEMPT}" \\
            --arg commitSha "$RELEASE_COMMIT" --arg branchName "$GITHUB_REF_NAME" \\
            --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED \\
            --arg imageUri "$IMAGE_URI" --arg imageDigest "$IMAGE_DIGEST" \\
            --arg runUrl "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" \\
            --arg summary "Verified build $BUILD_RUN_ID; releasing original image digest" \\
            --arg buildExternalRunId "build:github-$BUILD_RUN_ID-$(jq -r .run_attempt build-run.json)" \\
            --arg manualApprovalId "$MANUAL_APPROVAL_ID" \\
            --arg approvalActor "$(jq -r '.triggering_actor.login // .actor.login' release-run.json)" \\
            --arg approvedAt "$(jq -r '.run_started_at // .created_at' release-run.json)" \\
            '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 \\
            -hmac "$DEVPILOT_CICD_CALLBACK_SECRET" | awk '{print $NF}')"
          curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL"
${previewJobs}
`
  return {
    fileName: '.github/workflows/devpilot.yml', content,
    secrets: ['DEVPILOT_CICD_CALLBACK_URL', 'DEVPILOT_CICD_CALLBACK_SECRET', 'DEVPILOT_BUILD_CALLBACK_URL', 'DEVPILOT_BUILD_CALLBACK_SECRET', ...(options.previewEnabled ? ['DEVPILOT_PREVIEW_CALLBACK_URL', 'DEVPILOT_PREVIEW_CALLBACK_SECRET'] : [])],
    notes: ['先等待 push 构建成功，再 Run workflow 填写该次 build_run_id；发布原始 digest，不重新构建。', '构建凭证保留 90 天，过期后需重新构建并确认，禁止回退到可变 tag。', '创建 production Environment，并按需添加 Required reviewers。', 'GITHUB_TOKEN 由 Actions 自动提供；Packages 必须允许写入。', ...(options.previewEnabled ? [`Preview 最长保留 ${options.previewTtlHours} 小时；仅为同仓库分支创建，不运行 Fork PR。`, 'Preview 使用独立密钥，切勿向临时环境注入生产 Secrets。'] : [])],
  }
}

function gitlabQuality(runtime: RuntimePreset) {
  if (runtime === 'NODE') return `  image: node:22-alpine
  script: ["npm ci", "npm test"]`
  if (runtime === 'JAVA') return `  image: maven:3.9-eclipse-temurin-21
  script: ["mvn -B test"]`
  if (runtime === 'GO') return `  image: golang:1.25-alpine
  script: ["go test -race ./..."]`
  return `  image: docker:28-cli
  services: ["docker:28-dind"]
  script: ["docker build --target test ."]`
}

function gitlab(options: WorkflowOptions): GeneratedWorkflow {
  const applicationKey = safeKey(options.applicationCode)
  const readBuild = `    - >-
      jq -e --arg project "$CI_PROJECT_ID" --arg pipeline "$CI_PIPELINE_ID"
      --arg commit "$CI_COMMIT_SHA" --arg branch "$CI_COMMIT_REF_NAME" --arg repository "$IMAGE_REPOSITORY"
      '.project == $project and .pipeline == $pipeline and .commit == $commit and .branch == $branch
      and .repository == $repository and (.digest | test("^sha256:[a-f0-9]{64}$"))
      and (.job | test("^[0-9]+$"))' devpilot-build.json > /dev/null
    - IMAGE_DIGEST="$(jq -er .digest devpilot-build.json)"
    - IMAGE_URI="$IMAGE_REPOSITORY@$IMAGE_DIGEST"
    - BUILD_EXTERNAL_RUN_ID="build:gitlab-$CI_PIPELINE_ID-$(jq -er .job devpilot-build.json)"`
  const previewUrl = yaml((options.previewUrlTemplate || 'https://pr-{{pr_id}}.preview.invalid')
    .replace('{{pr_id}}', '$CI_MERGE_REQUEST_IID'))
  const previewJobs = options.previewEnabled ? `
preview:
  stage: deploy
  image: alpine:3.22
  needs: [quality, security, image]
  resource_group: preview-$CI_MERGE_REQUEST_IID
  variables:
    CALLBACK_FALLBACK: ${yaml(options.previewCallbackUrl)}
  before_script:
    - apk add --no-cache curl jq openssl
  script:
    - CALLBACK_URL="\${DEVPILOT_PREVIEW_CALLBACK_URL:-$CALLBACK_FALLBACK}"
    - IMAGE_URI="$IMAGE_REPOSITORY:$IMAGE_TAG"
    - >-
      BODY="$(jq -nc --arg action DEPLOY --argjson pullRequestId "$CI_MERGE_REQUEST_IID"
      --arg baseBranch "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
      --arg externalRunId "gitlab-$CI_PIPELINE_ID-$CI_JOB_ID"
      --arg title "$CI_MERGE_REQUEST_TITLE" --arg branchName "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"
      --arg commitSha "$CI_COMMIT_SHA" --arg status SUCCEEDED --arg testStatus PASSED
      --arg securityStatus PASSED --arg imageUri "$IMAGE_URI" --arg runUrl "$CI_PIPELINE_URL" '$ARGS.named')"
    - SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_PREVIEW_CALLBACK_SECRET" | awk '{print $NF}')"
    - >-
      RESPONSE="$(curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json'
      -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL")"
    - echo "$RESPONSE" | jq -e '.data.status == "DEPLOYING" or .data.status == "READY"'
  environment:
    name: review/$CI_MERGE_REQUEST_IID
    url: ${previewUrl}
    on_stop: stop_preview
    auto_stop_in: ${options.previewTtlHours} hours
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID'

stop_preview:
  stage: deploy
  image: alpine:3.22
  resource_group: preview-$CI_MERGE_REQUEST_IID
  variables:
    GIT_STRATEGY: none
    CALLBACK_FALLBACK: ${yaml(options.previewCallbackUrl)}
  before_script:
    - apk add --no-cache curl jq openssl
  script:
    - CALLBACK_URL="\${DEVPILOT_PREVIEW_CALLBACK_URL:-$CALLBACK_FALLBACK}"
    - >-
      BODY="$(jq -nc --arg action CLOSE --argjson pullRequestId "$CI_MERGE_REQUEST_IID"
      --arg baseBranch "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" '$ARGS.named')"
    - SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_PREVIEW_CALLBACK_SECRET" | awk '{print $NF}')"
    - >-
      RESPONSE="$(curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json'
      -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL")"
    - echo "$RESPONSE" | jq -e '.data.status == "DELETED"'
  environment:
    name: review/$CI_MERGE_REQUEST_IID
    action: stop
  when: manual
  allow_failure: true
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID'
      when: manual
` : ''
  const content = `stages: [test, security, build, report, deploy]

variables:
  IMAGE_REPOSITORY: ${yaml(options.imageRepository)}
  IMAGE_TAG: "sha-$CI_COMMIT_SHA"
  DEVPILOT_BRANCH: ${yaml(options.branch)}

quality:
  stage: test
${gitlabQuality(options.runtime)}

security:
  stage: security
  image:
    name: aquasec/trivy:0.65.0
    entrypoint: [""]
  script:
    - trivy fs --scanners vuln,secret,misconfig --include-dev-deps --severity HIGH,CRITICAL --exit-code 1 .

image:
  stage: build
  image: docker:28-cli
  services: ["docker:28-dind"]
  needs: [quality, security]
  before_script:
    - apk add --no-cache jq
    - echo "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"
  script:
    - docker buildx build --push --metadata-file image-metadata.json --label "org.opencontainers.image.revision=$CI_COMMIT_SHA" -t "$IMAGE_REPOSITORY:$IMAGE_TAG" .
    - IMAGE_DIGEST="$(jq -er '."containerimage.digest" | select(test("^sha256:[a-f0-9]{64}$"))' image-metadata.json)"
    - >-
      jq -nc --arg project "$CI_PROJECT_ID" --arg pipeline "$CI_PIPELINE_ID" --arg job "$CI_JOB_ID"
      --arg commit "$CI_COMMIT_SHA" --arg branch "$CI_COMMIT_REF_NAME" --arg repository "$IMAGE_REPOSITORY"
      --arg digest "$IMAGE_DIGEST" '$ARGS.named' > devpilot-build.json
  artifacts:
    paths: [devpilot-build.json]
    expire_in: 7 days
  rules:
    - if: '$CI_COMMIT_BRANCH == $DEVPILOT_BRANCH'
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID'

build_report:
  stage: report
  image: alpine:3.22
  needs: [quality, security, image]
  environment: build-status-${applicationKey}
  variables:
    GIT_STRATEGY: none
    CALLBACK_FALLBACK: ${yaml(options.callbackUrl + '/builds')}
  before_script:
    - apk add --no-cache curl jq openssl
  script:
${readBuild}
    - test -n "$DEVPILOT_BUILD_CALLBACK_SECRET"
    - CALLBACK_URL="\${DEVPILOT_BUILD_CALLBACK_URL:-$CALLBACK_FALLBACK}"
    - >-
      BODY="$(jq -nc --arg externalRunId "$BUILD_EXTERNAL_RUN_ID" --arg commitSha "$CI_COMMIT_SHA"
      --arg branchName "$CI_COMMIT_REF_NAME" --arg imageUri "$IMAGE_URI" --arg imageDigest "$IMAGE_DIGEST"
      --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED --arg runUrl "$CI_PIPELINE_URL" '$ARGS.named')"
    - SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_BUILD_CALLBACK_SECRET" | awk '{print $NF}')"
    - >-
      curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json'
      -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL"
  rules:
    - if: '$CI_COMMIT_BRANCH == $DEVPILOT_BRANCH'

production:
  stage: deploy
  image: alpine:3.22
  needs: [quality, security, image, build_report]
  environment: production
  resource_group: production-${applicationKey}
  variables:
    GIT_STRATEGY: none
    CALLBACK_FALLBACK: ${yaml(options.callbackUrl)}
  before_script:
    - apk add --no-cache curl jq openssl
  script:
    - test "$CI_JOB_MANUAL" = true
    - printf '%s' "$DEVPILOT_MANUAL_APPROVAL_ID" | grep -Eq '^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$'
    - test -n "$DEVPILOT_CICD_CALLBACK_SECRET"
${readBuild}
    - CALLBACK_URL="\${DEVPILOT_CICD_CALLBACK_URL:-$CALLBACK_FALLBACK}"
    - >-
      BODY="$(jq -nc --arg externalRunId "gitlab-$CI_PIPELINE_ID-$CI_JOB_ID"
      --arg commitSha "$CI_COMMIT_SHA" --arg branchName "$CI_COMMIT_REF_NAME"
      --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED
      --arg imageUri "$IMAGE_URI" --arg imageDigest "$IMAGE_DIGEST" --arg runUrl "$CI_PIPELINE_URL"
      --arg buildExternalRunId "$BUILD_EXTERNAL_RUN_ID" --arg manualApprovalId "$DEVPILOT_MANUAL_APPROVAL_ID"
      --arg approvalActor "$GITLAB_USER_LOGIN" --arg approvedAt "$CI_JOB_STARTED_AT"
      --arg summary "GitLab quality, security and image gates passed" '$ARGS.named')"
    - SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_CICD_CALLBACK_SECRET" | awk '{print $NF}')"
    - >-
      curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json'
      -H "X-DevPilot-Signature: sha256=$SIGNATURE"
      --data-binary "$BODY" "$CALLBACK_URL"
  rules:
    - if: '$CI_COMMIT_BRANCH == $DEVPILOT_BRANCH'
      when: manual
${previewJobs}
`
  return {
    fileName: '.gitlab-ci.yml', content,
    secrets: ['DEVPILOT_CICD_CALLBACK_URL', 'DEVPILOT_CICD_CALLBACK_SECRET', 'DEVPILOT_BUILD_CALLBACK_URL', 'DEVPILOT_BUILD_CALLBACK_SECRET', ...(options.previewEnabled ? ['DEVPILOT_PREVIEW_CALLBACK_URL', 'DEVPILOT_PREVIEW_CALLBACK_SECRET'] : [])],
    notes: ['GitLab 模板尚未经真实 Runner 验收；需要支持 Docker-in-Docker 与 Buildx 的 Runner。', '生产密钥限定 production 环境；派生构建密钥限定 build-status-' + applicationKey + '，均设为 Protected + Masked，不能复用生产密钥。', 'build_report 成功后在 DevPilot 核对并确认该构建，再打开 production 手动作业填写变量 DEVPILOT_MANUAL_APPROVAL_ID；不要填写密钥。', '保留原流水线的构建制品（7 天）；生产任务只发布制品中的 digest，不重建或重新解析标签。', '保护生产分支，并限制谁可以运行 production 手动作业。', ...(options.previewEnabled ? [`Review App 最长保留 ${options.previewTtlHours} 小时，关闭 MR 或超时都会回收。`, 'Preview 变量使用独立密钥且只允许同项目 MR；不要复用生产 Secrets。'] : [])],
  }
}

function woodpeckerQuality(runtime: RuntimePreset) {
  if (runtime === 'NODE') return `    image: node:22-alpine
    commands: [npm ci, npm test]`
  if (runtime === 'JAVA') return `    image: maven:3.9-eclipse-temurin-21
    commands: [mvn -B test]`
  if (runtime === 'GO') return `    image: golang:1.25-alpine
    commands: [go test -race ./...]`
  return `    image: woodpeckerci/plugin-docker-buildx
    privileged: true
    settings:
      dry_run: true
      repo: ${yaml(optionsSafeRepository('example/app'))}
      target: test`
}

function optionsSafeRepository(value: string) {
  return value.replace(/[^a-zA-Z0-9._/:@-]/g, '') || 'example/app'
}

const woodpeckerVariable = (expression: string) => '$${' + expression + '}'

function woodpecker(options: WorkflowOptions): GeneratedWorkflow {
  const image = optionsSafeRepository(options.imageRepository.toLowerCase())
  const registry = image.includes('/') ? (image.split('/')[0] || 'docker.io') : 'docker.io'
  const content = `when:
  - event: [push, pull_request, deployment]

steps:
  quality:
${woodpeckerQuality(options.runtime)}

  security:
    image: aquasec/trivy:0.65.0
    commands:
      - trivy fs --scanners vuln,secret,misconfig --include-dev-deps --severity HIGH,CRITICAL --exit-code 1 .

  image:
    image: woodpeckerci/plugin-docker-buildx
    privileged: true
    settings:
      registry: ${yaml(registry)}
      repo: ${yaml(image)}
      tags: sha-\${CI_COMMIT_SHA}
      platforms: linux/amd64,linux/arm64
      username:
        from_secret: registry_username
      password:
        from_secret: registry_password
    when:
      - event: [push, deployment]
        branch: [${yaml(options.branch)}]

  production:
    image: alpine:3.22
    commands:
      - apk add --no-cache bash curl jq openssl
      - export IMAGE_URI="${image}:sha-${woodpeckerVariable('CI_COMMIT_SHA')}"
      - export CALLBACK_URL="${woodpeckerVariable(`DEVPILOT_CICD_CALLBACK_URL:-${options.callbackUrl}`)}"
      - >-
        export BODY="$$(jq -nc --arg externalRunId "woodpecker-${woodpeckerVariable('CI_PIPELINE_NUMBER')}"
        --arg commitSha "${woodpeckerVariable('CI_COMMIT_SHA')}" --arg branchName "${woodpeckerVariable('CI_COMMIT_BRANCH')}"
        --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED
        --arg imageUri "${woodpeckerVariable('IMAGE_URI')}" --arg runUrl "${woodpeckerVariable('CI_PIPELINE_URL')}"
        --arg summary "Woodpecker quality, security and image gates passed" '$$ARGS.named')"
      - export SIGNATURE="$$(printf '%s' "${woodpeckerVariable('BODY')}" | openssl dgst -sha256 -hmac "${woodpeckerVariable('DEVPILOT_CICD_CALLBACK_SECRET')}" | awk '{print $$NF}')"
      - >-
        curl --fail-with-body --connect-timeout 5 --max-time 30 --retry 2 --retry-max-time 90 --retry-connrefused -H 'Content-Type: application/json'
        -H "X-DevPilot-Signature: sha256=${woodpeckerVariable('SIGNATURE')}"
        --data-binary "${woodpeckerVariable('BODY')}" "${woodpeckerVariable('CALLBACK_URL')}"
    environment:
      DEVPILOT_CICD_CALLBACK_URL:
        from_secret: devpilot_cicd_callback_url
      DEVPILOT_CICD_CALLBACK_SECRET:
        from_secret: devpilot_cicd_callback_secret
    when:
      - event: deployment
        branch: [${yaml(options.branch)}]
        evaluate: 'CI_PIPELINE_DEPLOY_TARGET == "production"'
`
  return {
    fileName: '.woodpecker/devpilot.yml', content,
    secrets: ['devpilot_cicd_callback_url', 'devpilot_cicd_callback_secret', 'registry_username', 'registry_password'],
    notes: ['使用 Deployment → production 作为人工生产门禁。', 'Buildx 插件需要可信仓库的 privileged 权限；不要向不可信 PR 暴露 Secrets。'],
  }
}

export function generateWorkflow(options: WorkflowOptions): GeneratedWorkflow {
  if (options.provider === 'GITHUB') return github(options)
  if (options.provider === 'GITLAB') return gitlab(options)
  return woodpecker(options)
}
