import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHmac } from 'node:crypto'
import { generateWorkflow } from './workflowTemplates.ts'

const base = {
  runtime: 'NODE', branch: 'main', imageRepository: 'ghcr.io/example/blog',
  callbackUrl: 'https://ops.example.com/api/cicd/webhooks/blog', applicationCode: 'blog',
  previewEnabled: false, previewCallbackUrl: '', previewUrlTemplate: '', previewTtlHours: 72,
}

test('generated recovery script signs original evidence and fails closed before sending mismatched artifacts', () => {
  const content = generateWorkflow({ ...base, provider: 'GITHUB' }).content
  const recovery = content.split('  recover_build:')[1].split('  production:')[0]
  const script = recovery.split('      - name: Validate and resend build evidence only')[1]
    .split('        run: |\n')[1].replace(/^          /gm, '')
  const root = mkdtempSync(join(tmpdir(), 'devpilot-recovery-test-'))
  const artifact = { image: base.imageRepository, runId: '123', runAttempt: '2', commit: 'a'.repeat(40),
    repository: 'example/blog', branch: 'main', event: 'push', digest: 'sha256:' + 'b'.repeat(64) }
  const response = { externalRunId: 'build:github-123-2', status: 'SUCCEEDED', deployStatus: 'AWAITING_APPROVAL',
    commitSha: artifact.commit, imageUri: artifact.image + '@' + artifact.digest }
  const secret = 'synthetic-build-only-key'
  let index = 0
  const check = ({ change = {}, responseChange = {}, responseCode = 0, curlStatus = 0, missingSecret = false } = {}) => {
    const dir = join(root, String(index++))
    mkdirSync(join(dir, 'recovered-release'), { recursive: true })
    writeFileSync(join(dir, 'build-run.json'), JSON.stringify({ head_sha: artifact.commit }))
    writeFileSync(join(dir, 'recovered-release/release.json'), JSON.stringify({ ...artifact, ...change }))
    const capture = join(dir, 'curl-args')
    const result = spawnSync('bash', ['-c', `curl() { printf '%s\\0' "$@" > "$CAPTURE"; printf '%s' "$FIXTURE_RESPONSE"; return "$FIXTURE_CURL_STATUS"; }\n${script}`], {
      cwd: dir, encoding: 'utf8', timeout: 5000,
      env: { PATH: process.env.PATH, CAPTURE: capture, FIXTURE_RESPONSE: JSON.stringify({ code: responseCode, data: { ...response, ...responseChange } }),
        FIXTURE_CURL_STATUS: String(curlStatus), IMAGE_REPOSITORY: artifact.image, BUILD_RUN_ID: '123', BUILD_RUN_ATTEMPT: '2',
        GITHUB_REPOSITORY: artifact.repository, EXPECTED_BRANCH: artifact.branch, GITHUB_SERVER_URL: 'https://github.com',
        DEVPILOT_BUILD_CALLBACK_URL: 'https://callback.invalid/builds', DEVPILOT_BUILD_CALLBACK_SECRET: missingSecret ? '' : secret },
    })
    assert.ifError(result.error)
    assert.doesNotMatch(result.stdout + result.stderr, /synthetic-build-only-key/)
    return { result, args: existsSync(capture) ? readFileSync(capture, 'utf8').split('\0').filter(Boolean) : null }
  }
  try {
    const { result, args } = check()
    assert.equal(result.status, 0, result.stderr)
    const body = args[args.indexOf('--data-binary') + 1]
    const payload = JSON.parse(body)
    assert.equal(payload.externalRunId, response.externalRunId)
    assert.equal(payload.commitSha, artifact.commit)
    assert.equal(payload.imageUri, response.imageUri)
    assert.equal(payload.testStatus, 'PASSED')
    assert.equal(payload.securityStatus, 'PASSED')
    assert.equal(payload.manualApprovalId, undefined)
    assert.equal(payload.buildExternalRunId, undefined)
    assert.equal(args.at(-1), 'https://callback.invalid/builds')
    assert.ok(args.includes('X-DevPilot-Signature: sha256=' + createHmac('sha256', secret).update(body).digest('hex')))
    for (const change of [{ runAttempt: '1' }, { runId: '999' }, { commit: 'c'.repeat(40) },
      { repository: 'other/blog' }, { branch: 'other' }, { event: 'pull_request' }, { digest: 'latest' }]) {
      const rejected = check({ change })
      assert.notEqual(rejected.result.status, 0)
      assert.equal(rejected.args, null, 'invalid artifact must not reach callback')
    }
    const noSecret = check({ missingSecret: true })
    assert.notEqual(noSecret.result.status, 0)
    assert.equal(noSecret.args, null)
    assert.notEqual(check({ curlStatus: 22 }).result.status, 0)
    assert.notEqual(check({ responseCode: 40041 }).result.status, 0, 'HTTP success is not application acceptance')
    for (const responseChange of [{ externalRunId: 'build:github-999-2' }, { status: 'FAILED' },
      { deployStatus: 'HEALTHY' }, { commitSha: 'd'.repeat(40) }, { imageUri: 'ghcr.io/other@sha256:' + 'e'.repeat(64) }]) {
      assert.notEqual(check({ responseChange }).result.status, 0)
    }
  } finally { rmSync(root, { recursive: true, force: true }) }
})

