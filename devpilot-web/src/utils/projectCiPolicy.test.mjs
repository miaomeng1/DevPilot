import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../../../.github/workflows/cicd.yml', import.meta.url), 'utf8')

test('project CI grants package writes only to gated image jobs, not test or security jobs', () => {
  const rootPermissions = workflow.match(/^permissions:\n((?:[ \t].*\n)+)/m)?.[1]
  assert.equal(rootPermissions, '  contents: read\n')
  const jobs = workflow.split('\njobs:\n')[1].split(/^  (?=[a-z_-]+:\n)/m).filter(block => /^[a-z_-]+:\n/.test(block))
  assert.deepEqual(jobs.map(block => block.split(':')[0]), ['quality', 'security', 'images', 'build_started', 'build_finished', 'recover_build', 'production'])
  for (const job of jobs) {
    const permissions = job.match(/^    permissions:\n((?:      .*\n)+)/m)?.[1]
    if (job.startsWith('images:')) {
      assert.equal(permissions, '      contents: read\n      packages: write\n')
      assert.match(job, /^    needs: \[quality, security\]$/m)
      assert.match(job, /push: \$\{\{ github.event_name != 'pull_request' \}\}/)
    } else if (job.startsWith('recover_build:')) {
      assert.equal(permissions, '      actions: read\n')
      assert.doesNotMatch(job, /DEVPILOT_CICD_CALLBACK_SECRET/)
      assert.match(job, /^    environment: build-status-platform-web$/m)
    } else if (job.startsWith('production:')) {
      assert.equal(permissions, '      contents: read\n      actions: read\n')
      assert.match(job, /inputs\.manual_approval_id/)
      assert.match(job, /^    environment: production$/m)
      assert.doesNotMatch(job, /docker\/build-push-action|actions\/checkout/)
    } else if (/^build_(started|finished):/.test(job)) {
      assert.match(job, /^    permissions: \{\}$/m)
      assert.doesNotMatch(job, /DEVPILOT_CICD_CALLBACK_SECRET/)
    } else assert.equal(permissions, undefined, 'Checks must inherit read-only permissions')
  }
  assert.equal((workflow.match(/packages: write/g) || []).length, 1)
  assert.doesNotMatch(workflow, /security-events: write|write-all|pull_request_target/)
})

test('every project checkout avoids persisting the token in git configuration', () => {
  const checkouts = workflow.split(/- uses: actions\/checkout@[^\n]+\n/).slice(1)
  assert.equal(checkouts.length, 3, 'Only quality, security and image jobs check out code')
  for (const checkout of checkouts) {
    const step = checkout.split(/\n      - /)[0]
    assert.match(step, /^          persist-credentials: false$/m)
  }
})
