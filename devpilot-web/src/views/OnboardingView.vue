<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiClient, apiErrorMessage, type ApiResponse } from '@/api/client'
import { applicationApi } from '@/api/applications'
import { useServerStore } from '@/stores/servers'
import { generateWorkflow, type RuntimePreset } from '@/utils/workflowTemplates'

interface Inspection {
  repository: { branch: string; imageRepository: string; dockerfile: string; runtime: RuntimePreset }
  provider: { targets: { projectId: string; environmentId: string; label: string }[]; servers: { id: string; name: string; ip: string }[] }
}
interface Job { id: string; applicationId: string; stage: number; status: string; resourceId: string | null; changeUrl: string | null; errorMessage: string | null }
const route = useRoute(), router = useRouter(), servers = useServerStore()
const inspection = ref<Inspection | null>(null), job = ref<Job | null>(null)
const busy = ref(false), error = ref(''), confirmed = ref(false)
const runtime = ref<RuntimePreset>('NODE'), envText = ref('')
const applicationId = ref('')
let mounted = true
const form = reactive({
  name: '', code: '', serverId: '', repositoryProvider: 'GITHUB' as 'GITHUB' | 'GITLAB',
  repositoryUrl: '', repositoryToken: '', deploymentProvider: 'DOKPLOY', providerBaseUrl: '', providerApiToken: '',
  projectId: '', environmentId: '__new__', providerServerId: '', publicBaseUrl: window.location.origin,
  containerPort: 8080, hostPort: 18081, healthPath: '/health', imageRepository: '', branch: '',
  registryUsername: '', registryPassword: '',
})
const steps = ['仓库、必要权限与端口预检', '创建部署应用', '配置端口、变量和发布目标', '加密写入 CI Secrets', '提交流水线 PR / MR']
const jobStatus: Record<string, string> = { PENDING: '等待执行 Pending', FAILED: '失败 · 可重试', EXPIRED: '授权已过期', AWAITING_MERGE: '待审阅合并', RUNNING: '执行中 Running' }
const workflow = computed(() => !inspection.value ? null : generateWorkflow({
  provider: form.repositoryProvider, runtime: runtime.value, branch: form.branch, imageRepository: form.imageRepository,
  applicationCode: form.code, callbackUrl: `${form.publicBaseUrl.replace(/\/+$/, '')}/api/cicd/webhooks/${form.code}`,
  previewEnabled: false, previewCallbackUrl: '', previewUrlTemplate: '', previewTtlHours: 72,
}))
async function inspect() {
  busy.value = true; error.value = ''; inspection.value = null; confirmed.value = false
  try {
    const response = await apiClient.post<ApiResponse<Inspection>>('/cicd/onboarding/inspect', form, { timeout: 120000 })
    inspection.value = response.data.data
    form.branch = inspection.value.repository.branch
    form.imageRepository = inspection.value.repository.imageRepository
    runtime.value = inspection.value.repository.runtime
    const suggested = form.repositoryUrl.split('/').pop()?.replace(/\.git$/, '').toLowerCase().replace(/[^a-z0-9-]/g, '-') || 'app'
    if (!form.code) form.code = /^[a-z]/.test(suggested) ? suggested : `app-${suggested}`
    if (!form.name) form.name = form.code
    if (inspection.value.provider.servers.length === 1) form.providerServerId = inspection.value.provider.servers[0]!.id
    const exposed = inspection.value.repository.dockerfile.match(/^EXPOSE\s+(\d+)/im)
    if (exposed) form.containerPort = Number(exposed[1])
  } catch (cause) { error.value = apiErrorMessage(cause, '无法检查接入条件；没有创建任何部署资源') }
  finally { busy.value = false }
}
function selectTarget() {
  form.projectId = inspection.value?.provider.targets.find(t => t.environmentId === form.environmentId)?.projectId || ''
}
function variables() {
  const values: Record<string, string> = {}
  for (const line of envText.value.split('\n').filter(line => line.trim() && !line.startsWith('#'))) {
    const index = line.indexOf('=')
    const key = line.slice(0, index)
    if (index < 1 || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) throw new Error('环境变量格式为 KEY=value，每行一个')
    values[key] = line.slice(index + 1)
  }
  return values
}
async function start() {
  if (!workflow.value || !confirmed.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const environmentValues = variables()
    if (!applicationId.value) {
      const app = await applicationApi.create({ name: form.name, code: form.code, serverId: form.serverId,
        environment: 'PRODUCTION', description: '由自动接入向导配置', containerSnapshotId: null, currentVersion: '',
        healthCheckUrl: `http://127.0.0.1:${form.hostPort}${form.healthPath}`, accessUrl: '' })
      applicationId.value = app.id
    }
    const response = await apiClient.post<ApiResponse<Job>>(`/cicd/onboarding/${applicationId.value}`, {
      ...form, environmentValues, workflowContent: workflow.value.content,
    })
    job.value = response.data.data
    await router.replace({ query: { applicationId: applicationId.value } })
    // Browser memory only; tokens are never put into URLs or localStorage.
    form.repositoryToken = ''; form.providerApiToken = ''; form.registryPassword = ''; envText.value = ''
    await advance()
  } catch (cause) { error.value = cause instanceof Error && !('response' in cause) ? cause.message : apiErrorMessage(cause, '接入任务创建失败') }
  finally { busy.value = false }
}
async function advance() {
  busy.value = true; error.value = ''
  try {
    do {
      const response = await apiClient.post<ApiResponse<Job>>(`/cicd/onboarding/${applicationId.value}/advance`, {}, { timeout: 600000 })
      job.value = response.data.data
      if (job.value.status !== 'PENDING') break
    } while (mounted)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '请求中断；进度已保存在服务端，请查询状态后继续，不要重复创建应用')
  } finally { busy.value = false }
}
async function refresh() {
  try { job.value = (await apiClient.get<ApiResponse<Job>>(`/cicd/onboarding/${applicationId.value}`)).data.data }
  catch (cause) { error.value = apiErrorMessage(cause, '无法读取接入进度') }
}
async function updateCredentials() {
  busy.value = true; error.value = ''
  try {
    job.value = (await apiClient.put<ApiResponse<Job>>(`/cicd/onboarding/${applicationId.value}/credentials`, {
      repositoryToken: form.repositoryToken, providerApiToken: form.providerApiToken, registryPassword: form.registryPassword,
    })).data.data
    form.repositoryToken = ''; form.providerApiToken = ''; form.registryPassword = ''
  } catch (cause) { error.value = apiErrorMessage(cause, '无法更新凭据') }
  finally { busy.value = false }
}
onMounted(async () => {
  try { await servers.load() } catch (cause) { error.value = apiErrorMessage(cause, '无法读取服务器列表') }
  if (typeof route.query.applicationId === 'string') { applicationId.value = route.query.applicationId; await refresh() }
})
onBeforeUnmount(() => { mounted = false; form.repositoryToken = ''; form.providerApiToken = ''; form.registryPassword = '' })
</script>

