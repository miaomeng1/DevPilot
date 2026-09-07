// Renders the production build against entirely synthetic, intercepted responses.
// No server, real credential, or external API is used; this is not onboarding E2E.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const { readFileSync, existsSync, mkdtempSync } = require('node:fs')
const { resolve, join, extname } = require('node:path')
const { tmpdir } = require('node:os')

async function main() {
  const dist = resolve(__dirname, '../devpilot-web/dist')
  assert(existsSync(join(dist, 'index.html')), 'Build Web first')
  const output = mkdtempSync(join(tmpdir(), 'devpilot-setup-status-ui-'))
  const origin = 'http://devpilot-setup-fixture.invalid'
  const state = {publicUrl:'https://ops.example',providerUrl:'https://deploy.example',providerTokenConfigured:true,
    serverId:'101',publicUrlStatus:'MANUAL_REQUIRED',providerStatus:'MANUAL_REQUIRED',serverStatus:'VERIFIED',
    agentStatus:'MANUAL_REQUIRED',providerVerifiedAt:'2036-01-01T00:00:00',lastHeartbeat:'2036-01-01T00:00:00',
    providerError:null,revision:'synthetic-revision'}
  const unexpected = [], writes = [], errors = []
  const browser = await chromium.launch({headless:true})
  try {
    const context = await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'})
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url())
      if (url.origin !== origin) { unexpected.push('external origin'); return route.abort() }
      let data
      if (url.pathname === '/api/auth/refresh' && request.method() === 'POST') {
        data = {accessToken:'synthetic-browser-session',user:{id:'1',username:'fixture-admin',displayName:'Fixture administrator',roles:['ADMIN']}}
      } else if (url.pathname.startsWith('/api/')) {
        if (request.method() !== 'GET') { writes.push(url.pathname); return route.abort() }
        if (url.pathname === '/api/setup') data = {...state}
        else if (url.pathname === '/api/servers') data = [{id:'101',name:'fixture-server',status:'ONLINE',ip:'192.0.2.10'}]
        else if (url.pathname === '/api/system/public-settings') data = {systemName:'DevPilot',logoUrl:null,defaultTheme:'LIGHT',logDefaultLines:100}
        else if (url.pathname === '/api/alerts/summary') data = {active:0,critical:0}
        else { unexpected.push(url.pathname); return route.abort() }
      } else {
        const file = url.pathname === '/setup' ? join(dist,'index.html') : resolve(dist, '.' + url.pathname)
        if (!file.startsWith(dist + '/') || !existsSync(file)) { unexpected.push(url.pathname); return route.abort() }
        const types = {'.html':'text/html','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'}
        return route.fulfill({contentType:types[extname(file)] || 'application/octet-stream',body:readFileSync(file)})
      }
      return route.fulfill({json:{code:0,message:'ok',data}})
    })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(origin + '/setup')
    const provider = page.locator('.setup-page form > section').filter({has:page.getByRole('heading',{name:/03 · Dokploy/})})
    const agent = page.locator('.setup-page form > section').filter({has:page.getByRole('heading',{name:/05 · Agent/})})
    await agent.getByText('需要人工确认',{exact:true}).waitFor()
    assert(await provider.getByRole('status').isVisible())
    assert(await agent.getByRole('status').isVisible())
    assert.equal(await agent.getByText('验证通过',{exact:true}).count(),0)
    assert.equal(await page.getByLabel('API Key',{exact:true}).inputValue(),'')
    await page.screenshot({path:join(output,'future-evidence-desktop.png'),fullPage:true})
    await page.setViewportSize({width:390,height:844})
    assert(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth),'No mobile overflow')
    await page.screenshot({path:join(output,'future-evidence-mobile.png'),fullPage:true})
    for (const [status,label] of [['FAILED','验证失败 / 连接异常'],['SAVED_UNVERIFIED','已保存 · 尚未验证'],['NOT_CONFIGURED','未配置'],['VERIFIED','验证通过']]) {
      state.agentStatus=status
      await page.getByRole('button',{name:'刷新状态',exact:true}).click()
      await agent.getByText(label,{exact:true}).waitFor()
      assert.equal(await agent.getByRole('status').count(),0)
    }
    assert.deepEqual(writes,[],'Refreshing must not save, verify, create, or deploy')
    assert.deepEqual(unexpected,[])
    assert.deepEqual(errors,[])
    console.log(JSON.stringify({passed:true,scenarios:5,output,scope:'Production Web rendering with synthetic API only; no live onboarding'}))
  } finally { await browser.close() }
}
main().catch(error=>{console.error(error);process.exitCode=1})
