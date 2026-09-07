// Opt-in real API/UI inspection. Never creates applications, Secrets, PRs or deployments.
// The supplied lab must be a private acceptance instance, with existing admin identity.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const { spawn } = require('node:child_process')
const { readFileSync, mkdtempSync, writeFileSync } = require('node:fs')
const { join } = require('node:path')
const { tmpdir } = require('node:os')
const assert = require('node:assert/strict')

function run(program, args, input) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { stdio: ['pipe', 'pipe', 'pipe'] }), chunks = []
    child.stdout.on('data', chunk => chunks.push(chunk))
    child.stderr.resume()
    child.on('error', () => reject(Error('Transport unavailable')))
    child.on('close', code => code === 0 ? resolve(Buffer.concat(chunks)) : reject(Error('Transport failed; details suppressed')))
    child.stdin.end(input)
  })
}

async function main() {
  assert.equal(process.env.DEVPILOT_LIVE_INSPECTION, 'true', 'Explicit live inspection opt-in required')
  const lab = process.env.DEVPILOT_INSPECTION_LAB
  assert.match(lab || '', /^devpilot-[a-z0-9-]+$/)
  const repository = process.env.DEVPILOT_INSPECTION_REPOSITORY
  assert.match(repository || '', /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/)
  const provider = process.env.DEVPILOT_INSPECTION_PROVIDER
  assert.match(provider || '', /^http:\/\/(host\.docker\.internal|127\.0\.0\.1):[0-9]+$/)
  const key = readFileSync(process.env.DEVPILOT_INSPECTION_KEY_FILE, 'utf8').trim()
  const repositoryToken = (await run('gh', ['auth', 'token'])).toString().trim()
  const identity = JSON.parse(await run('docker', ['exec', lab, 'cat', '/opt/stability-evidence/admin.json']))
  const origin = 'http://127.0.0.1:19996', errors = [], inspections = [], mutations = []
  const output = mkdtempSync(join(tmpdir(), 'devpilot-onboarding-live-inspection-'))
  const browser = await chromium.launch({ headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url())
      if (url.origin !== origin) { errors.push('Unexpected browser destination'); return route.abort() }
      if (!['GET', 'HEAD'].includes(request.method())) {
        mutations.push(request.method() + ' ' + url.pathname)
        if (request.method() !== 'POST' || !/^\/api\/(auth\/(login|refresh|logout)|cicd\/onboarding\/inspect)$/.test(url.pathname)) {
          errors.push('Forbidden mutation'); return route.abort()
        }
      }
      const args = ['exec', '-i', lab, 'curl', '-sS', '-i', '--connect-timeout', '5', '--max-time', '120', '-X', request.method()]
      for (const name of ['authorization', 'content-type', 'cookie']) {
        if (request.headers()[name]) args.push('-H', name + ': ' + request.headers()[name])
      }
      const body = request.postDataBuffer()
      if (body) args.push('--data-binary', '@-')
      args.push('http://127.0.0.1:18081' + url.pathname + url.search)
      try {
        const response = await run('docker', args, body), split = response.indexOf('\r\n\r\n')
        assert.ok(split > 0)
        const lines = response.subarray(0, split).toString().split('\r\n')
        const status = Number(lines.shift().split(' ')[1]), headers = {}, payload = response.subarray(split + 4)
        for (const line of lines) {
          const colon = line.indexOf(':'); if (colon < 0) continue
          const name = line.slice(0, colon).toLowerCase()
          if (!['transfer-encoding', 'content-length', 'connection'].includes(name)) headers[name] = line.slice(colon + 1).trim()
        }
        if (url.pathname === '/api/cicd/onboarding/inspect') {
          const result = JSON.parse(payload)
          inspections.push({ status, code: result.code, data: result.data })
        }
        await route.fulfill({ status, headers, body: payload })
      } catch { errors.push('Bridge failure'); await route.abort() }
    })
    const page = await context.newPage()
    page.on('pageerror', () => errors.push('Browser runtime error'))
    await page.goto(origin + '/login')
    await page.locator('input[autocomplete="username"]').fill(identity.username)
    await page.locator('input[autocomplete="current-password"]').fill(identity.password)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL(origin + '/')
    await page.goto(origin + '/cicd/onboarding')
    await page.getByLabel('仓库地址', { exact: true }).fill(repository)
    await page.getByLabel('仓库 Token', { exact: false }).fill(repositoryToken)
    await page.getByLabel('平台地址', { exact: true }).fill(provider)
    await page.getByLabel('部署平台 API Key', { exact: false }).fill(key)
    await page.getByRole('button', { name: '检查授权与识别项目', exact: true }).click()
    await page.getByRole('button', { name: '修改连接', exact: true }).waitFor({ timeout: 125000 })
    assert.equal(inspections.length, 1)
    const result = inspections[0]
    assert.equal(result.status, 200); assert.equal(result.code, 0)
    assert.ok(result.data.repository.branch)
    assert.ok(result.data.provider.servers.length > 0)
    assert.equal(await page.getByRole('checkbox', { name: /我已在 Dokploy 核对/ }).isChecked(), false)
    assert.equal(await page.getByRole('checkbox', { name: /我确认目标服务器和端口/ }).isChecked(), false)
    assert.equal(await page.getByRole('button', { name: '自动完成接入配置 →', exact: true }).isDisabled(), true)
    const storage = await page.evaluate(() => JSON.stringify({ ...localStorage }))
    for (const secret of [key, repositoryToken]) assert.equal(storage.includes(secret), false)
    await page.screenshot({ path: join(output, 'desktop.png'), fullPage: true, mask: [page.locator('input[type="password"]')] })
    await page.setViewportSize({ width: 390, height: 844 })
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow')
    await page.screenshot({ path: join(output, 'mobile.png'), fullPage: true, mask: [page.locator('input[type="password"]')] })
    assert.deepEqual(errors, [])
    assert.equal(mutations.filter(value => !value.startsWith('POST /api/auth/')).length, 1)
    const report = { repository, branch: result.data.repository.branch, runtime: result.data.repository.runtime,
      deploymentTargets: result.data.provider.targets.length, providerServers: result.data.provider.servers.length,
      unconfirmedCreationDisabled: true, secretsAbsentFromLocalStorage: true, mutations, verifiedAt: new Date().toISOString(),
      scope: 'Real rendered UI and backend inspection only; no application creation, PR, Secrets or release.' }
    writeFileSync(join(output, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 })
    console.log(JSON.stringify({ ...report, output }))
  } finally { await browser.close() }
}
main().catch(() => { console.error('Live inspection failed; raw errors and credentials suppressed.'); process.exitCode = 1 })
