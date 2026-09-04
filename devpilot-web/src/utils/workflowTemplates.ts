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
          RESPONSE="$(curl --fail-with-body --retry 2 -H 'Content-Type: application/json' \\
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
          RESPONSE="$(curl --fail-with-body --retry 2 -H 'Content-Type: application/json' \\
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

permissions:
  contents: read
  packages: write

concurrency:
  group: devpilot-${applicationKey}-\${{ github.ref }}
  cancel-in-progress: true

env:
  IMAGE_REPOSITORY: ${image}
  SOURCE_SHA: \${{ github.event.pull_request.head.sha || github.sha }}

jobs:
  quality:
    if: github.event.action != 'closed'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
        with:
          ref: \${{ env.SOURCE_SHA }}
${githubQuality(options.runtime)}

  security:
    if: github.event.action != 'closed'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
        with:
          ref: \${{ env.SOURCE_SHA }}
      - name: Trivy source, dependency, secret and IaC gate
        run: |
          docker run --rm -v "$PWD:/workspace" -w /workspace aquasec/trivy:0.65.0 fs \\
            --scanners vuln,secret,misconfig --severity HIGH,CRITICAL \\
            --exit-code 1 --ignore-unfixed .

  image:
    needs: [quality, security]
    if: github.event.action != 'closed' && (github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository)
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
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: \${{ env.IMAGE_REPOSITORY }}:sha-\${{ env.SOURCE_SHA }}
          labels: org.opencontainers.image.revision=\${{ env.SOURCE_SHA }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  production:
    needs: [quality, security, image]
    if: github.event_name == 'workflow_dispatch' && github.ref_name == ${branch}
    runs-on: ubuntu-24.04
    environment: production
    concurrency:
      group: production-${applicationKey}
      cancel-in-progress: false
    env:
      DEVPILOT_CICD_CALLBACK_URL: \${{ secrets.DEVPILOT_CICD_CALLBACK_URL }}
      DEVPILOT_CICD_CALLBACK_SECRET: \${{ secrets.DEVPILOT_CICD_CALLBACK_SECRET }}
      IMAGE_URI: ${options.imageRepository.toLowerCase()}:sha-\${{ github.sha }}
    steps:
      - name: Send signed deployment evidence
        env:
          CALLBACK_FALLBACK: ${callback}
        run: |
          CALLBACK_URL="\${DEVPILOT_CICD_CALLBACK_URL:-$CALLBACK_FALLBACK}"
          BODY="$(jq -nc \\
            --arg externalRunId "github-\${GITHUB_RUN_ID}-\${GITHUB_RUN_ATTEMPT}" \\
            --arg commitSha "$GITHUB_SHA" --arg branchName "$GITHUB_REF_NAME" \\
            --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED \\
            --arg imageUri "$IMAGE_URI" \\
            --arg runUrl "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" \\
            --arg summary "GitHub Actions quality, security and image gates passed" \\
            '$ARGS.named')"
          SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 \\
            -hmac "$DEVPILOT_CICD_CALLBACK_SECRET" | awk '{print $NF}')"
          curl --fail-with-body --retry 2 -H 'Content-Type: application/json' \\
            -H "X-DevPilot-Signature: sha256=$SIGNATURE" --data-binary "$BODY" "$CALLBACK_URL"
${previewJobs}
`
  return {
    fileName: '.github/workflows/devpilot.yml', content,
    secrets: ['DEVPILOT_CICD_CALLBACK_URL', 'DEVPILOT_CICD_CALLBACK_SECRET', ...(options.previewEnabled ? ['DEVPILOT_PREVIEW_CALLBACK_URL', 'DEVPILOT_PREVIEW_CALLBACK_SECRET'] : [])],
    notes: ['创建 production Environment，并按需添加 Required reviewers。', 'GITHUB_TOKEN 由 Actions 自动提供；Packages 必须允许写入。', ...(options.previewEnabled ? [`Preview 最长保留 ${options.previewTtlHours} 小时；仅为同仓库分支创建，不运行 Fork PR。`, 'Preview 使用独立密钥，切勿向临时环境注入生产 Secrets。'] : [])],
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
      RESPONSE="$(curl --fail-with-body --retry 2 -H 'Content-Type: application/json'
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
      RESPONSE="$(curl --fail-with-body --retry 2 -H 'Content-Type: application/json'
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
  const content = `stages: [test, security, build, deploy]

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
    - trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 --ignore-unfixed .

image:
  stage: build
  image: docker:28-cli
  services: ["docker:28-dind"]
  needs: [quality, security]
  before_script:
    - echo "$CI_REGISTRY_PASSWORD" | docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"
  script:
    - docker build --label "org.opencontainers.image.revision=$CI_COMMIT_SHA" -t "$IMAGE_REPOSITORY:$IMAGE_TAG" .
    - docker push "$IMAGE_REPOSITORY:$IMAGE_TAG"
  rules:
    - if: '$CI_COMMIT_BRANCH == $DEVPILOT_BRANCH'
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID'

production:
  stage: deploy
  image: alpine:3.22
  needs: [quality, security, image]
  environment: production
  resource_group: production-${applicationKey}
  variables:
    CALLBACK_FALLBACK: ${yaml(options.callbackUrl)}
  before_script:
    - apk add --no-cache curl jq openssl
  script:
    - CALLBACK_URL="\${DEVPILOT_CICD_CALLBACK_URL:-$CALLBACK_FALLBACK}"
    - IMAGE_URI="$IMAGE_REPOSITORY:$IMAGE_TAG"
    - >-
      BODY="$(jq -nc --arg externalRunId "gitlab-$CI_PIPELINE_ID-$CI_JOB_ID"
      --arg commitSha "$CI_COMMIT_SHA" --arg branchName "$CI_COMMIT_REF_NAME"
      --arg status SUCCEEDED --arg testStatus PASSED --arg securityStatus PASSED
      --arg imageUri "$IMAGE_URI" --arg runUrl "$CI_PIPELINE_URL"
      --arg summary "GitLab quality, security and image gates passed" '$ARGS.named')"
    - SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVPILOT_CICD_CALLBACK_SECRET" | awk '{print $NF}')"
    - >-
      curl --fail-with-body --retry 2 -H 'Content-Type: application/json'
      -H "X-DevPilot-Signature: sha256=$SIGNATURE"
      --data-binary "$BODY" "$CALLBACK_URL"
  rules:
    - if: '$CI_COMMIT_BRANCH == $DEVPILOT_BRANCH'
      when: manual
${previewJobs}
`
  return {
    fileName: '.gitlab-ci.yml', content,
    secrets: ['DEVPILOT_CICD_CALLBACK_URL', 'DEVPILOT_CICD_CALLBACK_SECRET', ...(options.previewEnabled ? ['DEVPILOT_PREVIEW_CALLBACK_URL', 'DEVPILOT_PREVIEW_CALLBACK_SECRET'] : [])],
    notes: ['把生产变量设为 Protected + Masked。', '保护生产分支，并限制谁可以运行 production 手动作业。', ...(options.previewEnabled ? [`Review App 最长保留 ${options.previewTtlHours} 小时，关闭 MR 或超时都会回收。`, 'Preview 变量使用独立密钥且只允许同项目 MR；不要复用生产 Secrets。'] : [])],
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
      - trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 --ignore-unfixed .

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
        curl --fail-with-body --retry 2 -H 'Content-Type: application/json'
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
