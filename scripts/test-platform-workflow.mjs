// Exercises the actual shell blocks in the platform workflow with local GitHub fixtures.
// No network, GitHub dispatch, registry mutation, or real callback is performed.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHmac } from 'node:crypto'
import { generateWorkflow } from '../devpilot-web/src/utils/workflowTemplates.ts'

let workflow = readFileSync(new URL('../.github/workflows/cicd.yml', import.meta.url), 'utf8')
function block(name) {
  const parts = name.split('::')
  const source = parts.length === 2 ? workflow.slice(workflow.indexOf(`  ${parts[0]}:\n`)) : workflow
  name = parts.at(-1)
  const start = source.indexOf(`      - name: ${name}\n`)
  assert(start >= 0, `missing step ${name}`)
  const remaining = source.slice(start)
  const run = remaining.indexOf('        run: |\n')
  assert(run >= 0)
  const lines = remaining.slice(run + '        run: |\n'.length).split('\n')
  const result = []
  for (const line of lines) {
    if (line && !line.startsWith('          ')) break
    result.push(line.slice(10))
  }
  return result.join('\n')
}
const root = mkdtempSync(join(tmpdir(), 'devpilot-platform-workflow-'))
const sha = 'a'.repeat(40), digest = `sha256:${'b'.repeat(64)}`
const image = 'ghcr.io/example/devpilot-web'
const env = { ...process.env, GITHUB_REPOSITORY: 'example/DevPilot', GITHUB_RUN_ID: '200',
  GITHUB_RUN_ATTEMPT: '1', BUILD_RUN_ID: '100', BUILD_RUN_ATTEMPT: '2',
  EXPECTED_BRANCH: 'main', GITHUB_REF_NAME: 'main', GITHUB_ENV: join(root, 'env'),
  MANUAL_APPROVAL_ID: '11111111-2222-3333-4444-555555555555', IMAGE_REPOSITORY: image }
const build = { id: 100, run_attempt: 2, event: 'push', status: 'completed', head_sha: sha,
  head_branch: 'main', repository: {full_name: 'example/DevPilot'},
  head_repository: {full_name: 'example/DevPilot'}, workflow_id: 42 }
const release = { id: 200, run_attempt: 1, event: 'workflow_dispatch', head_branch: 'main',
  repository: {full_name: 'example/DevPilot'}, workflow_id: 42,
  triggering_actor: {login: 'reviewer'}, run_started_at: '2026-09-06T00:00:00Z' }
