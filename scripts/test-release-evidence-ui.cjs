// Read-only acceptance: candidate UI from restore lab, real historical data from
// source lab, then callback evidence from restore lab. No deployment writes.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const { spawn } = require('node:child_process')
const { mkdtempSync, readFileSync, realpathSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join, extname, sep } = require('node:path')
const assert = require('node:assert/strict')

function command(args, input) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, { stdio: ['pipe', 'pipe', 'pipe'] })
    const chunks = []
    child.stdout.on('data', chunk => chunks.push(chunk))
    child.stderr.resume() // Never print credentials, cookies or private payloads.
    child.on('error', reject)
    child.on('close', code => code === 0 ? resolve(Buffer.concat(chunks)) : reject(Error(`Transport failed (${code})`)))
    child.stdin.end(input)
  })
}

async function main() {
  const origin = 'http://127.0.0.1:19998'
  const uiLab = 'devpilot-stability-restore-lab'
  const localDist = process.env.DEVPILOT_TEST_LOCAL_DIST ? realpathSync(join(__dirname, '../devpilot-web/dist')) : null
  const output = mkdtempSync(join(tmpdir(), 'devpilot-release-evidence-ui-'))
  const browser = await chromium.launch({ headless: true })
  try {
    for (const apiLab of ['devpilot-stability-lab', uiLab]) {
      const identity = JSON.parse(await command(['exec', apiLab, 'cat', '/opt/stability-evidence/admin.json']))
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      const errors = []
      await context.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url())
        if (url.origin !== origin) { errors.push('Unexpected external request'); return route.abort() }
        if (localDist && !url.pathname.startsWith('/api/')) {
          const file = realpathSync(join(localDist, url.pathname.startsWith('/assets/') ? url.pathname.slice(1) : 'index.html'))
          assert.ok(file.startsWith(localDist + sep))
          return route.fulfill({ status: 200, contentType: { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' }[extname(file)] || 'application/octet-stream', body: readFileSync(file) })
        }
        if (!['GET', 'HEAD'].includes(request.method()) && !/^\/api\/auth\/(login|refresh|logout)$/.test(url.pathname)) {
          errors.push(`Unexpected mutation: ${request.method()} ${url.pathname}`)
          return route.abort()
        }
        const lab = url.pathname.startsWith('/api/') ? apiLab : uiLab
        const args = ['exec', '-i', lab, 'curl', '-sS', '-i', '--connect-timeout', '5', '--max-time', '30', '-X', request.method()]
        for (const name of ['authorization', 'content-type', 'cookie']) {
          if (request.headers()[name]) args.push('-H', `${name}: ${request.headers()[name]}`)
        }
        const body = request.postDataBuffer()
        if (body) args.push('--data-binary', '@-')
        args.push('http://127.0.0.1:18081' + url.pathname + url.search)
        try {
          const response = await command(args, body), split = response.indexOf('\r\n\r\n')
          assert.ok(split > 0)
          const lines = response.subarray(0, split).toString().split('\r\n')
          const status = Number(lines.shift().split(' ')[1]), headers = {}
          for (const line of lines) {
            const colon = line.indexOf(':'); if (colon < 0) continue
            const name = line.slice(0, colon).toLowerCase()
            if (!['transfer-encoding', 'content-length', 'connection'].includes(name)) headers[name] = line.slice(colon + 1).trim()
          }
          await route.fulfill({ status, headers, body: response.subarray(split + 4) })
        } catch { errors.push(`Transport failure: ${url.pathname}`); await route.abort() }
      })
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      await page.goto(origin + '/login')
      await page.locator('input[autocomplete="username"]').fill(identity.username)
      await page.locator('input[autocomplete="current-password"]').fill(identity.password)
      await page.locator('button[type="submit"]').click()
      await page.waitForURL(origin + '/')
      await page.goto(origin + '/cicd')
      const selector = page.locator('.app-selector select')
      const name = apiLab === uiLab ? 'Approval persistence fixture' : 'GitHub V36 全链路验收'
      await selector.locator('option').filter({ hasText: name }).waitFor({ state: 'attached' })
      const id = await selector.locator('option').evaluateAll((options, name) => options.find(o => o.textContent.includes(name))?.value, name)
      assert.ok(id)
      await selector.selectOption(id)
      if (apiLab !== uiLab) {
        const guides = page.locator('.failure-guide')
        await guides.filter({ hasText: '镜像拉取 Image pull' }).first().waitFor()
        await guides.filter({ hasText: '再次打标签' }).waitFor()
        assert.equal(await guides.count(), 2, 'Two actual failed attempts, not the healthy deployment')
        assert.equal(await page.locator('.deployment-table tbody tr').filter({ has: page.locator('.rollback-action') }).locator('.failure-guide').count(), 0)
        for (const guide of await guides.all()) assert.match(await guide.innerText(), /不能假定旧版本仍在运行/)
        await page.locator('.deployment-panel').scrollIntoViewIfNeeded()
        // Scroll the table, not the whole page, to make evidence readable.
        await guides.first().scrollIntoViewIfNeeded()
        await guides.first().screenshot({ path: join(output, 'real-failures-desktop.png') })
        await page.locator('.deployment-log summary').first().click()
        await page.locator('.deployment-log[open] pre').waitFor()
        await page.locator('.deployment-log[open] summary').click()
        await page.setViewportSize({ width: 390, height: 844 })
        await guides.first().scrollIntoViewIfNeeded()
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile page must not overflow')
        for (const guide of await guides.all()) {
          assert.ok(await guide.evaluate(el => el.getBoundingClientRect().width <= innerWidth - 60), 'Guidance must fit mobile width')
          assert.equal(await guide.locator('small').evaluate(el => getComputedStyle(el).whiteSpace), 'normal', 'Recovery warning must wrap, never ellipsize')
        }
        await guides.last().screenshot({ path: join(output, 'real-failures-mobile.png') })
      } else {
        const checks = page.locator('.readiness-checks article')
        await checks.filter({ hasText: '构建签名回调' }).waitFor()
        assert.ok(await page.locator('.delivery-flow li').evaluateAll(items => items.every(item => {
          const detail = item.querySelector('small')
          return detail.getBoundingClientRect().right <= item.getBoundingClientRect().right && detail.title === detail.textContent
        })), 'Long digest must stay within its step and retain full hover text')
        assert.match(await checks.filter({ hasText: '构建签名回调' }).getAttribute('class'), /pass/)
        assert.match(await checks.filter({ has: page.getByText('发布签名回调', { exact: true }) }).getAttribute('class'), /warn/)
        await page.locator('.readiness-checks').scrollIntoViewIfNeeded()
        await page.screenshot({ path: join(output, 'v37-callback-evidence.png') })
        await page.goto(origin + '/setup')
        await page.getByText('只有在线状态和有效期内的心跳同时满足', { exact: false }).waitFor()
        assert.equal(await page.getByLabel('API Key', { exact: true }).inputValue(), '')
        await page.screenshot({ path: join(output, 'running-setup-status.png'), fullPage: true })
      }
      assert.deepEqual(errors, [])
      await context.close()
    }
    console.log(`PASS: ${localDist ? 'local production build' : 'running candidate UI'} renders actual failed history, keeps healthy history separate, opens logs without writes, fits mobile, and separates actual callback evidence.`)
    console.log('Screenshots: ' + output)
  } finally { await browser.close() }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