test('all providers retain unfixed HIGH/CRITICAL findings in the security gate', () => {
  for (const provider of ['GITHUB', 'GITLAB', 'WOODPECKER']) {
    const { content } = generateWorkflow({ ...base, provider })
    assert.doesNotMatch(content, /--ignore-unfixed/)
    assert.match(content, /--severity HIGH,CRITICAL/)
    assert.match(content, /--include-dev-deps/)
    assert.match(content, /--exit-code 1/)
  }
})

test('recovery has build-only authority and both recovery and release verify every original gate job', () => {
  const { content } = generateWorkflow({ ...base, provider: 'GITHUB' })
  const recovery = content.split('  recover_build:')[1].split('  production:')[0]
  for (const block of content.matchAll(/        run: \|\n((?:          .*\n|\n)+)/g)) {
    const result = spawnSync('bash', ['-n'], { input: block[1].replace(/^          /gm, ''), encoding: 'utf8' })
    assert.ifError(result.error)
    assert.equal(result.status, 0, result.stderr)
  }
  assert.match(recovery, /inputs.operation == 'recover_build'/)
  assert.match(recovery, /environment: build-status-blog/)
  assert.match(recovery, /permissions:\n      actions: read/)
  assert.match(recovery, /build:github-\$BUILD_RUN_ID-\$BUILD_RUN_ATTEMPT/)
  assert.match(recovery, /\.data.deployStatus == "AWAITING_APPROVAL"/)
  assert.doesNotMatch(recovery, /DEVPILOT_CICD_CALLBACK_SECRET|manualApprovalId|actions\/checkout|build-push-action|docker build|packages: write/)
  for (const job of [recovery, content.split('  production:')[1]]) {
    assert.match(job, /actions\/runs\/\$BUILD_RUN_ID\/attempts\/\$BUILD_RUN_ATTEMPT\/jobs/)
    const filter = job.match(/--arg commit "\$\(jq -r .head_sha build-run.json\)" '([\s\S]*?)' build-jobs.json/)?.[1]
    assert.ok(filter)
    const jobs = ['quality', 'security', 'image'].map(name => ({ name, run_id: 123,
      head_sha: 'a'.repeat(40), status: 'completed', conclusion: 'success' }))
    const check = rows => spawnSync('jq', ['-e', '--arg', 'run', '123', '--arg', 'commit', 'a'.repeat(40), filter],
      { input: JSON.stringify([{ jobs: rows.slice(0, 1) }, { jobs: rows.slice(1) }]), encoding: 'utf8' })
    const accepted = check(jobs)
    assert.ifError(accepted.error)
    assert.equal(accepted.status, 0)
    assert.notEqual(check([]).status, 0)
    assert.notEqual(check(jobs.slice(1)).status, 0)
    assert.notEqual(check([...jobs, jobs[0]]).status, 0)
    for (const index of [0, 1, 2]) {
      for (const change of [{ conclusion: 'failure' }, { conclusion: 'skipped' }, { conclusion: 'cancelled' },
        { status: 'in_progress' }, { head_sha: 'b'.repeat(40) }, { run_id: 124 }]) {
        assert.notEqual(check(jobs.map((value, n) => n === index ? { ...value, ...change } : value)).status, 0)
      }
    }
  }
})

