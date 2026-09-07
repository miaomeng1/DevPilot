// Run with: node --experimental-strip-types scripts/test-github-workflow-lint.mjs
// Requires Go (downloads the pinned linter into Go's cache) or DEVPILOT_ACTIONLINT.
import { spawnSync } from 'node:child_process'
import { generateWorkflow } from '../devpilot-web/src/utils/workflowTemplates.ts'

const binary = process.env.DEVPILOT_ACTIONLINT
const platform = spawnSync(binary || 'go', [
  ...(binary ? [] : ['run', 'github.com/rhysd/actionlint/cmd/actionlint@v1.7.7']),
  '-shellcheck=', '-pyflakes=', '.github/workflows/cicd.yml',
], { encoding: 'utf8', timeout: 120000 })
if (platform.error) throw platform.error
if (platform.status !== 0) {
  console.error(platform.stdout + platform.stderr)
  process.exit(1)
}
console.log('PASS: platform CI/CD workflow')
for (const runtime of ['NODE', 'JAVA', 'GO', 'DOCKER']) {
  for (const previewEnabled of [false, true]) {
    const workflow = generateWorkflow({ provider: 'GITHUB', runtime, branch: 'main',
      imageRepository: 'ghcr.io/example/blog', applicationCode: 'blog',
      callbackUrl: 'https://ops.example.com/api/cicd/webhooks/blog', previewEnabled,
      previewCallbackUrl: 'https://ops.example.com/api/cicd/previews/blog',
      previewUrlTemplate: 'https://pr-{number}.example.com', previewTtlHours: 72 })
    const result = spawnSync(binary || 'go', [
      ...(binary ? [] : ['run', 'github.com/rhysd/actionlint/cmd/actionlint@v1.7.7']),
      '-shellcheck=', '-pyflakes=', '-',
    ], { input: workflow.content, encoding: 'utf8', timeout: 120000 })
    if (result.error) throw result.error
    if (result.status !== 0) {
      console.error(`FAIL: ${runtime}, preview=${previewEnabled}\n${result.stdout}${result.stderr}`)
      process.exit(1)
    }
    console.log(`PASS: GitHub workflow ${runtime}, preview=${previewEnabled}`)
  }
}
console.log('YAML/Actions semantics checked; external shellcheck/pyflakes not run. No workflow dispatched.')
