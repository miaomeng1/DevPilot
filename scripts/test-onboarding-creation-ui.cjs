// Production Web + synthetic API. Drops the first application creation response.
// Does not connect to GitHub, a deployment provider, or an existing installation.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert=require('node:assert/strict')
const {readFileSync,existsSync}=require('node:fs')
const {resolve,join,extname}=require('node:path')
async function main(){
  // Loopback is a secure browser context, like supported local installations.
  // Every request is still intercepted; no listener or real instance is used.
  const dist=resolve(__dirname,'../devpilot-web/dist'), origin='http://127.0.0.1:19997'
  const requests=[], unexpected=[]
  let tasks=0, advances=0
  const browser=await chromium.launch({headless:true})
  try{
    const context=await browser.newContext({serviceWorkers:'block'})
    await context.route('**/*',async route=>{
      const req=route.request(), url=new URL(req.url()), path=url.pathname, method=req.method()
      if(url.origin!==origin){unexpected.push('external');return route.abort()}
      let data
      if(path==='/api/auth/refresh' && method==='POST') data={accessToken:'synthetic-session',user:{id:'1',username:'fixture',displayName:'Fixture',roles:['ADMIN']}}
      else if(path==='/api/system/public-settings' && method==='GET') data={systemName:'DevPilot',logoUrl:null,defaultTheme:'LIGHT',logDefaultLines:100}
      else if(path==='/api/alerts/summary' && method==='GET') data={active:0,critical:0}
      else if(path==='/api/setup' && method==='GET') data={providerUrl:null,providerTokenConfigured:false,publicUrl:'https://ops.example',revision:'fixture',serverId:'101'}
      else if(path==='/api/servers' && method==='GET') data=[{id:'101',name:'fixture-server',status:'ONLINE',ip:'192.0.2.10'}]
      else if(path==='/api/cicd/onboarding/inspect' && method==='POST') data={repository:{branch:'main',imageRepository:'ghcr.io/example/retry-app',dockerfile:'FROM node:22\nEXPOSE 8080',runtime:'NODE'},provider:{targets:[],servers:[{id:'provider-1',name:'fixture-provider',ip:'192.0.2.10'}]}}
      else if(path==='/api/applications' && method==='POST'){
        requests.push(req.postDataJSON())
        if(requests.length===1)return route.abort('failed')
        assert.deepEqual(requests[1],requests[0],'Reload must replay the exact application request')
        data={...requests[0],id:'42'}
      }else if(path==='/api/cicd/onboarding/42' && method==='POST'){
        tasks++;data={id:'51',applicationId:'42',status:'PENDING',stage:0,containerPort:8080,hostPort:18081,healthPath:'/health',environmentVariableNames:[]}
      }else if(path==='/api/cicd/onboarding/42/advance' && method==='POST'){
        advances++;data={id:'51',applicationId:'42',status:'FAILED',stage:0,errorMessage:'Synthetic preflight stop: no external resources created',containerPort:8080,hostPort:18081,healthPath:'/health',environmentVariableNames:[]}
      }else if(!path.startsWith('/api/')){
        const file=path==='/cicd/onboarding'?join(dist,'index.html'):resolve(dist,'.'+path)
        if(!file.startsWith(dist+'/')||!existsSync(file)){unexpected.push(path);return route.abort()}
        return route.fulfill({contentType:({'.html':'text/html','.js':'application/javascript','.css':'text/css'})[extname(file)]||'application/octet-stream',body:readFileSync(file)})
      }else{unexpected.push(method+' '+path);return route.abort()}
      return route.fulfill({json:{code:0,message:'ok',data}})
    })
    const page=await context.newPage(), errors=[]
    page.on('pageerror',e=>errors.push(e.message))
    async function inspect(){
      await page.getByPlaceholder('https://github.com/you/blog').fill('https://github.com/example/retry-app')
      await page.getByLabel('仓库 Token',{exact:false}).fill('synthetic-repository-key')
      await page.getByPlaceholder('https://deploy.example.com').fill('https://deploy.example.com')
      await page.getByLabel('部署平台 API Key',{exact:false}).fill('synthetic-provider-key')
      await page.getByRole('button',{name:'检查授权与识别项目'}).click()
      await page.getByRole('heading',{name:'2 · 确认运行参数'}).waitFor()
      await page.getByLabel('Agent 所在业务服务器',{exact:false}).selectOption('101')
      await page.getByLabel('我已在 Dokploy 核对当前 Key',{exact:false}).check()
      await page.getByLabel('我确认目标服务器和端口',{exact:false}).check()
    }
    await page.goto(origin+'/cicd/onboarding')
    await inspect()
    await page.getByRole('button',{name:'自动完成接入配置 →'}).click()
    await page.getByRole('alert').waitFor()
    assert.equal(requests.length,1,'Must actually reach creation before simulating a lost response')
    assert.equal(tasks,0)
    await page.reload()
    await page.getByRole('status').filter({hasText:'有尚未完成的应用创建请求'}).waitFor()
    await inspect()
    assert.equal(await page.getByLabel('应用编码',{exact:true}).inputValue(),'retry-app')
    await page.getByRole('button',{name:'自动完成接入配置 →'}).click()
    await page.getByText('Synthetic preflight stop: no external resources created',{exact:true}).waitFor()
    assert(new URL(page.url()).searchParams.get('applicationId')==='42')
    assert.equal(requests.length,2);assert.equal(tasks,1);assert.equal(advances,1)
    const storage=await page.evaluate(()=>JSON.stringify(localStorage))
    assert(!storage.includes('synthetic-repository-key')&&!storage.includes('synthetic-provider-key'))
    assert(!storage.includes('devpilot.onboarding.application-create.'))
    assert.deepEqual(unexpected,[]);assert.deepEqual(errors,[])
    console.log('PASS: dropped creation response survives reload, reuses exact request, persists returned application ID before onboarding, and stores no credentials. Synthetic API only.')
  }finally{await browser.close()}
}
main().catch(e=>{console.error(e);process.exitCode=1})