<template>
  <main class="onboard-page">
    <header><div><RouterLink to="/cicd">← 发布中心</RouterLink><h1>自动接入项目 <small>Repository setup</small></h1><p>一次授权，自动准备交付配置。生产发布始终由你确认。</p></div></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section v-if="job" class="card progress" aria-live="polite">
      <h2>{{ job.status === 'AWAITING_MERGE' ? '配置已提交，等待审阅合并' : '接入进度' }}</h2>
      <ol><li v-for="(step, index) in steps" :key="step" :class="{ done: job.stage > index, active: job.stage === index }"><b>{{ job.stage > index ? '✓' : index + 1 }}</b><span>{{ step }}</span><small>{{ job.stage > index ? '完成' : job.stage === index ? jobStatus[job.status] || job.status : '等待' }}</small></li></ol>
      <p class="verification-note">步骤完成不代表全面就绪：平台剩余配额与细粒度授权范围可能无法自动读取；实际构建、签名回调、目标镜像和新鲜健康检查通过后，才算交付成功。</p>
      <p v-if="job.errorMessage" class="error">{{ job.errorMessage }}</p>
      <details v-if="job.status === 'FAILED'"><summary>授权失效？更新凭据（留空表示不修改）</summary><div class="grid">
        <label>仓库 Token<input v-model="form.repositoryToken" type="password" autocomplete="off" /></label>
        <label>部署平台 Key<input v-model="form.providerApiToken" type="password" autocomplete="off" /></label>
        <label>Registry 密码<input v-model="form.registryPassword" type="password" autocomplete="off" /></label>
        <button :disabled="busy" @click="updateCredentials">加密更新凭据</button>
      </div></details>
      <p>已创建的资源会保留，重试会核对并复用；不会自动删除业务数据。</p>
      <a v-if="job.changeUrl" :href="job.changeUrl" target="_blank" rel="noreferrer" class="primary">审阅并合并 PR / MR ↗</a>
      <p v-if="job.status === 'AWAITING_MERGE'">合并后自动构建镜像；在 GitHub Run workflow / GitLab 手动生产任务确认发布。首次有效回调和健康检查完成前，不会声称部署已验收。</p>
      <button v-if="job.stage < 5" :disabled="busy || job.status === 'EXPIRED'" @click="advance">{{ busy ? '正在执行…' : '继续 / 重试当前步骤' }}</button>
      <button :disabled="busy" @click="refresh">查询状态</button>
      <RouterLink :to="`/applications/${applicationId}`">查看应用</RouterLink>
    </section>
    <template v-else>
      <section class="card"><h2>1 · 连接账号与部署平台</h2>
        <p>仓库需要根目录 Dockerfile 和可运行的测试。Token 只用于本次接入，加密暂存，完成后清除；失败任务最多保留 24 小时。</p>
        <div class="grid">
          <label>代码平台<select v-model="form.repositoryProvider" :disabled="!!inspection"><option>GITHUB</option><option>GITLAB</option></select></label>
          <label>仓库地址<input v-model.trim="form.repositoryUrl" :disabled="!!inspection" placeholder="https://github.com/you/blog" /></label>
          <label class="wide">仓库 Token<input v-model="form.repositoryToken" :disabled="!!inspection" type="password" autocomplete="off" /><small>GitHub：Contents、Workflows、Pull requests、Environments、Secrets 写权限；GitLab：api 权限，发布分支需受保护。</small></label>
          <label>部署平台<select v-model="form.deploymentProvider" :disabled="!!inspection"><option>DOKPLOY</option><option>COOLIFY</option></select></label>
          <label>平台地址<input v-model.trim="form.providerBaseUrl" :disabled="!!inspection" placeholder="https://deploy.example.com" /></label>
          <label class="wide">部署平台 API Key<input v-model="form.providerApiToken" :disabled="!!inspection" type="password" autocomplete="off" /><small>应具备读取项目、创建应用和部署权限。仅连接你信任的平台；公网务必使用 HTTPS。</small></label>
        </div>
        <button v-if="!inspection" class="primary" :disabled="busy" @click="inspect">{{ busy ? '正在检查…' : '检查授权与识别项目' }}</button>
        <button v-else :disabled="busy" @click="inspection = null; confirmed = false">修改连接</button>
      </section>
      <section v-if="inspection" class="card"><h2>2 · 确认运行参数</h2>
        <aside class="verification-note" aria-label="预检范围与待确认事项">
          <strong>已识别项目，不等于全部就绪</strong>
          <p>当前仅确认仓库可读取、项目类型和部署目标列表。开始接入后才会检查必要权限及目标机端口；请确保新版 Agent 在目标宿主机网络中运行。</p>
          <p v-if="form.deploymentProvider === 'DOKPLOY'">待人工确认 · Dokploy Key 剩余配额无法通过普通 API Key 自动读取。请在平台核对限流和有效期；每天 10 次的限制不足以支持接入和部署轮询。DevPilot 不会自动提高配额或扩大权限。</p>
          <p v-else>待人工确认 · Coolify 私有镜像拉取凭据和平台配额需在目标平台配置，该路径尚未完成真实端到端验收。</p>
        </aside>
        <div class="grid">
          <label>应用名称<input v-model.trim="form.name" /></label><label>应用编码<input v-model.trim="form.code" pattern="[a-z][a-z0-9-]{1,63}" /></label>
          <label>Agent 所在业务服务器<select v-model="form.serverId"><option value="">请选择</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }} · {{ server.ip }}</option></select></label>
          <label>部署平台目标服务器<select v-model="form.providerServerId"><option v-for="server in inspection.provider.servers" :key="server.id" :value="server.id">{{ server.name }} {{ server.ip }}</option></select><small>必须与左侧 Agent 位于同一台机器；不能用控制台服务器的 Agent 代替业务服务器。</small></label>
          <label class="wide">项目 / 环境<select v-model="form.environmentId" @change="selectTarget"><option value="__new__">自动创建专用项目和生产环境（推荐）</option><option v-for="target in inspection.provider.targets" :key="target.environmentId" :value="target.environmentId">{{ target.label }}</option></select></label>
          <label>测试类型<select v-model="runtime"><option>NODE</option><option>JAVA</option><option>GO</option><option>DOCKER</option></select></label><label>发布分支<input :value="form.branch" readonly /></label>
          <label>容器内部端口 Internal<input v-model.number="form.containerPort" type="number" min="1" max="65535" /></label><label>服务器发布端口 Published<input v-model.number="form.hostPort" type="number" min="1024" max="65535" /><small>预检结合 Agent 监听端口、Docker 映射和 Dokploy 应用；需要 30 秒内的 Agent 数据。不会预留端口，外网访问仍取决于防火墙。</small></label>
          <label>健康检查路径<input v-model.trim="form.healthPath" placeholder="/health" /></label><label>镜像仓库<input v-model.trim="form.imageRepository" /></label>
          <label class="wide">DevPilot 公网 HTTPS 根地址<input v-model.trim="form.publicBaseUrl" placeholder="https://ops.example.com" /><small>不能填写 localhost。仅保存地址不代表云端 Runner 已经连通，首次回调才是连通证据。</small></label>
          <label>Registry 拉取用户名（私有镜像）<input v-model.trim="form.registryUsername" autocomplete="off" /></label><label>Registry 拉取密码 / Token<input v-model="form.registryPassword" type="password" autocomplete="off" /><small>Dokploy 可自动配置。Coolify 需服务器预先登录 Registry；不会自动公开你的镜像。</small></label>
          <label class="wide">业务环境变量<textarea v-model="envText" rows="4" placeholder="DATABASE_URL=...&#10;PORT=8080" /><small>每行 KEY=value，值不带额外引号；变量只写入新建应用，不覆盖已有应用。</small></label>
        </div>
      </section>
      <section v-if="inspection" class="card"><h2>3 · 审阅并执行</h2>
        <p>将创建一个部署应用、配置端口和变量、写入独立生产环境 Secrets，并创建流水线 PR/MR。不会直接合并、不会自动发布，也不会改变包的公开状态。</p>
        <details><summary>查看生成的流水线</summary><pre>{{ workflow?.content }}</pre></details>
        <label class="consent"><input v-model="confirmed" type="checkbox" /> 我确认目标服务器和端口，并允许执行上述配置变更</label>
        <button class="primary" :disabled="busy || !confirmed || !form.name || !form.code || !form.serverId || !form.environmentId" @click="start">{{ busy ? '接入中…' : '自动完成接入配置 →' }}</button>
      </section>
    </template>
  </main>
