// Browser acceptance of the running isolated Linux installation. No listening
// host port: browser requests are transported through docker exec + curl.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const { spawn } = require('node:child_process')
const { mkdtempSync, readFileSync, realpathSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join, resolve, extname, sep } = require('node:path')
const assert = require('node:assert/strict')

function command(args, input) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, { stdio: ['pipe', 'pipe', 'pipe'] })
    const chunks = []
    child.stdout.on('data', chunk => chunks.push(chunk))
    child.stderr.resume() // Do not print URLs, payloads, cookies or credentials.
    child.on('error', reject)
    child.on('close', code => code === 0 ? resolve(Buffer.concat(chunks)) : reject(new Error(`Isolated transport failed (${code})`)))
    child.stdin.end(input)
  })
}

async function within(promise, label, timeout = 30000) {
  let timer
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), timeout)
    })])
  } finally { clearTimeout(timer) }
}

async function main() {
  const origin = 'http://127.0.0.1:19999'
  const lab = 'devpilot-stability-lab'
  const localDist = process.env.DEVPILOT_TEST_LOCAL_DIST ? realpathSync(resolve(__dirname, '../devpilot-web/dist')) : null
  const faults = process.env.DEVPILOT_TEST_REFRESH_FAILURE === 'true'
  assert.ok(!faults || localDist || process.env.DEVPILOT_TEST_RUNNING_IMAGE_FAULTS === 'true',
    'Fault test requires local production UI or explicit running-image fault mode')
  let failure = ''
  let delayed = null
  let releaseDelayed = () => {}
  const identity = JSON.parse(await command(['exec', lab, 'cat', '/opt/stability-evidence/admin.json']))
  const output = mkdtempSync(join(tmpdir(), 'devpilot-observation-ui-'))
  const browser = await chromium.launch({ headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    const errors = []
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url())
      if (url.origin !== origin) return route.abort()
      const delay = delayed?.path === url.pathname ? delayed : null
      if (delay) delayed = null
      if (failure === 'dashboard' && url.pathname === '/api/dashboard') return route.abort('internetdisconnected')
      if (failure === 'configuration' && /^\/api\/cicd\/configurations\//.test(url.pathname)) {
        return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 50300, message: 'Injected test outage', data: null }) })
      }
      if (localDist && !url.pathname.startsWith('/api/')) {
        const relative = url.pathname.startsWith('/assets/') ? url.pathname.slice(1) : 'index.html'
        const file = realpathSync(join(localDist, relative))
        assert.ok(file.startsWith(localDist + sep))
        const contentType = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' }[extname(file)] || 'application/octet-stream'
        return route.fulfill({ status: 200, contentType, body: readFileSync(file) })
      }
      // Only login/refresh/logout and reads are permitted in this UI test.
      if (!['GET', 'HEAD'].includes(request.method()) && !/^\/api\/auth\/(login|refresh|logout)$/.test(url.pathname)) {
        errors.push(`Unexpected mutation: ${request.method()} ${url.pathname}`)
        return route.abort()
      }
      const args = ['exec', '-i', lab, 'curl', '-sS', '-i', '--connect-timeout', '5', '--max-time', '30', '-X', request.method()]
      for (const name of ['authorization', 'content-type', 'cookie']) {
        const value = request.headers()[name]
        if (value) args.push('-H', `${name}: ${value}`)
      }
      const body = request.postDataBuffer()
      if (body) args.push('--data-binary', '@-')
      args.push('http://127.0.0.1:18081' + url.pathname + url.search)
      try {
        const response = await command(args, body)
        const split = response.indexOf('\r\n\r\n')
        assert.ok(split > 0)
        const lines = response.subarray(0, split).toString().split('\r\n')
        const status = Number(lines.shift().split(' ')[1])
        const headers = {}
        for (const line of lines) {
          const colon = line.indexOf(':')
          if (colon < 0) continue
          const name = line.slice(0, colon).toLowerCase()
          if (['transfer-encoding', 'content-length', 'connection'].includes(name)) continue
          headers[name] = line.slice(colon + 1).trim()
        }
        if (delay) { delay.ready(); await delay.released }
        await route.fulfill({ status, headers, body: response.subarray(split + 4) })
        if (delay) delay.delivered()
      } catch { errors.push(`Transport failure: ${url.pathname}`); await route.abort() }
    })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(origin + '/login')
    await page.locator('input[autocomplete="username"]').fill(identity.username)
    await page.locator('input[autocomplete="current-password"]').fill(identity.password)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL(origin + '/')
    await page.getByText('部分应用健康状态待确认', { exact: true }).waitFor()
    await page.screenshot({ path: join(output, 'dashboard.png'), fullPage: true })
    if (faults) {
      failure = 'dashboard'
      await page.getByRole('button', { name: '6h', exact: true }).click()
      const warning = page.getByRole('status').filter({ hasText: '刷新失败，当前保留的是历史快照' })
      await warning.waitFor()
      await page.getByText('部分应用健康状态待确认', { exact: true }).waitFor()
      await page.getByText('历史快照', { exact: true }).waitFor()
      await page.screenshot({ path: join(output, 'dashboard-refresh-failed.png'), fullPage: true })
      failure = ''
      await page.getByRole('button', { name: '1h', exact: true }).click()
      await warning.waitFor({ state: 'hidden' })
      console.log('Verified: dashboard outage retains data, warns and recovers.')
    }
    await page.getByRole('link', { name: /^核对证据/ }).click()
    await page.getByText('Runtime observation fixture', { exact: true }).first().click()
    await page.getByText('容器采集时间 UTC', { exact: true }).waitFor()
    await page.getByText('最近健康检查 Last health check', { exact: true }).waitFor()
    await page.screenshot({ path: join(output, 'application.png'), fullPage: true })
    await page.getByRole('link', { name: '发布中心 CI/CD', exact: true }).click()
    const selector = page.locator('.app-selector select')
    await selector.locator('option').filter({ hasText: 'Notification fixture' }).waitFor({ state: 'attached' })
    const appId = await selector.locator('option').evaluateAll(options => options.find(option => option.textContent.includes('Notification fixture'))?.value)
    assert.ok(appId)
    await selector.selectOption(appId)
    const filter = page.getByRole('combobox', { name: '流水线状态筛选' })
    await filter.selectOption('FAILED')
    await page.locator('.pipeline-table tbody tr').first().waitFor()
    await page.getByText('build:notification-fixture-1', { exact: true }).waitFor()
    if (faults) {
      failure = 'configuration'
      await page.locator('.pipeline-tools').getByRole('button', { name: '刷新', exact: true }).click()
      const warning = page.getByRole('status').filter({ hasText: '刷新失败，以下为上次成功读取的数据' })
      await warning.waitFor()
      await page.getByText('build:notification-fixture-1', { exact: true }).waitFor()
      assert.equal(await selector.inputValue(), appId)
      await page.screenshot({ path: join(output, 'cicd-config-503.png'), fullPage: true })
      failure = ''
      await page.locator('.pipeline-tools').getByRole('button', { name: '刷新', exact: true }).click()
      await warning.waitFor({ state: 'hidden' })
      console.log('Verified: configuration 503 preserves prior pipeline and recovers.')
    }
    await filter.selectOption('CANCELLED')
    await page.getByText('没有符合筛选条件的流水线记录。', { exact: true }).waitFor()
    await filter.selectOption('UNKNOWN')
    await page.getByText('没有符合筛选条件的流水线记录。', { exact: true }).waitFor()
    await filter.selectOption('ALL')
    await page.getByText('build:notification-fixture-1', { exact: true }).waitFor()
    if (faults) {
      let ready, delivered
      const readyPromise = new Promise(resolve => { ready = resolve })
      const deliveredPromise = new Promise(resolve => { delivered = resolve })
      const released = new Promise(resolve => { releaseDelayed = resolve })
      delayed = { path: `/api/cicd/configurations/${appId}`, ready, delivered, released }
      await page.locator('.pipeline-tools').getByRole('button', { name: '刷新', exact: true }).click()
      await within(readyPromise, 'old application response ready')
      const otherId = await selector.locator('option').evaluateAll(options => options.find(option => option.textContent.includes('Approval persistence fixture'))?.value)
      assert.ok(otherId && otherId !== appId)
      await selector.selectOption(otherId)
      await page.getByText('build:approval-fixture-1', { exact: true }).waitFor()
      releaseDelayed()
      await within(deliveredPromise, 'old application response delivered')
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      assert.equal(await selector.inputValue(), otherId)
      assert.equal(await page.getByText('build:notification-fixture-1', { exact: true }).count(), 0)
      await page.getByText('build:approval-fixture-1', { exact: true }).waitFor()
      await page.screenshot({ path: join(output, 'cicd-late-response.png'), fullPage: true })
      console.log('Verified: delayed old application response cannot overwrite the newly selected application.')
      await selector.selectOption(appId)
      await page.getByText('build:notification-fixture-1', { exact: true }).waitFor()

      await page.getByRole('button', { name: '配置与密钥', exact: true }).click()
      const branch = page.getByLabel('受保护分支 Protected branch', { exact: true })
      const repository = page.getByLabel('代码仓库 Repository URL', { exact: true })
      const savedBranch = await branch.inputValue(), savedRepository = await repository.inputValue()
      const draftBranch = 'draft/unsaved-poll-test'
      const draftRepository = 'https://github.com/example/unsaved-poll-test'
      await branch.fill(draftBranch)
      await repository.fill(draftRepository)
      const pollPaths = [
        `/api/cicd/configurations/${appId}`, `/api/applications/${appId}`, '/api/cicd/activity',
        ...['runs', 'deployments', 'environment', 'readiness', 'promotion-targets', 'previews']
          .map(suffix => `/api/cicd/applications/${appId}/${suffix}`),
      ]
      // Observe two real timer-driven, fully received batches. No manual reload,
      // fake timer or save request can stand in for background polling here.
      for (let cycle = 0; cycle < 2; cycle++) {
        const responses = await Promise.all(pollPaths.map(path => page.waitForResponse(
          response => new URL(response.url()).pathname === path && response.request().method() === 'GET',
          { timeout: 25000 },
        )))
        for (const response of responses) {
          assert.equal(response.status(), 200)
          assert.equal(await response.finished(), null)
        }
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
        assert.equal(await branch.inputValue(), draftBranch)
        assert.equal(await repository.inputValue(), draftRepository)
        assert.equal(await selector.inputValue(), appId)
        await page.getByText('build:notification-fixture-1', { exact: true }).waitFor()
      }
      await page.screenshot({ path: join(output, 'cicd-unsaved-draft-poll.png'), fullPage: true })
      // Leave only the original, persisted values in this browser session.
      await branch.fill(savedBranch)
      await repository.fill(savedRepository)
      await page.getByRole('button', { name: '收起配置', exact: true }).click()
      console.log('Verified: two actual background poll batches preserve unsaved repository and branch drafts; no save request.')
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: join(output, 'cicd-mobile.png'), fullPage: true })
    assert.deepEqual(errors, [])
    console.log(`Verified: ${localDist ? 'local production UI' : 'running UI image'} with actual MySQL-backed dashboard, observation fields and filters; no business mutations.`)
    console.log('Screenshots: ' + output)
  } finally { releaseDelayed(); await browser.close() }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
