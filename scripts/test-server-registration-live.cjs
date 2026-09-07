// Explicit isolated acceptance: create one server via real UI; no rotation or deployment.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const { spawn } = require('node:child_process')
const { readFileSync, mkdtempSync, writeFileSync, existsSync } = require('node:fs')
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
  assert.equal(process.env.DEVPILOT_LIVE_SERVER_CREATE, 'true');
  const lab = process.env.DEVPILOT_SERVER_LAB;
  const name = process.env.DEVPILOT_SERVER_NAME;
  const output = process.env.DEVPILOT_SERVER_EVIDENCE;
  assert.match(lab || '', /^devpilot-[a-z0-9-]+$/);
  assert.match(name || '', /^onboarding-[a-z0-9-]+$/);
  assert.ok(output?.startsWith('/tmp/devpilot-') && existsSync(output), 'Use an existing private evidence directory');
  assert.equal(existsSync(join(output, 'server-created.json')), false, 'Existing credential: reuse it, never create again');
  const identity = JSON.parse(await run('docker', ['exec', lab, 'cat', '/opt/stability-evidence/admin.json']));
  const origin = 'http://127.0.0.1:19995', errors = [], mutations = [];
  let created = null;
  const browser = await chromium.launch({ headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, ...(existsSync(join(output, 'server-browser.json')) ? {storageState:join(output, 'server-browser.json')} : {}) })
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url())
      if (url.origin !== origin) { errors.push('Unexpected browser destination'); return route.abort() }
      if (!['GET', 'HEAD'].includes(request.method())) {
        mutations.push(request.method() + ' ' + url.pathname)
        if (request.method() !== 'POST' || !/^\/api\/(auth\/(login|refresh|logout)|servers)$/.test(url.pathname)) {
          errors.push('Forbidden mutation'); return route.abort()
        }
      }
      const args = ['exec', '-i', lab, 'curl', '-sS', '-i', '--connect-timeout', '5', '--max-time', '120', '-X', request.method()]
      for (const name of ['authorization', 'content-type', 'cookie']) {
        if (request.headers()[name]) args.push('-H', name + ': ' + request.headers()[name])
      }
      const body = request.postDataBuffer()
      if (url.pathname === '/api/servers' && request.method() === 'POST') {
        assert.equal(JSON.parse(body).name, name);
        writeFileSync(join(output, 'server-request.json'), body, {mode:0o600});
        writeFileSync(join(output, 'server-browser.json'), JSON.stringify(await context.storageState()), {mode:0o600});
      }
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
        if (url.pathname === '/api/servers' && request.method() === 'POST') {
          const result = JSON.parse(payload);
          assert.equal(result.code, 0);
          created = result.data;
          writeFileSync(join(output, 'server-created.json'), JSON.stringify(created), {mode:0o600});
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
    await page.goto(origin + '/servers');
    await page.getByRole('button', {name:/Add server/}).waitFor();
    if (!existsSync(join(output, 'server-request.json'))) {
      assert.equal(await page.locator('tbody tr').filter({hasText:name}).count(), 0, 'Existing server must be reviewed first');
    }
    await page.getByRole('button', {name:/Add server/}).click();
    await page.getByPlaceholder('prod-server-01').fill(name);
    await page.getByRole('button', {name:/Generate Agent token/}).click();
    await page.getByRole('heading', {name:'Install DevPilot Agent',exact:true}).waitFor();
    assert.ok(created?.server?.id && created.agentToken);
    await page.getByRole('button', {name:'Done',exact:true}).click();
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({serverId:created.server.id,name,scope:'One server registered through UI; Agent not started',output}));
  } finally { await browser.close() }
}
main().catch(() => { console.error('Server registration failed; inspect private recovery evidence before retrying.'); process.exitCode = 1 })
