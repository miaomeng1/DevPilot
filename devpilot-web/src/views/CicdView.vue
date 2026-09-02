<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { applicationApi, type Application } from '@/api/applications'
import { cicdApi, type CicdConfiguration, type CicdConfigurationPayload, type CicdDeployment, type PipelineRun } from '@/api/cicd'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const applications = ref<Application[]>([])
const selectedId = ref('')
const configuration = ref<CicdConfiguration | null>(null)
const runs = ref<PipelineRun[]>([])
const deployments = ref<CicdDeployment[]>([])
const loading = ref(false)
const saving = ref(false)
const rollingBack = ref('')
const errorMessage = ref('')
const successMessage = ref('')
const revealedSecret = ref('')
const configurationExpanded = ref(false)
const runFilter = ref('ALL')
const runQuery = ref('')
let pollTimer: number | undefined

const form = reactive<CicdConfigurationPayload>({
  repositoryProvider: 'GITHUB', repositoryUrl: '', branchName: 'main', deploymentProvider: 'COOLIFY', deploymentMode: 'API',
  deploymentWebhookUrl: '', providerBaseUrl: '', providerApiToken: '', providerResourceId: '',
  autoDeploy: true, productionApproval: true, autoRollback: true, healthTimeoutSeconds: 120, rotateCallbackSecret: false,
})

const selectedApp = computed(() => applications.value.find((item) => item.id === selectedId.value) || null)
const canConfigure = computed(() => auth.hasAnyRole(['ADMIN']))
const summary = computed(() => ({
  total: runs.value.length,
  passed: runs.value.filter((run) => run.status === 'SUCCEEDED').length,
  failed: runs.value.filter((run) => run.status === 'FAILED').length,
  deployed: deployments.value.filter((item) => item.status === 'HEALTHY').length,
}))
const latestRun = computed(() => runs.value[0] || null)
const filteredRuns = computed(() => {
  const needle = runQuery.value.trim().toLowerCase()
  return runs.value.filter((run) => {
    const matchesText = !needle || [run.externalRunId, run.commitSha, run.imageUri].some((value) => value?.toLowerCase().includes(needle))
    const matchesStatus = runFilter.value === 'ALL'
      || (runFilter.value === 'HEALTHY' && run.deployStatus === 'HEALTHY')
      || (runFilter.value === 'FAILED' && ['FAILED', 'HEALTH_FAILED'].includes(run.deployStatus))
      || (runFilter.value === 'ACTIVE' && ['RUNNING', 'TRIGGERING', 'TRIGGERED', 'VERIFYING'].includes(run.deployStatus))
    return matchesText && matchesStatus
  })
})
const deliverySteps = computed(() => {
  const run = latestRun.value
  const successful = (value: string) => ['SUCCEEDED', 'PASSED', 'HEALTHY'].includes(value)
  const failed = (value: string) => ['FAILED', 'CANCELLED', 'UNHEALTHY', 'HEALTH_FAILED'].includes(value)
  return [
    { label: '代码提交', detail: run ? run.commitSha.slice(0, 12) : '等待 Commit', state: run ? 'done' : 'idle' },
    { label: '测试与扫描', detail: run ? `${statusLabel(run.testStatus)} · ${statusLabel(run.securityStatus)}` : 'Quality gates', state: run && successful(run.testStatus) && successful(run.securityStatus) ? 'done' : run && (failed(run.testStatus) || failed(run.securityStatus)) ? 'failed' : 'active' },
    { label: '不可变镜像', detail: run?.imageUri ? run.imageUri.split('/').pop() || run.imageUri : '等待 Image', state: run?.imageUri ? 'done' : run && failed(run.status) ? 'failed' : 'idle' },
    { label: '生产部署', detail: run ? statusLabel(run.deployStatus) : '等待 Deploy', state: run && run.deployStatus === 'HEALTHY' ? 'done' : run && failed(run.deployStatus) ? 'failed' : run && ['TRIGGERED', 'VERIFYING', 'TRIGGERING'].includes(run.deployStatus) ? 'active' : 'idle' },
  ]
})
const releaseGuidance = computed(() => {
  if (!configuration.value) return '先完成仓库与部署平台配置，DevPilot 才能接收签名流水线回调。'
  const run = latestRun.value
  if (!run) return '配置已就绪。向受保护分支推送代码，开始第一条流水线。'
  if (run.deployStatus === 'HEALTHY') return '最新版本已经通过部署后健康验证，可以安全提供服务。'
  if (['FAILED', 'HEALTH_FAILED'].includes(run.deployStatus)) return '最新发布未通过。旧健康版本仍被保留，请查看错误或回滚记录。'
  if (['TRIGGERED', 'VERIFYING', 'TRIGGERING'].includes(run.deployStatus)) return '发布正在执行，DevPilot 会等待 Provider 完成并使用新的 Agent 探测结果验证。'
  return '构建结果已收到；生产部署需要 CI 平台审批或手动运行 Production workflow。'
})