test('GitHub artifact validation binds the exact build attempt and repository identity', () => {
  const { content } = generateWorkflow({ ...base, provider: 'GITHUB' })
  assert.match(content, /name: devpilot-release-blog-\$\{\{ github.run_attempt }}/)
  assert.match(content, /name: devpilot-release-blog-\$\{\{ env.BUILD_RUN_ATTEMPT }}/)
  const filter = content.split('  production:')[1].match(/'(\.image == \$image[\s\S]*?)' verified-release\/release\.json/)?.[1]
  assert.ok(filter)
  const valid = { image: base.imageRepository, runId: '123', commit: 'a'.repeat(40),
    runAttempt: '2', repository: 'example/blog', branch: 'main', event: 'push', digest: 'sha256:' + 'b'.repeat(64) }
  const check = value => spawnSync('jq', ['-e', '--arg', 'image', base.imageRepository,
    '--arg', 'run', '123', '--arg', 'commit', valid.commit, '--arg', 'attempt', '2',
    '--arg', 'repo', 'example/blog', '--arg', 'branch', 'main', filter],
    { input: JSON.stringify(value), encoding: 'utf8' })
  const accepted = check(valid)
  assert.ifError(accepted.error)
  assert.equal(accepted.status, 0)
  for (const changed of [{ runAttempt: '1' }, { runAttempt: null }, { runAttempt: 2 },
    { repository: 'other/blog' }, { branch: 'other' }, { event: 'pull_request' },
    { digest: 'latest' }, { commit: 'c'.repeat(40) }, { runId: '124' }, { image: 'ghcr.io/other/blog' }]) {
    assert.notEqual(check({ ...valid, ...changed }).status, 0, JSON.stringify(changed))
  }
})

test('all generated callback requests have bounded connection, transfer and retry budgets', () => {
  for (const provider of ['GITHUB', 'GITLAB', 'WOODPECKER']) {
    const { content } = generateWorkflow({ ...base, provider, previewEnabled: true,
      previewCallbackUrl: 'https://ops.example.com/api/cicd/previews/blog',
      previewUrlTemplate: 'https://pr-{number}.example.com' })
    const requests = content.split('\n').filter(line => /curl --fail/.test(line))
    assert.ok(requests.length > 0, `${provider}: no callback request tested`)
    for (const request of requests) {
      assert.match(request, /--connect-timeout 5\b/)
      assert.match(request, /--max-time 30\b/)
      assert.match(request, /--retry 2\b/)
      assert.match(request, /--retry-max-time 90\b/)
      assert.match(request, /--retry-connrefused\b/)
      assert.doesNotMatch(request, /--retry-all-errors|\|\| true/)
    }
  }
})

test('GitHub reporter executes distinct cancelled, skipped, failed and successful mappings', () => {
  const content = generateWorkflow({ ...base, provider: 'GITHUB' }).content
  const finished = content.split('  build_finished:')[1].split('  quality:')[0]
  const mapping = finished.match(/          STATUS=FAILED[\s\S]*?(?=          BODY=)/)?.[0]
  assert.ok(mapping)
  const check = (quality, security, build, digest = 'sha256:' + 'b'.repeat(64)) => {
    const result = spawnSync('bash', ['-euo', 'pipefail', '-c', `${mapping}\nprintf '%s|%s|%s|%s' "$STATUS" "$TEST_STATUS" "$SECURITY_STATUS" "$IMAGE_URI"`], {
      encoding: 'utf8', env: { PATH: process.env.PATH, TEST_RESULT: quality, SECURITY_RESULT: security,
        BUILD_RESULT: build, DIGEST: digest, IMAGE_REPOSITORY: base.imageRepository },
    })
    assert.ifError(result.error)
    return result
  }
  assert.equal(check('cancelled', 'success', 'skipped').stdout, 'CANCELLED|SKIPPED|PASSED|')
  assert.equal(check('success', 'success', 'cancelled').stdout, 'CANCELLED|PASSED|PASSED|')
  assert.equal(check('failure', 'cancelled', 'skipped').stdout, 'FAILED|FAILED|SKIPPED|')
  assert.equal(check('skipped', 'skipped', 'skipped').stdout, 'FAILED|SKIPPED|SKIPPED|')
  assert.equal(check('success', 'success', 'success').stdout, `SUCCEEDED|PASSED|PASSED|${base.imageRepository}@sha256:${'b'.repeat(64)}`)
  assert.notEqual(check('success', 'success', 'success', 'latest').status, 0)
})

