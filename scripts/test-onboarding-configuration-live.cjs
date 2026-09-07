// Opt-in live onboarding configuration through real UI. Stops at PR; never approves or releases.
// The supplied lab must be a private acceptance instance, with existing admin identity.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const { spawn } = require('node:child_process')
const { readFileSync, mkdtempSync, writeFileSync, existsSync } = require('node:fs')
const { join } = require('node:path')
const { tmpdir } = require('node:os')
const assert = require('node:assert/strict')
let phase = 'initialization';

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
  assert.equal(process.env.DEVPILOT_LIVE_ONBOARDING, 'true', 'Explicit live inspection opt-in required')
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
  const output = process.env.DEVPILOT_ONBOARDING_EVIDENCE;
  assert.ok(output?.startsWith('/tmp/devpilot-') && existsSync(output));
  assert.equal(existsSync(join(output, 'onboarding-application.json')), false, 'Resume saved app; never create twice');
  const target = JSON.parse(readFileSync(join(output, 'server-created.json')));
  const applicationCode = 'onboarding-acceptance';
  const callback = process.env.DEVPILOT_ONBOARDING_CALLBACK;
  assert.match(callback || '', /^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/);
  const registryResponse = await fetch(provider.replace('host.docker.internal', '127.0.0.1') + '/api/registry.all', {headers:{'x-api-key':key}, signal:AbortSignal.timeout(15000)});
  assert.equal(registryResponse.status, 200);
  const registry = (await registryResponse.json()).find(row => row.registryId === process.env.DEVPILOT_ONBOARDING_REGISTRY_ID);
  assert.ok(registry?.password && registry.username);
  let applicationId = null, latestJob = null, targetFresh = false, existingApplication = false;
  const browser = await chromium.launch({ headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url())
      if (url.origin !== origin) { errors.push('Unexpected browser destination'); return route.abort() }
      if (!['GET', 'HEAD'].includes(request.method())) {
        mutations.push(request.method() + ' ' + url.pathname)
        if (request.method() !== 'POST' || !(
          /^\/api\/(auth\/(login|refresh|logout)|cicd\/onboarding\/inspect)$/.test(url.pathname) ||
          url.pathname === '/api/applications' ||
          (applicationId && [`/api/cicd/onboarding/${applicationId}`, `/api/cicd/onboarding/${applicationId}/advance`].includes(url.pathname)))) {
          errors.push('Forbidden mutation'); return route.abort()
        }
      }
      const args = ['exec', '-i', lab, 'curl', '-sS', '-i', '--connect-timeout', '5', '--max-time', '120', '-X', request.method()]
      for (const name of ['authorization', 'content-type', 'cookie']) {
        if (request.headers()[name]) args.push('-H', name + ': ' + request.headers()[name])
      }
      const body = request.postDataBuffer()
      if (url.pathname === '/api/applications' && request.method() === 'POST') {
        const payload = JSON.parse(body);
        assert.equal(payload.code, applicationCode); assert.equal(payload.serverId, target.server.id);
        assert.equal(existingApplication, false); assert.equal(targetFresh, true);
        writeFileSync(join(output, 'application-request.json'), body, {mode:0o600});
        writeFileSync(join(output, 'onboarding-browser.json'), JSON.stringify(await context.storageState()), {mode:0o600});
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
        if (url.pathname === '/api/cicd/onboarding/inspect') {
          const result = JSON.parse(payload)
          inspections.push({ status, code: result.code, data: result.data })
        }
        if (url.pathname === '/api/servers') {
          const row = JSON.parse(payload).data.find(row => row.id === target.server.id);
          targetFresh = row?.status === 'ONLINE' && Math.abs(Date.now() - Date.parse(row.lastHeartbeat + 'Z')) < 30000;
        }
        if (url.pathname === '/api/applications') {
          const result = JSON.parse(payload);
          if (request.method() === 'GET') existingApplication = result.data.some(row => row.code === applicationCode);
          else if (result.code === 0) {
            applicationId = result.data.id;
            writeFileSync(join(output, 'onboarding-application.json'), JSON.stringify(result.data), {mode:0o600});
          }
        }
        if (applicationId && url.pathname.startsWith('/api/cicd/onboarding/' + applicationId)) {
          const result = JSON.parse(payload);
          if (result.code === 0) { latestJob = result.data; writeFileSync(join(output, 'onboarding-job.json'), JSON.stringify(latestJob), {mode:0o600}); }
        }
        await route.fulfill({ status, headers, body: payload })
      } catch { errors.push('Bridge failure'); await route.abort() }
    })
    const page = await context.newPage()
    page.on('pageerror', () => errors.push('Browser runtime error'))
    await page.goto(origin + '/login')
    phase = 'login';
    await page.locator('input[autocomplete="username"]').fill(identity.username)
    await page.locator('input[autocomplete="current-password"]').fill(identity.password)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL(origin + '/')
    await page.goto(origin + '/cicd/onboarding')
    phase = 'repository inspection';
    await page.getByLabel('仓库地址', { exact: true }).fill(repository)
    await page.getByLabel('仓库 Token', { exact: false }).fill(repositoryToken)
    await page.getByLabel('平台地址', { exact: true }).fill(provider)
    await page.getByLabel('部署平台 API Key', { exact: false }).fill(key)
    await page.getByRole('button', { name: '检查授权与识别项目', exact: true }).click()
    await page.getByRole('button', { name: '修改连接', exact: true }).waitFor({ timeout: 125000 })
    assert.equal(inspections.length, 1)
    const result = inspections[0]
    phase = 'inspection assertions';
    assert.equal(result.status, 200); assert.equal(result.code, 0)
    assert.ok(result.data.repository.branch)
    assert.ok(result.data.provider.servers.length > 0)
    assert.equal(await page.getByRole('checkbox', { name: /我已在 Dokploy 核对/ }).isChecked(), false)
    assert.equal(await page.getByRole('checkbox', { name: /我确认目标服务器和端口/ }).isChecked(), false)
    assert.equal(await page.getByRole('button', { name: '自动完成接入配置 →', exact: true }).isDisabled(), true)
    await page.getByLabel('应用名称', {exact:true}).fill('V38 全新向导验收');
    phase = 'application and server fields';
    await page.getByLabel('应用编码', {exact:true}).fill(applicationCode);
    await page.locator('label').filter({hasText:/^Agent 所在业务服务器/}).locator('select').selectOption(target.server.id);
    await page.getByLabel('服务器发布端口 Published', {exact:false}).fill('18089');
    phase = 'callback and registry fields';
    await page.getByLabel('DevPilot 公网 HTTPS 根地址', {exact:false}).fill(callback);
    await page.getByLabel('Registry 拉取用户名（私有镜像）', {exact:true}).fill(registry.username);
    await page.getByLabel('Registry 拉取密码 / Token', {exact:false}).fill(registry.password);
    await page.getByRole('checkbox', {name:/我已在 Dokploy 核对/}).check();
    phase = 'configuration consent';
    await page.getByRole('checkbox', {name:/我确认目标服务器和端口/}).check();
    await page.getByRole('button', {name:'自动完成接入配置 →',exact:true}).click();
    phase = 'waiting for saved job';
    const deadline = Date.now() + 240000;
    while (Date.now() < deadline && !['FAILED','AWAITING_MERGE'].includes(latestJob?.status)) await page.waitForTimeout(500);
    assert.ok(latestJob && ['FAILED','AWAITING_MERGE'].includes(latestJob.status), 'Inspect saved job before retrying');
    await page.screenshot({path:join(output,'onboarding-result.png'),fullPage:true,mask:[page.locator('input[type="password"]')]});
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({applicationId,jobId:latestJob.id,status:latestJob.status,stage:latestJob.stage,changeUrl:latestJob.changeUrl,error:latestJob.errorMessage,output}));
  } finally { await browser.close() }
}
main().catch(() => { console.error('Live onboarding stopped at ' + phase + '; inspect saved application/job before retrying. Details suppressed.'); process.exitCode = 1 })
