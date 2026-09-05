import test from 'node:test'
import assert from 'node:assert/strict'
import { generateWorkflow } from './workflowTemplates.ts'

const base = {
  runtime: 'NODE', branch: 'main', imageRepository: 'ghcr.io/example/blog',
  callbackUrl: 'https://ops.example.com/api/cicd/webhooks/blog', applicationCode: 'blog',
  previewEnabled: false, previewCallbackUrl: '', previewUrlTemplate: '', previewTtlHours: 72,
}

for (const runtime of ['NODE', 'JAVA', 'GO', 'DOCKER']) {
  test(`GitHub ${runtime}: manually releases verified digest without rebuilding`, () => {
    const workflow = generateWorkflow({ ...base, provider: 'GITHUB', runtime })
    assert.match(workflow.content, /if: github.event_name == 'workflow_dispatch'/)
    const production = workflow.content.split('  production:')[1]
    assert.doesNotMatch(production, /needs:|build-push-action|docker build/)
    assert.match(workflow.content, /build_run_id:/)
    assert.match(production, /\.event == "push" and .status == "completed" and .conclusion == "success"/)
    assert.match(production, /\.workflow_id == \$release\[0\].workflow_id/)
    assert.match(production, /\.image == \$image and .runId == \$run and .commit == \$commit/)
    assert.match(production, /IMAGE_URI=\$IMAGE_REPOSITORY@/)
    assert.match(production, /--arg imageDigest "\$IMAGE_DIGEST"/)
    assert.equal(workflow.content.match(/github.event_name != 'workflow_dispatch'/g)?.length, 4)
    assert.match(workflow.content, /Missing DevPilot callback secret/)
    assert.match(workflow.content, /sha-\$\{\{ env.SOURCE_SHA }}/)
    assert.ok(workflow.secrets.includes('DEVPILOT_CICD_CALLBACK_SECRET'))
  })
  test(`GitLab ${runtime}: production confirmation retained`, () => {
    const workflow = generateWorkflow({ ...base, provider: 'GITLAB', runtime })
    assert.match(workflow.content, /when: manual/)
    assert.match(workflow.content, /needs: \[quality, security, image\]/)
    assert.match(workflow.content, /X-DevPilot-Signature/)
  })
}

test('production secret validation does not leak into Preview jobs', () => {
  const workflow = generateWorkflow({ ...base, provider: 'GITHUB', previewEnabled: true,
    previewCallbackUrl: 'https://ops.example.com/api/cicd/webhooks/blog/previews',
    previewUrlTemplate: 'https://pr-{{pr_id}}.example.com' })
  assert.equal(workflow.content.match(/Missing DevPilot callback secret/g)?.length, 1)
  assert.match(workflow.content, /DEVPILOT_PREVIEW_CALLBACK_SECRET/)
})