const names = ['Test all components', 'Security gate', 'Build server image', 'Build web image', 'Build agent image']
const jobs = [{ jobs: names.map(name => ({name, run_id: 100, head_sha: sha, status: 'completed', conclusion: 'success'})) }]
const mock = `
gh() {
  case "$*" in
    *'/jobs?'*) cat fixture-jobs.json ;;
    *'/runs/100') cat fixture-build.json ;;
    *'/runs/200') cat fixture-release.json ;;
    *) return 90 ;;
  esac
}
timeout() { shift; "$@"; }
curl() {
  while (( $# )); do
    if [[ "$1" == --data-binary ]]; then printf '%s' "$2" > callback.json; fi
    if [[ "$1" == -H && "$2" == X-DevPilot-Signature:* ]]; then printf '%s' "$2" > signature.txt; fi
    shift
  done
  printf '%s' "\${MOCK_RESPONSE:-}"
  return "\${MOCK_CURL_STATUS:-0}"
}
`
let count = 0
function run(name, step, expected, overrides = {}) {
  const result = spawnSync('bash', ['-euo', 'pipefail', '-c', mock + '\n' + block(step)], {
    cwd: root, env: {...env, ...overrides}, encoding: 'utf8', timeout: 5000,
  })
  assert.equal(result.error, undefined)
  assert.equal(result.status === 0, expected, `${name}: ${result.stderr}`)
  console.log(`PASS: ${name}`); count++
}
function fixtures(b = build, r = release, j = jobs) {
  for (const [name, value] of Object.entries({build:b, release:r, jobs:j}))
    writeFileSync(join(root, `fixture-${name}.json`), JSON.stringify(value))
}
try {
  fixtures()
  const step = 'Verify successful build provenance'
  run('five successful gates', step, true)
  run('approval required', step, false, {MANUAL_APPROVAL_ID: ''})
  run('run ID must be numeric', step, false, {BUILD_RUN_ID: '../100'})
  for (const [key, value] of [['event','pull_request'], ['workflow_id',43], ['head_branch','other'], ['id',101]]) {
    fixtures({...build, [key]:value}); run(`reject build ${key}`, step, false)
  }
  fixtures({...build, head_repository:{full_name:'fork/DevPilot'}})
  run('reject foreign head repository', step, false)
  for (const name of names) {
    const changed = structuredClone(jobs)
    changed[0].jobs.find(j => j.name === name).conclusion = 'failure'
    fixtures(build, release, changed); run(`reject failed ${name}`, step, false)
  }
  fixtures(build, release, [{jobs:jobs[0].jobs.slice(1)}]); run('reject missing gate', step, false)
  fixtures(build, release, [{jobs:[...jobs[0].jobs, jobs[0].jobs[0]]}]); run('reject duplicate gate', step, false)
  fixtures(); run('restored valid provenance', step, true)
  // Artifact validation operates on the same files downloaded by Actions.
  mkdirSync(join(root, 'verified-release'))
  const evidence = {digest,image,commit:sha,runId:'100',runAttempt:'2',repository:'example/DevPilot',branch:'main',event:'push'}
  function artifact(value) { writeFileSync(join(root, 'verified-release/release.json'), JSON.stringify(value)) }
  artifact(evidence); run('accept original digest artifact', 'Validate immutable image evidence', true)
  for (const [key,value] of [['digest','latest'], ['commit','c'.repeat(40)], ['runAttempt','1'], ['runId','101'], ['image','ghcr.io/other/web'], ['branch','other'], ['repository','fork/DevPilot']]) {
    artifact({...evidence,[key]:value}); run(`reject artifact ${key}`, 'Validate immutable image evidence', false)
  }
  const callbackEnv = { RELEASE_COMMIT:sha, IMAGE_URI:`${image}@${digest}`, IMAGE_DIGEST:digest,
    DEVPILOT_CICD_CALLBACK_URL:'https://invalid.example/callback', DEVPILOT_CICD_CALLBACK_SECRET:'synthetic-test-only',
    CALLBACK_FALLBACK:'', GITHUB_SERVER_URL:'https://github.com' }
  run('signed version-bound callback', 'Send signed deployment evidence', true, callbackEnv)
  const body = readFileSync(join(root, 'callback.json'), 'utf8'), payload = JSON.parse(body)
  assert.equal(payload.imageUri, `${image}@${digest}`)
  assert.equal(payload.buildExternalRunId, 'build:github-100-2')
  assert.equal(payload.manualApprovalId, env.MANUAL_APPROVAL_ID)
  assert.equal(payload.approvalActor, 'reviewer')
  assert.equal(readFileSync(join(root, 'signature.txt'), 'utf8'),
    'X-DevPilot-Signature: sha256=' + createHmac('sha256', callbackEnv.DEVPILOT_CICD_CALLBACK_SECRET).update(body).digest('hex'))
  run('missing callback URL fails closed', 'Send signed deployment evidence', false, {...callbackEnv,DEVPILOT_CICD_CALLBACK_URL:''})

  // Build-only recovery uses the original attempt, not this dispatch's identity.
  fixtures()
  const recoveryStep = 'Verify original build and successful gate jobs'
  run('recover original successful build', recoveryStep, true)
  fixtures({...build,event:'workflow_dispatch'}); run('recovery rejects dispatch as build', recoveryStep, false)
  fixtures(build, release, [{jobs:jobs[0].jobs.slice(0,4)}]); run('recovery rejects missing agent build', recoveryStep, false)
  fixtures(); run('restore recovery provenance', recoveryStep, true)
  mkdirSync(join(root, 'recovered-release'))
  writeFileSync(join(root, 'recovered-release/release.json'), JSON.stringify(evidence))
  const recovered = {code:0,data:{externalRunId:'build:github-100-2',status:'SUCCEEDED',deployStatus:'AWAITING_APPROVAL',commitSha:sha,imageUri:`${image}@${digest}`}}
  const buildEnv = {DEVPILOT_BUILD_CALLBACK_URL:'https://invalid.example/build',
    DEVPILOT_BUILD_CALLBACK_SECRET:'synthetic-build-only',GITHUB_SHA:sha,
    GITHUB_SERVER_URL:'https://github.com',MOCK_RESPONSE:JSON.stringify(recovered)}
  const resend = 'Validate and resend build evidence only'
  run('recover build-only evidence', resend, true, buildEnv)
  run('recovery rejects business error envelope', resend, false, {...buildEnv,MOCK_RESPONSE:JSON.stringify({...recovered,code:40041})})
  const recoveredBody = JSON.parse(readFileSync(join(root,'callback.json'),'utf8'))
  assert.equal(recoveredBody.externalRunId, 'build:github-100-2')
  for (const forbidden of ['manualApprovalId','approvalActor','approvedAt','buildExternalRunId'])
    assert.equal(recoveredBody[forbidden], undefined, `recovery must not send ${forbidden}`)
  for (const [key,value] of [['externalRunId','build:github-100-1'],['commitSha','c'.repeat(40)],['imageUri',`${image}:latest`],['deployStatus','DEPLOYING'],['status','FAILED']]) {
    run(`recovery rejects response ${key}`, resend, false, {...buildEnv,MOCK_RESPONSE:JSON.stringify({code:0,data:{...recovered.data,[key]:value}})})
  }
  run('recovery rejects HTTP failure', resend, false, {...buildEnv,MOCK_CURL_STATUS:'22'})
  writeFileSync(join(root,'recovered-release/release.json'), JSON.stringify({...evidence,runAttempt:'1'}))
  rmSync(join(root,'callback.json'))
  run('recovery rejects stale artifact before callback', resend, false, buildEnv)
  assert.equal(existsSync(join(root,'callback.json')), false)

  // Normal completion must validate the acknowledgement, not merely HTTP 200.
  const finished = 'build_finished::Report build state without deployment authority'
  const started = 'build_started::Report build state without deployment authority'
  const reply = {code:0,data:{externalRunId:'build:github-200-1',commitSha:sha,status:'SUCCEEDED',testStatus:'PASSED',securityStatus:'PASSED',imageUri:`${image}@${digest}`}}
  const reportEnv = {...buildEnv,TEST_RESULT:'success',SECURITY_RESULT:'success',BUILD_RESULT:'success',DIGEST:digest,MOCK_RESPONSE:JSON.stringify(reply)}
  run('completed build acknowledgement', finished, true, reportEnv)
  const successBody = JSON.parse(readFileSync(join(root,'callback.json'),'utf8'))
  assert.equal(successBody.status, 'SUCCEEDED'); assert.equal(successBody.imageUri,`${image}@${digest}`)
  assert.equal(successBody.manualApprovalId, undefined)
  run('reject HTTP 200 application error', finished, false, {...reportEnv,MOCK_RESPONSE:JSON.stringify({...reply,code:40041})})
  run('reject non-JSON proxy response', finished, false, {...reportEnv,MOCK_RESPONSE:'<html>gateway</html>'})
  for (const [key,value] of [['externalRunId','build:github-201-1'],['commitSha','c'.repeat(40)],['status','RUNNING'],['imageUri',`${image}:latest`],['securityStatus','FAILED']])
    run(`completion rejects acknowledgement ${key}`, finished, false, {...reportEnv,MOCK_RESPONSE:JSON.stringify({code:0,data:{...reply.data,[key]:value}})})
  run('completion rejects missing digest', finished, false, {...reportEnv,DIGEST:''})
  for (const [field,result,status] of [['TEST_RESULT','failure','FAILED'],['SECURITY_RESULT','failure','FAILED'],['BUILD_RESULT','cancelled','CANCELLED']]) {
    run(`report ${field} ${result}`, finished, true, {...reportEnv,[field]:result,MOCK_RESPONSE:JSON.stringify({code:0,data:{...reply.data,status,imageUri:''}})})
    const failed = JSON.parse(readFileSync(join(root,'callback.json'),'utf8'))
    assert.equal(failed.status,status); assert.equal(failed.imageUri,'')
  }
  run('late start accepts terminal identity without regression', started, true, {...reportEnv,TEST_RESULT:'pending',SECURITY_RESULT:'pending',BUILD_RESULT:'pending',DIGEST:''})
  assert.equal(JSON.parse(readFileSync(join(root,'callback.json'),'utf8')).status,'RUNNING')
  for (const job of ['quality','security','images'])
    assert(workflow.includes(`  ${job}:\n    if: github.event_name != 'workflow_dispatch'`))
  assert(!workflow.includes('report-and-deploy:'))
  const platformWorkflow = workflow
  for (const runtime of ['NODE','JAVA','GO','DOCKER']) {
    for (const previewEnabled of [false,true]) {
      workflow = generateWorkflow({provider:'GITHUB',runtime,branch:'main',imageRepository:image,
        applicationCode:'platform-web',callbackUrl:'https://invalid.example/callback',previewEnabled,
        previewCallbackUrl:'https://invalid.example/preview',previewUrlTemplate:'https://pr-{number}.example',previewTtlHours:72}).content
      const prefix = `${runtime} preview=${previewEnabled}`
      run(`${prefix} completion accepted`, finished, true, reportEnv)
      run(`${prefix} completion rejects wrong digest`, finished, false, {...reportEnv,MOCK_RESPONSE:JSON.stringify({code:0,data:{...reply.data,imageUri:`${image}:latest`}})})
      run(`${prefix} completion rejects application error`, finished, false, {...reportEnv,MOCK_RESPONSE:JSON.stringify({...reply,code:40041})})
      run(`${prefix} late RUNNING acknowledgement`, started, true, {...reportEnv,TEST_RESULT:'pending',SECURITY_RESULT:'pending',BUILD_RESULT:'pending',DIGEST:''})
    }
  }
  workflow = platformWorkflow
  console.log(`${count} shell cases passed; HMAC and no-rebuild guards checked. No remote workflow run.`)
} finally {
  // Only this test's newly-created synthetic fixture directory is removed.
  rmSync(root, {recursive:true, force:true})
}