const statusLabels: Record<string, string> = {
  SUCCEEDED: '成功 SUCCEEDED',
  PASSED: '通过 PASSED',
  HEALTHY: '健康 HEALTHY',
  FAILED: '失败 FAILED',
  CANCELLED: '已取消 CANCELLED',
  UNHEALTHY: '异常 UNHEALTHY',
  HEALTH_FAILED: '健康检查失败',
  RUNNING: '运行中 RUNNING',
  TRIGGERING: '触发中 TRIGGERING',
  TRIGGERED: '已触发 TRIGGERED',
  VERIFYING: '验证中 VERIFYING',
  ROLLBACK_TRIGGERED: '回滚中 ROLLBACK',
  RELEASE: '发布 RELEASE',
  ROLLBACK: '回滚 ROLLBACK',
  PENDING: '等待中 PENDING',
  NOT_STARTED: '未开始 NOT STARTED',
  QUEUED: '排队中 QUEUED',
  SKIPPED: '已跳过 SKIPPED',
}

function statusLabel(value: string) {
  return statusLabels[value] || value
}

async function initialize() {
  loading.value = true
  errorMessage.value = ''
  try {
    applications.value = await applicationApi.list()
    selectedId.value = applications.value[0]?.id || ''
    if (selectedId.value) await loadApplication()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法加载 CI/CD 工作台 Workspace')
  } finally { loading.value = false }
}

async function loadApplication(silent = false) {
  if (!selectedId.value) return
  if (!silent) loading.value = true
  errorMessage.value = ''
  // Keep a newly generated one-time secret visible while background polling
  // refreshes pipeline evidence. It is cleared only when the operator
  // explicitly switches/reloads the application.
  if (!silent) revealedSecret.value = ''
  try {
    const [configurationResult, pipelineRuns, deploymentHistory] = await Promise.all([
      cicdApi.configuration(selectedId.value).catch(() => null),
      cicdApi.runs(selectedId.value),
      cicdApi.deployments(selectedId.value),
    ])
    configuration.value = configurationResult
    if (!silent) configurationExpanded.value = !configurationResult
    runs.value = pipelineRuns
    deployments.value = deploymentHistory
    Object.assign(form, configurationResult ? {
      repositoryProvider: configurationResult.repositoryProvider,
      repositoryUrl: configurationResult.repositoryUrl,
      branchName: configurationResult.branchName,
      deploymentProvider: configurationResult.deploymentProvider,
      deploymentMode: configurationResult.deploymentMode,
      deploymentWebhookUrl: '',
      providerBaseUrl: '', providerApiToken: '', providerResourceId: configurationResult.providerResourceId || '',
      autoDeploy: configurationResult.autoDeploy,
      productionApproval: configurationResult.productionApproval,
      autoRollback: configurationResult.autoRollback,
      healthTimeoutSeconds: configurationResult.healthTimeoutSeconds,
      rotateCallbackSecret: false,
    } : {
      repositoryProvider: 'GITHUB', repositoryUrl: '', branchName: 'main', deploymentProvider: 'COOLIFY', deploymentMode: 'API',
      deploymentWebhookUrl: '', providerBaseUrl: '', providerApiToken: '', providerResourceId: '',
      autoDeploy: true, productionApproval: true, autoRollback: true, healthTimeoutSeconds: 120, rotateCallbackSecret: false,
    })
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法加载流水线历史 Pipeline history')
  } finally { loading.value = false }
}

async function save() {
  const missingWebhook = form.deploymentMode === 'WEBHOOK' && !configuration.value && !form.deploymentWebhookUrl
  const missingApi = form.deploymentMode === 'API' && (!configuration.value || !configuration.value.providerBaseUrlConfigured || !configuration.value.providerApiTokenConfigured)
    && (!form.providerBaseUrl || !form.providerApiToken || !form.providerResourceId)
  if (!selectedId.value || !form.repositoryUrl || !form.branchName || missingWebhook || missingApi) return
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const saved = await cicdApi.saveConfiguration(selectedId.value, { ...form })
    configuration.value = saved
    revealedSecret.value = saved.oneTimeCallbackSecret || ''
    form.deploymentWebhookUrl = ''
    form.providerBaseUrl = ''
    form.providerApiToken = ''
    form.rotateCallbackSecret = false
    successMessage.value = 'CI/CD 配置已保存。Provider 凭据与回调密钥均已加密存储。'
    configurationExpanded.value = false
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法保存 CI/CD 配置 Configuration')
  } finally { saving.value = false }
}