</template>

<style scoped>
.verification-note{border:1px solid #b88735;border-radius:9px;padding:13px 16px;background:#e9aa190a;color:var(--text);font-size:12px;line-height:1.7}.verification-note p{margin:6px 0;font-size:12px}
.onboard-page{max-width:1050px;margin:0 auto;padding:24px;color:var(--text)}header{margin-bottom:24px}h1{font-size:26px;margin:12px 0}h1 small{font-size:14px;color:var(--muted);font-weight:400}h2{font-size:17px;margin:0 0 14px}p,small{color:var(--muted);line-height:1.65}p{font-size:13px}a{color:#568ded}.card{border:1px solid var(--line);border-radius:14px;background:var(--panel);padding:24px;margin:18px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin:20px 0}.grid label{display:flex;flex-direction:column;gap:7px;font-size:13px}.wide{grid-column:1/-1}input,select,textarea{box-sizing:border-box;width:100%;border:1px solid var(--line);border-radius:7px;padding:11px;color:var(--text);background:var(--panel-solid);font:inherit}small{font-size:11px}button,.primary{display:inline-block;border:1px solid var(--line);border-radius:8px;padding:11px 16px;background:var(--panel-solid);color:var(--text);cursor:pointer;margin:8px 8px 0 0;font-size:13px;text-decoration:none}.primary{background:#2563eb;color:white;border-color:#2563eb}button:disabled{opacity:.5;cursor:not-allowed}.error{padding:12px;border:1px solid #db7474;border-radius:8px;color:#c54e4e;background:#e456560a}.consent{display:flex;gap:10px;align-items:center;margin:20px 0;font-size:13px}.consent input{width:18px;height:18px}pre{max-height:380px;overflow:auto;font-size:11px;line-height:1.6;padding:15px;background:#101827;color:#d7e1f3;border-radius:8px}summary{cursor:pointer;color:#568ded}ol{list-style:none;padding:0}li{display:flex;align-items:center;gap:14px;padding:14px 0;border-bottom:1px solid var(--line);font-size:13px}li b{display:grid;place-items:center;width:28px;height:28px;background:var(--panel-solid);border:1px solid var(--line);border-radius:50%}li small{margin-left:auto}.done b{color:#1b9854}.active b{background:#2563eb;color:white}@media(max-width:650px){.grid{grid-template-columns:1fr}.onboard-page{padding:12px}.card{padding:18px}}
</style>