for (const runtime of ['NODE', 'JAVA', 'GO', 'DOCKER']) {
  test(`GitHub ${runtime}: manually releases verified digest without rebuilding`, () => {
    const workflow = generateWorkflow({ ...base, provider: 'GITHUB', runtime })
    assert.match(workflow.content, /if: github.event_name == 'workflow_dispatch'/)
    const production = workflow.content.split('  production:')[1]
    assert.doesNotMatch(production, /needs:|build-push-action|docker build/)
    assert.match(workflow.content, /build_run_id:/)
    assert.match(production, /\.event == "push" and .status == "completed"/)
    assert.match(production, /all\(\["quality", "security", "image"\]/)
    assert.match(production, /\.workflow_id == \$release\[0\].workflow_id/)
    assert.match(production, /\.image == \$image and .runId == \$run and .commit == \$commit/)
    assert.match(production, /IMAGE_URI=\$IMAGE_REPOSITORY@/)
    assert.match(production, /--arg imageDigest "\$IMAGE_DIGEST"/)
    assert.match(production, /--arg buildExternalRunId "build:github-\$BUILD_RUN_ID-/)
    assert.match(production, /--arg approvalActor/)
    assert.match(production, /--arg approvedAt/)
    assert.match(workflow.content, /manual_approval_id:\n[\s\S]*?required: false/)
    assert.match(production, /\[\[ "\$MANUAL_APPROVAL_ID" =~/)
    assert.match(production, /inputs.operation == 'release'/)
    assert.match(production, /MANUAL_APPROVAL_ID: \$\{\{ inputs.manual_approval_id }}/)
    assert.match(production, /--arg manualApprovalId "\$MANUAL_APPROVAL_ID"/)
    assert.match(production, /\.event == "workflow_dispatch" and \(\.id \| tostring\) == \$run/)
    assert.equal(workflow.content.match(/github.event_name != 'workflow_dispatch'/g)?.length, 4)
    assert.match(workflow.content, /Missing DevPilot callback secret/)
    assert.match(workflow.content, /sha-\$\{\{ env.SOURCE_SHA }}/)
    assert.ok(workflow.secrets.includes('DEVPILOT_CICD_CALLBACK_SECRET'))
    const buildReports = workflow.content.split('jobs:')[1].split('  quality:')[0]
    assert.match(buildReports, /environment: build-status-blog/)
    assert.match(buildReports, /DEVPILOT_BUILD_CALLBACK_SECRET/)
    assert.doesNotMatch(buildReports, /DEVPILOT_CICD_CALLBACK_SECRET|environment: production|actions\/checkout/)
    assert.match(buildReports, /needs: \[quality, security, image\]/)
    assert.match(buildReports, /build:github-\$GITHUB_RUN_ID-\$GITHUB_RUN_ATTEMPT/)
  })
  test(`GitLab ${runtime}: production confirmation retained`, () => {
    const workflow = generateWorkflow({ ...base, provider: 'GITLAB', runtime })
    assert.match(workflow.content, /when: manual/)
    assert.match(workflow.content, /needs: \[quality, security, image\]/)
    assert.match(workflow.content, /X-DevPilot-Signature/)
    const production = workflow.content.split('\nproduction:')[1].split('\npreview:')[0]
    const report = workflow.content.split('\nbuild_report:')[1].split('\nproduction:')[0]
    assert.match(production, /needs: \[quality, security, image, build_report\]/)
    assert.match(production, /--arg manualApprovalId "\$DEVPILOT_MANUAL_APPROVAL_ID"/)
    assert.match(production, /--arg buildExternalRunId "\$BUILD_EXTERNAL_RUN_ID"/)
    assert.match(production, /IMAGE_URI="\$IMAGE_REPOSITORY@\$IMAGE_DIGEST"/)
    assert.doesNotMatch(production, /docker build|IMAGE_REPOSITORY:\$IMAGE_TAG/)
    assert.match(report, /environment: build-status-blog/)
    assert.match(report, /DEVPILOT_BUILD_CALLBACK_SECRET/)
    assert.doesNotMatch(report, /DEVPILOT_CICD_CALLBACK_SECRET/)
    assert.match(workflow.content, /--metadata-file image-metadata.json/)
    assert.match(workflow.content, /paths: \[devpilot-build.json\]/)
  })
}

test('generated release provenance filter rejects wrong event, attempt, run, branch and repository', () => {
  const content = generateWorkflow({ ...base, provider: 'GITHUB' }).content
  const filter = content.match(/'(\.event == "workflow_dispatch"[\s\S]*?)' release-run\.json/)?.[1]
  assert.ok(filter)
  const valid = { event: 'workflow_dispatch', id: 123, run_attempt: 2, head_branch: 'main', repository: { full_name: 'example/blog' } }
  const check = data => spawnSync('jq', ['-e', '--arg', 'run', '123', '--arg', 'attempt', '2', '--arg', 'branch', 'main', '--arg', 'repo', 'example/blog', filter], { input: JSON.stringify(data), encoding: 'utf8' })
  const accepted = check(valid)
  assert.ifError(accepted.error)
  assert.equal(accepted.status, 0)
  for (const changed of [{ event: 'push' }, { id: 124 }, { run_attempt: 1 }, { head_branch: 'other' }, { repository: { full_name: 'other/blog' } }]) {
    assert.notEqual(check({ ...valid, ...changed }).status, 0)
  }
})

test('GitLab artifact validation rejects changed provenance or non-digest images', () => {
  const content = generateWorkflow({ ...base, provider: 'GITLAB' }).content
  const filters = [...content.matchAll(/'(\.project == \$project[\s\S]*?)' devpilot-build\.json/g)].map(match => match[1])
  assert.equal(filters.length, 2, 'Both build reporting and deployment must validate the artifact')
  assert.equal(filters[0], filters[1])
  const valid = { project: '12', pipeline: '34', commit: 'a'.repeat(40), branch: 'main', repository: base.imageRepository,
    digest: 'sha256:' + 'b'.repeat(64), job: '56' }
  const check = data => spawnSync('jq', ['-e', '--arg', 'project', '12', '--arg', 'pipeline', '34',
    '--arg', 'commit', valid.commit, '--arg', 'branch', 'main', '--arg', 'repository', base.imageRepository, filters[0]],
    { input: JSON.stringify(data), encoding: 'utf8' })
  const accepted = check(valid)
  assert.ifError(accepted.error)
  assert.equal(accepted.status, 0)
  for (const changed of [{ project: 'other' }, { pipeline: 'other' }, { commit: 'c'.repeat(40) },
    { branch: 'other' }, { repository: 'other/image' }, { digest: 'latest' }, { digest: null }, { job: 'bad' }]) {
    assert.notEqual(check({ ...valid, ...changed }).status, 0)
  }
})

test('production secret validation does not leak into Preview jobs', () => {
  const workflow = generateWorkflow({ ...base, provider: 'GITHUB', previewEnabled: true,
    previewCallbackUrl: 'https://ops.example.com/api/cicd/webhooks/blog/previews',
    previewUrlTemplate: 'https://pr-{{pr_id}}.example.com' })
  assert.equal(workflow.content.match(/Missing DevPilot callback secret/g)?.length, 1)
  assert.match(workflow.content, /DEVPILOT_PREVIEW_CALLBACK_SECRET/)
})