async function rollback(deployment: CicdDeployment) {
  if (!selectedId.value || rollingBack.value) return
  rollingBack.value = deployment.id
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await cicdApi.rollback(selectedId.value, deployment.id)
    successMessage.value = `${deployment.provider} 已接受回滚 Rollback：${deployment.imageUri}`
    await loadApplication(true)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法触发回滚 Rollback')
  } finally { rollingBack.value = '' }
}

async function copy(value: string) {
  await navigator.clipboard.writeText(value)
  successMessage.value = '已复制 Copied to clipboard.'
}

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

function tone(value: string) {
  if (['SUCCEEDED', 'PASSED', 'HEALTHY'].includes(value)) return 'success'
  if (['FAILED', 'CANCELLED', 'UNHEALTHY', 'HEALTH_FAILED'].includes(value)) return 'danger'
  if (['RUNNING', 'TRIGGERING'].includes(value)) return 'running'
  return 'neutral'
}

watch(selectedId, () => void loadApplication())
onMounted(() => {
  void initialize()
  pollTimer = window.setInterval(() => void loadApplication(true), 15_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="cicd-view">
    <header class="page-heading cicd-heading">
      <div><span>持续交付控制面 · DELIVERY CONTROL PLANE</span><h1>CI/CD 发布中心</h1><p>签名流水线凭证、不可变镜像与受控生产发布。</p></div>
      <label class="app-selector"><span>应用 Application</span><select v-model="selectedId"><option v-for="app in applications" :key="app.id" :value="app.id">{{ app.name }} · {{ app.environment }}</option></select></label>
    </header>

    <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    <p v-if="successMessage" class="cicd-success">{{ successMessage }}</p>
    <div v-if="!applications.length && !loading" class="empty-panel"><strong>暂无应用 No applications</strong><p>请先创建并绑定应用，再配置 CI/CD。</p></div>

    <template v-else-if="selectedApp">
      <div class="cicd-summary">
        <article><span>运行次数 Runs</span><strong>{{ summary.total }}</strong><small>保留最近 100 条记录</small></article>
        <article><span>已通过 Passed</span><strong>{{ summary.passed }}</strong><small>质量与安全门禁通过</small></article>
        <article><span>失败 Failed</span><strong>{{ summary.failed }}</strong><small>已阻止部署 Deployment blocked</small></article>
        <article><span>健康 Healthy</span><strong>{{ summary.deployed }}</strong><small>部署后检查已通过</small></article>
      </div>

      <section class="delivery-overview">
        <header><div><span>当前发布 · CURRENT DELIVERY</span><strong>{{ latestRun ? statusLabel(latestRun.deployStatus) : '等待首次流水线' }}</strong><p>{{ releaseGuidance }}</p></div><div class="delivery-actions"><a v-if="configuration?.repositoryUrl" :href="configuration.repositoryUrl" target="_blank" rel="noreferrer">打开代码仓库 ↗</a><button type="button" @click="configurationExpanded = !configurationExpanded">{{ configurationExpanded ? '收起配置' : '配置与密钥' }}</button></div></header>
        <ol class="delivery-flow"><li v-for="(step, index) in deliverySteps" :key="step.label" :class="step.state"><span>{{ index + 1 }}</span><div><strong>{{ step.label }}</strong><small>{{ step.detail }}</small></div></li></ol>
      </section>

      <section v-if="configuration && !configurationExpanded" class="connection-strip">
        <div><span>代码仓库 Repository</span><strong>{{ configuration.repositoryProvider }} · {{ configuration.branchName }}</strong><small>{{ configuration.repositoryUrl }}</small></div>
        <div><span>部署平台 Provider</span><strong>{{ configuration.deploymentProvider }} · {{ configuration.deploymentMode }}</strong><small>资源 {{ configuration.providerResourceId || 'Provider managed' }}</small></div>
        <div><span>安全策略 Policy</span><strong>{{ configuration.productionApproval ? '生产审批开启' : '无需生产审批' }}</strong><small>{{ configuration.autoRollback ? '自动回滚已开启' : '自动回滚已关闭' }}</small></div>
        <button type="button" @click="configurationExpanded = true">编辑配置</button>
      </section>

      <div v-if="configurationExpanded" class="cicd-layout">
        <section class="cicd-panel">
          <header><div><strong>流水线契约 Pipeline contract</strong><small>代码仓库、受保护分支与部署适配器</small></div><span :class="{ live: configuration }"><i />{{ configuration ? '已配置 Configured' : '未配置' }}</span></header>
          <form class="cicd-form" @submit.prevent="save">
            <label><span>CI 平台 Provider</span><select v-model="form.repositoryProvider"><option>GITHUB</option><option>GITLAB</option><option>WOODPECKER</option></select></label>
            <label><span>代码仓库 Repository URL</span><input v-model="form.repositoryUrl" type="url" placeholder="https://github.com/org/repository" maxlength="1000" required /></label>
            <label><span>受保护分支 Protected branch</span><input v-model="form.branchName" maxlength="255" required /></label>
            <label><span>部署平台 Provider</span><select v-model="form.deploymentProvider"><option>COOLIFY</option><option>DOKPLOY</option></select></label>
            <label><span>部署模式 Mode</span><select v-model="form.deploymentMode"><option value="API">API · 精确镜像 Exact image</option><option value="WEBHOOK">Webhook · 平台托管</option></select></label>
            <template v-if="form.deploymentMode === 'API'">
              <label><span>平台地址 Base URL</span><input v-model="form.providerBaseUrl" type="url" :placeholder="configuration?.providerBaseUrlConfigured ? '留空以保留加密 URL' : 'https://deploy.example.com'" maxlength="1000" /></label>
              <label><span>资源 ID Resource ID</span><input v-model="form.providerResourceId" placeholder="应用 UUID / ID" pattern="[A-Za-z0-9_-]+" maxlength="255" /></label>
              <label class="wide"><span>平台令牌 API token</span><input v-model="form.providerApiToken" type="password" autocomplete="new-password" :placeholder="configuration?.providerApiTokenConfigured ? '留空以保留加密 Token' : '最小权限 API token'" maxlength="4000" /><small>DevPilot 更新精确的 sha-* 镜像，然后调用平台 Deploy API。</small></label>
            </template>
            <label v-else class="wide"><span>部署回调 Webhook</span><input v-model="form.deploymentWebhookUrl" type="url" :placeholder="configuration?.deploymentWebhookConfigured ? '留空以保留加密值' : 'Webhook 模式必填'" maxlength="4000" /><small>URL 会加密保存，API 不会再次返回明文。</small></label>
            <label class="check"><input v-model="form.autoDeploy" type="checkbox" /><span>仅在测试和安全扫描通过后自动部署 Auto deploy</span></label>
            <label class="check"><input v-model="form.productionApproval" type="checkbox" /><span>生产环境需要 CI 平台审批 Production approval</span></label>
            <label class="check"><input v-model="form.autoRollback" type="checkbox" /><span>健康检查失败时自动回滚最新健康镜像 Auto rollback</span></label>
            <label><span>健康超时 Health timeout（秒）</span><input v-model.number="form.healthTimeoutSeconds" type="number" min="30" max="1800" required /></label>
            <label v-if="configuration" class="check danger-check"><input v-model="form.rotateCallbackSecret" type="checkbox" /><span>轮换回调密钥，并使旧 CI Secret 失效</span></label>
            <footer class="wide"><button v-if="canConfigure" class="primary-action" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存配置 Save' }}</button><small v-else>需要管理员角色才能修改部署设置。</small></footer>
          </form>
        </section>

        <aside class="callback-panel">
          <header><strong>签名回调 Signed callback</strong><small>CI 向 DevPilot 报告可信凭证</small></header>
          <div v-if="configuration" class="callback-body">
            <label><span>回调地址 Callback path</span><code>{{ configuration.callbackUrl }}</code><button @click="copy(configuration.callbackUrl)">复制 Copy</button></label>
            <label v-if="revealedSecret" class="secret-reveal"><span>一次性回调密钥 One-time secret</span><code>{{ revealedSecret }}</code><button @click="copy(revealedSecret)">立即复制</button><small>此值不会再次显示，请存入受保护的 CI Secret。</small></label>
            <p>请求头 Header：<code>X-DevPilot-Signature: sha256=&lt;HMAC&gt;</code></p>
            <p>成功回调必须证明测试和安全门禁已通过，并使用 Digest 或 <code>sha-*</code> 镜像标签。</p>
          </div>
          <div v-else class="callback-empty">保存首个配置后，将生成 256-bit 回调密钥。</div>
        </aside>
      </div>

      <section class="pipeline-panel">
        <header class="pipeline-heading"><div><strong>流水线凭证 Pipeline evidence</strong><small>提交、门禁、不可变镜像与部署请求</small></div><div class="pipeline-tools"><input v-model="runQuery" placeholder="搜索 Commit / Image" /><select v-model="runFilter"><option value="ALL">全部记录</option><option value="HEALTHY">健康发布</option><option value="FAILED">失败记录</option><option value="ACTIVE">进行中</option></select><button @click="loadApplication()">刷新</button></div></header>
        <div class="table-scroll"><table class="console-table pipeline-table"><thead><tr><th>运行 / 提交</th><th>流水线 Pipeline</th><th>测试 Tests</th><th>安全 Security</th><th>镜像 Image</th><th>部署 Deployment</th><th>更新时间</th></tr></thead><tbody>
          <tr v-for="run in filteredRuns" :key="run.id"><td><a v-if="run.runUrl" :href="run.runUrl" target="_blank" rel="noreferrer">{{ run.externalRunId }}</a><strong v-else>{{ run.externalRunId }}</strong><code>{{ run.commitSha.slice(0, 12) }}</code></td><td><span class="pipeline-state" :class="tone(run.status)">{{ statusLabel(run.status) }}</span></td><td><span class="pipeline-state" :class="tone(run.testStatus)">{{ statusLabel(run.testStatus) }}</span></td><td><span class="pipeline-state" :class="tone(run.securityStatus)">{{ statusLabel(run.securityStatus) }}</span></td><td><code class="image-uri">{{ run.imageUri || '尚未生成 Not produced' }}</code></td><td><span class="pipeline-state" :class="tone(run.deployStatus)">{{ statusLabel(run.deployStatus) }}</span><small v-if="run.deployError">{{ run.deployError }}</small></td><td>{{ formatTime(run.updatedAt) }}</td></tr>
          <tr v-if="!filteredRuns.length"><td colspan="7" class="table-empty">没有符合筛选条件的流水线记录。</td></tr>
        </tbody></table></div>
      </section>

      <section class="pipeline-panel deployment-panel">
        <header><div><strong>部署验证与回滚 Verification & rollback</strong><small>平台接受部署后，由 Agent 执行全新的健康检查</small></div></header>
        <div class="table-scroll"><table class="console-table deployment-table"><thead><tr><th>开始时间</th><th>类型 Kind</th><th>精确镜像</th><th>平台 Provider</th><th>健康状态</th><th>证据 Evidence</th><th>操作 Action</th></tr></thead><tbody>
          <tr v-for="deployment in deployments" :key="deployment.id">
            <td>{{ formatTime(deployment.startedAt) }}</td><td><span class="pipeline-state" :class="tone(deployment.deploymentKind)">{{ statusLabel(deployment.deploymentKind) }}</span></td><td><code class="image-uri">{{ deployment.imageUri }}</code></td>
            <td>{{ deployment.provider }}<small v-if="deployment.providerDeploymentId">{{ deployment.providerDeploymentId }}</small></td>
            <td><span class="pipeline-state" :class="tone(deployment.status)">{{ statusLabel(deployment.status) }}</span><small v-if="deployment.status === 'TRIGGERED'">截止 Deadline {{ formatTime(deployment.healthDeadlineAt) }}</small></td>
            <td><details v-if="deployment.logs" class="deployment-log"><summary>查看采集日志 View logs</summary><pre>{{ deployment.logs }}</pre></details><span v-else>等待凭证 Awaiting evidence</span></td>
            <td><button v-if="canConfigure && deployment.status === 'HEALTHY'" class="rollback-action" :disabled="!!rollingBack" @click="rollback(deployment)">{{ rollingBack === deployment.id ? '回滚中…' : '回滚到此版本' }}</button><span v-else>—</span></td>
          </tr>
          <tr v-if="!deployments.length"><td colspan="7" class="table-empty">尚未触发部署 No deployments.</td></tr>
        </tbody></table></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.cicd-view{max-width:1480px;margin:0 auto}.cicd-heading{align-items:center}.app-selector{display:grid;gap:6px;color:#718096;font-size:8px;font-weight:700}.app-selector select,.cicd-form input,.cicd-form select{height:38px;border:1px solid var(--line);border-radius:8px;padding:0 10px;color:var(--text);background:var(--panel);font-size:8px}.app-selector select{min-width:240px}.cicd-success{margin:0 0 12px;border:1px solid rgba(34,197,94,.2);border-radius:8px;padding:10px;color:#4ade80;background:rgba(34,197,94,.05);font-size:8px}.cicd-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;overflow:hidden;border:1px solid var(--line);border-radius:11px;background:var(--line)}.cicd-summary article{padding:16px;background:var(--panel)}.cicd-summary span,.cicd-summary small{display:block;color:#64748b;font-size:8px}.cicd-summary strong{display:block;margin:8px 0 5px;font-size:21px}.cicd-layout{display:grid;grid-template-columns:minmax(0,1fr) 350px;gap:14px;margin-top:14px}.cicd-panel,.callback-panel,.pipeline-panel{overflow:hidden;border:1px solid var(--line);border-radius:11px;background:var(--panel)}.cicd-panel>header,.callback-panel>header,.pipeline-panel>header{display:flex;min-height:58px;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);padding:0 15px}.cicd-panel header strong,.cicd-panel header small,.callback-panel header strong,.callback-panel header small,.pipeline-panel header strong,.pipeline-panel header small{display:block}.cicd-panel header strong,.callback-panel header strong,.pipeline-panel header strong{font-size:10px}.cicd-panel header small,.callback-panel header small,.pipeline-panel header small{margin-top:4px;color:#617087;font-size:8px}.cicd-panel>header>span{display:flex;align-items:center;gap:5px;color:#64748b;font-size:7px}.cicd-panel>header>span i{width:5px;height:5px;border-radius:50%;background:currentColor}.cicd-panel>header>span.live{color:#4ade80}.cicd-form{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:15px}.cicd-form label:not(.check){display:grid;gap:6px;color:#7d8ba0;font-size:8px;font-weight:700}.cicd-form label small{color:#5f6d81;font-weight:400}.cicd-form .wide{grid-column:1/-1}.check{display:flex;grid-column:1/-1;align-items:center;gap:8px;color:#8795a9;font-size:8px}.check input{accent-color:#2563eb}.danger-check{color:#f59e0b}.cicd-form footer{display:flex;align-items:center;justify-content:flex-end}.primary-action,.pipeline-panel>header>button,.callback-body button{height:31px;border:0;border-radius:7px;padding:0 12px;color:#fff;background:#2563eb;font-size:8px;font-weight:750}.callback-body{display:grid;gap:13px;padding:15px}.callback-body label{display:grid;grid-template-columns:1fr auto;gap:6px}.callback-body label>span,.callback-body label>small{grid-column:1/-1;color:#68778c;font-size:8px}.callback-body code{overflow:hidden;border:1px solid var(--line);border-radius:6px;padding:9px;color:#93c5fd;background:#080d18;font:8px ui-monospace,monospace;text-overflow:ellipsis}.callback-body button{height:auto}.callback-body p,.callback-empty{margin:0;color:#64748b;font-size:8px;line-height:1.6}.secret-reveal{border:1px solid rgba(245,158,11,.2);border-radius:8px;padding:10px;background:rgba(245,158,11,.04)}.callback-empty{padding:18px}.pipeline-panel{margin-top:14px}.pipeline-panel>header>button{border:1px solid var(--line);color:#8ba9d7;background:transparent}.pipeline-table{min-width:1120px}.pipeline-table td:first-child strong,.pipeline-table td:first-child a,.pipeline-table td:first-child code{display:block}.pipeline-table td:first-child a{color:#82adf5;text-decoration:none;font-weight:750}.pipeline-table td:first-child code{margin-top:5px;color:#69788d}.pipeline-state{display:inline-flex;border-radius:4px;padding:4px 6px;color:#94a3b8;background:rgba(100,116,139,.08);font-size:6px;font-weight:850}.pipeline-state.success{color:#4ade80;background:rgba(34,197,94,.08)}.pipeline-state.danger{color:#f87171;background:rgba(239,68,68,.08)}.pipeline-state.running{color:#60a5fa;background:rgba(59,130,246,.08)}.image-uri{display:block;max-width:260px;overflow:hidden;color:#a78bfa;text-overflow:ellipsis;white-space:nowrap}.pipeline-table td small{display:block;max-width:180px;overflow:hidden;margin-top:4px;color:#f87171;text-overflow:ellipsis;white-space:nowrap}.empty-panel{border:1px solid var(--line);border-radius:11px;padding:30px;text-align:center;background:var(--panel)}.empty-panel p{color:#64748b;font-size:8px}.table-empty{text-align:center;color:#64748b!important}@media(max-width:1050px){.cicd-layout{grid-template-columns:1fr}.cicd-summary{grid-template-columns:1fr 1fr}}@media(max-width:700px){.cicd-heading{align-items:stretch;flex-direction:column}.app-selector select{width:100%}.cicd-form{grid-template-columns:1fr}.cicd-form .wide{grid-column:1}.cicd-summary{gap:8px;border:0;background:transparent}.cicd-summary article{border:1px solid var(--line);border-radius:9px}}
.deployment-table{min-width:1180px}.deployment-log{max-width:480px;color:#7e8ca1}.deployment-log summary{cursor:pointer;color:#82adf5;font-size:7px;font-weight:750}.deployment-log pre{max-height:360px;overflow:auto;margin:8px 0 0;border:1px solid var(--line);border-radius:6px;padding:10px;color:#b8c4d6;background:#080d18;font:7px/1.55 ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}.rollback-action{height:28px;border:1px solid rgba(245,158,11,.25);border-radius:6px;padding:0 9px;color:#fbbf24;background:rgba(245,158,11,.05);font-size:7px;font-weight:750}.rollback-action:disabled{opacity:.5}.deployment-table td small{display:block;max-width:190px;overflow:hidden;margin-top:4px;color:#f59e0b;text-overflow:ellipsis;white-space:nowrap}

/* Product refresh: readable hierarchy and delivery-first workflow */
.cicd-heading>div>span{color:#76a7ff;font-size:11px;font-weight:800;letter-spacing:.12em}.cicd-heading p{margin:0;color:var(--muted);font-size:14px}.app-selector{font-size:12px}.app-selector select,.cicd-form input,.cicd-form select{height:44px;border-radius:10px;padding:0 13px;font-size:12px}.app-selector select{min-width:290px}.cicd-success{padding:13px 15px;border-radius:10px;font-size:12px}
.cicd-summary{border-radius:14px}.cicd-summary article{padding:20px 22px}.cicd-summary span{font-size:11px;font-weight:750}.cicd-summary small{font-size:11px}.cicd-summary strong{margin:10px 0 7px;font-size:29px}
.delivery-overview{overflow:hidden;margin-top:18px;border:1px solid rgba(59,130,246,.25);border-radius:16px;background:linear-gradient(135deg,rgba(37,99,235,.13),rgba(99,102,241,.04) 55%,var(--panel))}.delivery-overview>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:24px 26px}.delivery-overview>header>div:first-child>span{color:#76a7ff;font-size:10px;font-weight:850;letter-spacing:.14em}.delivery-overview>header strong{display:block;margin-top:8px;font-size:24px;letter-spacing:-.03em}.delivery-overview>header p{max-width:760px;margin:8px 0 0;color:#91a0b6;font-size:13px;line-height:1.6}.delivery-actions{display:flex;flex:0 0 auto;gap:9px}.delivery-actions a,.delivery-actions button,.connection-strip>button{display:flex;height:38px;align-items:center;border:1px solid var(--line);border-radius:9px;padding:0 13px;color:#a8c5f5;background:rgba(15,23,42,.25);font-size:11px;font-weight:750;text-decoration:none}.delivery-actions button{color:#fff;background:#2563eb}
.delivery-flow{display:grid;grid-template-columns:repeat(4,1fr);margin:0;border-top:1px solid rgba(148,163,184,.12);padding:0;list-style:none}.delivery-flow li{position:relative;display:grid;grid-template-columns:34px 1fr;gap:11px;min-width:0;padding:18px 22px;border-right:1px solid rgba(148,163,184,.12)}.delivery-flow li:last-child{border:0}.delivery-flow li>span{display:grid;width:32px;height:32px;place-items:center;border:1px solid var(--line);border-radius:10px;color:#708096;background:rgba(15,23,42,.3);font-size:11px;font-weight:850}.delivery-flow strong,.delivery-flow small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.delivery-flow strong{font-size:12px}.delivery-flow small{margin-top:6px;color:#6f7f94;font-size:10px}.delivery-flow li.done>span{border-color:rgba(34,197,94,.25);color:#4ade80;background:rgba(34,197,94,.09)}.delivery-flow li.done>span::after{content:'✓'}.delivery-flow li.done>span{font-size:0}.delivery-flow li.done>span::after{font-size:13px}.delivery-flow li.active>span{border-color:rgba(59,130,246,.4);color:#93c5fd;background:rgba(59,130,246,.12);box-shadow:0 0 0 4px rgba(59,130,246,.06)}.delivery-flow li.failed>span{border-color:rgba(239,68,68,.35);color:#f87171;background:rgba(239,68,68,.09)}
.connection-strip{display:grid;grid-template-columns:1.2fr .8fr .8fr auto;align-items:center;gap:0;margin-top:14px;border:1px solid var(--line);border-radius:14px;padding:14px 16px;background:var(--panel)}.connection-strip>div{min-width:0;padding:3px 18px;border-right:1px solid var(--line)}.connection-strip>div:first-child{padding-left:4px}.connection-strip span,.connection-strip strong,.connection-strip small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.connection-strip span{color:#68778c;font-size:10px}.connection-strip strong{margin-top:6px;font-size:12px}.connection-strip small{margin-top:4px;color:#65748a;font-size:10px}.connection-strip>button{margin-left:16px}
.cicd-layout{grid-template-columns:minmax(0,1.25fr) 390px;gap:16px;margin-top:16px}.cicd-panel,.callback-panel,.pipeline-panel{border-radius:14px}.cicd-panel>header,.callback-panel>header,.pipeline-panel>header{min-height:68px;padding:0 20px}.cicd-panel header strong,.callback-panel header strong,.pipeline-panel header strong{font-size:14px}.cicd-panel header small,.callback-panel header small,.pipeline-panel header small{font-size:11px}.cicd-panel>header>span{font-size:10px}.cicd-form{gap:17px;padding:20px}.cicd-form label:not(.check){gap:8px;font-size:12px}.cicd-form label small{font-size:10px;line-height:1.5}.check{gap:10px;font-size:12px}.check input{width:16px;height:16px}.cicd-form .primary-action{width:auto;height:42px;padding:0 18px;font-size:12px}.callback-body{gap:16px;padding:20px}.callback-body label>span,.callback-body label>small{font-size:11px}.callback-body code{padding:11px;font-size:10px}.callback-body button{font-size:11px}.callback-body p,.callback-empty{font-size:11px}.callback-empty{padding:22px}
.pipeline-panel{margin-top:16px}.pipeline-heading{gap:16px}.pipeline-tools{display:flex;align-items:center;gap:8px}.pipeline-tools input,.pipeline-tools select{height:36px;border:1px solid var(--line);border-radius:8px;outline:0;padding:0 10px;color:var(--text);background:rgba(15,23,42,.22);font-size:11px}.pipeline-tools input{width:190px}.pipeline-panel>header>button,.pipeline-tools button{height:36px;font-size:11px}.pipeline-state{border-radius:6px;padding:5px 8px;font-size:9px;white-space:nowrap}.pipeline-table td,.deployment-table td{font-size:11px}.pipeline-table td:first-child code{font-size:10px}.image-uri{max-width:310px;font-size:10px}.pipeline-table td small,.deployment-table td small{font-size:10px}.deployment-log summary{font-size:10px}.deployment-log pre{font:10px/1.6 ui-monospace,monospace}.rollback-action{height:34px;padding:0 11px;font-size:10px}.empty-panel p{font-size:12px}
@media(max-width:1100px){.delivery-flow{grid-template-columns:1fr 1fr}.delivery-flow li:nth-child(2){border-right:0}.delivery-flow li:nth-child(-n+2){border-bottom:1px solid var(--line)}.connection-strip{grid-template-columns:1fr 1fr}.connection-strip>div{border:0;border-bottom:1px solid var(--line);padding:12px}.connection-strip>button{margin:12px}.cicd-layout{grid-template-columns:1fr}}
@media(max-width:700px){.delivery-overview>header{padding:20px;flex-direction:column}.delivery-actions{width:100%}.delivery-actions a,.delivery-actions button{flex:1;justify-content:center}.delivery-flow{grid-template-columns:1fr}.delivery-flow li{border-right:0!important;border-bottom:1px solid var(--line)!important}.delivery-flow li:last-child{border-bottom:0!important}.connection-strip{grid-template-columns:1fr}.pipeline-heading{align-items:stretch!important;flex-direction:column}.pipeline-tools{display:grid;grid-template-columns:1fr 1fr}.pipeline-tools input{grid-column:1/-1;width:100%}.pipeline-tools button{height:36px!important}.cicd-summary article{padding:16px}.cicd-heading p{font-size:12px}}
</style>
