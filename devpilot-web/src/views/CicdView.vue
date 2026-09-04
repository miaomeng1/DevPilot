<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { applicationApi, type Application } from '@/api/applications'
import { cicdApi, type CicdActivity, type CicdConfiguration, type CicdConfigurationPayload, type CicdDeployment, type PipelineRun } from '@/api/cicd'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { generateWorkflow, type RuntimePreset } from '@/utils/workflowTemplates'

const auth = useAuthStore()
const applications = ref<Application[]>([])
const selectedId = ref('')
const configuration = ref<CicdConfiguration | null>(null)
const runs = ref<PipelineRun[]>([])
const deployments = ref<CicdDeployment[]>([])
const activity = ref<CicdActivity[]>([])
const loading = ref(false)
const saving = ref(false)
const rollingBack = ref('')
const errorMessage = ref('')
const successMessage = ref('')
const revealedSecret = ref('')
const configurationExpanded = ref(false)
const onboardingOpen = ref(false)
const runtimePreset = ref<RuntimePreset>('NODE')
const runtimePresets: RuntimePreset[] = ['NODE', 'JAVA', 'GO', 'DOCKER']
const imageRepository = ref('')
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
const latestHealthyDeployment = computed(() => deployments.value.find((item) => item.status === 'HEALTHY') || null)
const imageDrift = computed(() => {
  const expected = latestHealthyDeployment.value?.imageUri || ''
  const actual = selectedApp.value?.dockerImage || ''
  if (!expected) return { state: 'unknown', label: '尚无健康基线', detail: '完成首次健康发布后开始检测' }
  if (!actual) return { state: 'unknown', label: '等待运行清单', detail: 'Agent 尚未上报实际运行镜像' }
  if (expected === actual) return { state: 'synced', label: '镜像一致 In sync', detail: actual }
  return { state: 'drift', label: '检测到镜像漂移 Drift', detail: `期望 ${expected} · 实际 ${actual}` }
})
const filteredRuns = computed(() => {
  const needle = runQuery.value.trim().toLowerCase()
  return runs.value.filter((run) => {
    const matchesText = !needle || [run.externalRunId, run.commitSha, run.imageUri].some((value) => value?.toLowerCase().includes(needle))
    const matchesStatus = runFilter.value === 'ALL'
      || (runFilter.value === 'HEALTHY' && run.deployStatus === 'HEALTHY')
      || (runFilter.value === 'FAILED' && ['FAILED', 'HEALTH_FAILED'].includes(run.deployStatus))
      || (runFilter.value === 'ACTIVE' && ['RUNNING', 'QUEUED', 'TRIGGERING', 'TRIGGERED', 'VERIFYING'].includes(run.deployStatus))
    return matchesText && matchesStatus
  })
})
const activeDeployments = computed(() => activity.value.filter((item) => ['TRIGGERING', 'TRIGGERED', 'VERIFYING'].includes(item.status)).length)
const deliverySteps = computed(() => {
  const run = latestRun.value
  const successful = (value: string) => ['SUCCEEDED', 'PASSED', 'HEALTHY'].includes(value)
  const failed = (value: string) => ['FAILED', 'CANCELLED', 'UNHEALTHY', 'HEALTH_FAILED'].includes(value)
  return [
    { label: '代码提交', detail: run ? run.commitSha.slice(0, 12) : '等待 Commit', state: run ? 'done' : 'idle' },
    { label: '测试与扫描', detail: run ? `${statusLabel(run.testStatus)} · ${statusLabel(run.securityStatus)}` : 'Quality gates', state: run && successful(run.testStatus) && successful(run.securityStatus) ? 'done' : run && (failed(run.testStatus) || failed(run.securityStatus)) ? 'failed' : 'active' },
    { label: '不可变镜像', detail: run?.imageUri ? run.imageUri.split('/').pop() || run.imageUri : '等待 Image', state: run?.imageUri ? 'done' : run && failed(run.status) ? 'failed' : 'idle' },
    { label: '生产部署', detail: run ? statusLabel(run.deployStatus) : '等待 Deploy', state: run && run.deployStatus === 'HEALTHY' ? 'done' : run && failed(run.deployStatus) ? 'failed' : run && ['QUEUED', 'TRIGGERED', 'VERIFYING', 'TRIGGERING'].includes(run.deployStatus) ? 'active' : 'idle' },
  ]
})
const releaseGuidance = computed(() => {
  if (!configuration.value) return '先完成仓库与部署平台配置，DevPilot 才能接收签名流水线回调。'
  const run = latestRun.value
  if (!run) return '配置已就绪。向受保护分支推送代码，开始第一条流水线。'
  if (run.deployStatus === 'HEALTHY') return '最新版本已经通过部署后健康验证，可以安全提供服务。'
  if (['FAILED', 'HEALTH_FAILED'].includes(run.deployStatus)) return '最新发布未通过。旧健康版本仍被保留，请查看错误或回滚记录。'
  if (run.deployStatus === 'QUEUED') return '已有版本正在发布；当前版本已进入持久队列，前一个发布结束后会自动继续。'
  if (['TRIGGERED', 'VERIFYING', 'TRIGGERING'].includes(run.deployStatus)) return '发布正在执行，DevPilot 会等待 Provider 完成并使用新的 Agent 探测结果验证。'
  return '构建结果已收到；生产部署需要 CI 平台审批或手动运行 Production workflow。'
})
const imageRepositoryError = computed(() => {
  const value = imageRepository.value.trim()
  if (!value) return '请填写镜像仓库 Image repository'
  if (value !== value.toLowerCase()) return '镜像仓库请使用小写字符'
  if (!/^(?:[a-z0-9]+(?:[._:-][a-z0-9]+)*\/)+(?:[a-z0-9]+(?:[._-][a-z0-9]+)*)+$/.test(value)) return '格式示例：ghcr.io/owner/repository'
  return ''
})
const callbackAbsoluteUrl = computed(() => {
  if (!configuration.value) return ''
  try { return new URL(configuration.value.callbackUrl, window.location.origin).toString() }
  catch { return configuration.value.callbackUrl }
})
const generatedWorkflow = computed(() => {
  if (!configuration.value || imageRepositoryError.value) return null
  return generateWorkflow({
    provider: configuration.value.repositoryProvider,
    runtime: runtimePreset.value,
    branch: configuration.value.branchName,
    imageRepository: imageRepository.value.trim(),
    callbackUrl: callbackAbsoluteUrl.value,
    applicationCode: configuration.value.applicationCode,
  })
})
const callbackIsLocal = computed(() => /\b(?:localhost|127\.0\.0\.1|0\.0\.0\.0)\b/.test(callbackAbsoluteUrl.value))

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

function suggestedImageRepository(repositoryUrl: string, provider: string) {
  try {
    const url = new URL(repositoryUrl)
    const slug = url.pathname.replace(/^\/+|\/?\.git\/?$/g, '').replace(/[^a-zA-Z0-9._/-]/g, '').toLowerCase()
    if (!slug) return ''
    if (provider === 'GITLAB') return `registry.gitlab.com/${slug}`
    return `ghcr.io/${slug}`
  } catch { return '' }
}

function useSuggestedImageRepository() {
  const current = configuration.value
  imageRepository.value = current ? suggestedImageRepository(current.repositoryUrl, current.repositoryProvider) : ''
}

async function initialize() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [applicationList, recentActivity] = await Promise.all([applicationApi.list(), cicdApi.activity()])
    applications.value = applicationList
    activity.value = recentActivity
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
    const [configurationResult, pipelineRuns, deploymentHistory, runtimeApplication] = await Promise.all([
      cicdApi.configuration(selectedId.value).catch(() => null),
      cicdApi.runs(selectedId.value),
      cicdApi.deployments(selectedId.value),
      applicationApi.get(selectedId.value),
    ])
    configuration.value = configurationResult
    if (!silent) {
      configurationExpanded.value = !configurationResult
      imageRepository.value = configurationResult ? suggestedImageRepository(configurationResult.repositoryUrl, configurationResult.repositoryProvider) : ''
    }
    runs.value = pipelineRuns
    deployments.value = deploymentHistory
    activity.value = await cicdApi.activity()
    const applicationIndex = applications.value.findIndex((item) => item.id === runtimeApplication.id)
    if (applicationIndex >= 0) applications.value[applicationIndex] = runtimeApplication
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
    onboardingOpen.value = true
    imageRepository.value = suggestedImageRepository(saved.repositoryUrl, saved.repositoryProvider)
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

function downloadWorkflow() {
  const workflow = generatedWorkflow.value
  if (!workflow) return
  const url = URL.createObjectURL(new Blob([workflow.content], { type: 'text/yaml;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = workflow.fileName.split('/').pop() || 'devpilot.yml'
  link.click()
  URL.revokeObjectURL(url)
  successMessage.value = `已下载 ${link.download}，请放入仓库路径 ${workflow.fileName}`
}

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

function tone(value: string) {
  if (['SUCCEEDED', 'PASSED', 'HEALTHY'].includes(value)) return 'success'
  if (['FAILED', 'CANCELLED', 'UNHEALTHY', 'HEALTH_FAILED'].includes(value)) return 'danger'
  if (['RUNNING', 'QUEUED', 'TRIGGERING', 'TRIGGERED', 'VERIFYING'].includes(value)) return 'running'
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

    <section v-if="activity.length" class="activity-panel">
      <header><div><span>全局发布活动 · DEPLOYMENT ACTIVITY</span><strong>最近发布与回滚</strong><small>{{ activeDeployments ? `${activeDeployments} 个任务正在执行或验证` : '当前没有进行中的发布' }}</small></div><span class="activity-live"><i />LIVE</span></header>
      <div class="activity-list">
        <RouterLink v-for="item in activity.slice(0, 8)" :key="item.id" :to="{ path: '/cicd', query: { application: item.applicationId } }" @click="selectedId = item.applicationId">
          <span class="activity-kind" :class="item.deploymentKind.toLowerCase()">{{ item.deploymentKind === 'ROLLBACK' ? '回滚' : '发布' }}</span>
          <div><strong>{{ item.applicationName }} <small>{{ item.environment }}</small></strong><code>{{ item.imageUri }}</code><p>{{ item.serverName }} · {{ item.provider }}<template v-if="item.logExcerpt"> · {{ item.logExcerpt }}</template></p></div>
          <aside><span class="pipeline-state" :class="tone(item.status)">{{ statusLabel(item.status) }}</span><time>{{ formatTime(item.updatedAt) }}</time></aside>
        </RouterLink>
      </div>
    </section>

    <template v-if="selectedApp">
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
        <div class="drift-state" :class="imageDrift.state"><span>运行一致性 Runtime drift</span><strong>{{ imageDrift.label }}</strong><small :title="imageDrift.detail">{{ imageDrift.detail }}</small></div>
        <button type="button" @click="configurationExpanded = true">编辑配置</button>
      </section>

      <section v-if="configuration" class="onboarding-panel" :class="{ expanded: onboardingOpen }">
        <header>
          <div class="onboarding-title"><span>仓库接入向导 · REPOSITORY ONBOARDING</span><strong>生成一条真正可执行的交付流水线</strong><p>测试、安全扫描、构建不可变镜像，再把签名发布凭证交给 DevPilot。</p></div>
          <button type="button" @click="onboardingOpen = !onboardingOpen">{{ onboardingOpen ? '收起向导' : '开始接入 Setup' }}</button>
        </header>
        <div v-if="onboardingOpen" class="onboarding-body">
          <ol class="onboarding-steps">
            <li class="done"><span>1</span><div><strong>连接信息</strong><small>{{ configuration.repositoryProvider }} · {{ configuration.branchName }}</small></div></li>
            <li :class="{ done: !imageRepositoryError }"><span>2</span><div><strong>生成配置</strong><small>{{ runtimePreset }} · {{ imageRepository || '等待镜像仓库' }}</small></div></li>
            <li><span>3</span><div><strong>提交并运行</strong><small>{{ generatedWorkflow?.fileName || '等待有效配置' }}</small></div></li>
          </ol>

          <div class="onboarding-grid">
            <div class="onboarding-config">
              <div class="config-section">
                <span class="section-kicker">01 · BUILD PRESET</span>
                <strong>项目技术栈</strong>
                <div class="runtime-options">
                  <button v-for="preset in runtimePresets" :key="preset" type="button" :class="{ active: runtimePreset === preset }" @click="runtimePreset = preset">{{ preset }}</button>
                </div>
                <small>会生成对应的测试步骤；Docker 预设要求 Dockerfile 中存在 <code>test</code> stage。</small>
              </div>

              <div class="config-section">
                <span class="section-kicker">02 · IMAGE DESTINATION</span>
                <strong>镜像仓库</strong>
                <label><input v-model.trim="imageRepository" spellcheck="false" placeholder="ghcr.io/owner/repository" /><button type="button" @click="useSuggestedImageRepository">自动填充</button></label>
                <small v-if="imageRepositoryError" class="input-error">{{ imageRepositoryError }}</small>
                <small v-else>镜像标签固定为 <code>sha-&lt;commit&gt;</code>，避免 latest 带来的版本漂移。</small>
              </div>

              <div class="config-section secrets-section">
                <span class="section-kicker">03 · PROTECTED SECRETS</span>
                <strong>在 CI 平台创建 Secrets</strong>
                <ul v-if="generatedWorkflow"><li v-for="secret in generatedWorkflow.secrets" :key="secret"><code>{{ secret }}</code><span>{{ secret.toLowerCase().includes('url') ? callbackAbsoluteUrl : secret.toLowerCase().includes('callback_secret') ? (revealedSecret ? '使用右侧的一次性密钥' : '请轮换回调密钥后立即保存') : '镜像仓库凭据' }}</span></li></ul>
                <p v-if="callbackIsLocal" class="local-warning">当前回调是本机地址，云端 CI 无法访问。正式使用时请通过域名/HTTPS 暴露 DevPilot，再重新生成此文件。</p>
              </div>

              <div v-if="generatedWorkflow" class="generator-notes">
                <p v-for="note in generatedWorkflow.notes" :key="note">✓ {{ note }}</p>
              </div>
            </div>

            <div class="workflow-preview">
              <header><div><span>生成文件 Generated file</span><strong>{{ generatedWorkflow?.fileName || '请先完成配置' }}</strong></div><div><button type="button" :disabled="!generatedWorkflow" @click="generatedWorkflow && copy(generatedWorkflow.content)">复制 Copy</button><button class="download" type="button" :disabled="!generatedWorkflow" @click="downloadWorkflow">下载 YAML</button></div></header>
              <pre v-if="generatedWorkflow"><code>{{ generatedWorkflow.content }}</code></pre>
              <div v-else class="workflow-empty">填写有效镜像仓库后，在这里生成完整流水线。</div>
              <footer v-if="generatedWorkflow"><span>下一步</span><p>把文件保存到上述路径并提交；在 CI 平台配置 Secrets，然后手动运行生产任务。</p></footer>
            </div>
          </div>
        </div>
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
.connection-strip{display:grid;grid-template-columns:1.15fr .72fr .72fr 1fr auto;align-items:center;gap:0;margin-top:14px;border:1px solid var(--line);border-radius:14px;padding:14px 16px;background:var(--panel)}.connection-strip>div{min-width:0;padding:3px 18px;border-right:1px solid var(--line)}.connection-strip>div:first-child{padding-left:4px}.connection-strip span,.connection-strip strong,.connection-strip small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.connection-strip span{color:#68778c;font-size:10px}.connection-strip strong{margin-top:6px;font-size:12px}.connection-strip small{margin-top:4px;color:#65748a;font-size:10px}.connection-strip>button{margin-left:16px}.drift-state.synced strong{color:#22c55e}.drift-state.drift strong{color:#f59e0b}.drift-state.unknown strong{color:#94a3b8}
.cicd-layout{grid-template-columns:minmax(0,1.25fr) 390px;gap:16px;margin-top:16px}.cicd-panel,.callback-panel,.pipeline-panel{border-radius:14px}.cicd-panel>header,.callback-panel>header,.pipeline-panel>header{min-height:68px;padding:0 20px}.cicd-panel header strong,.callback-panel header strong,.pipeline-panel header strong{font-size:14px}.cicd-panel header small,.callback-panel header small,.pipeline-panel header small{font-size:11px}.cicd-panel>header>span{font-size:10px}.cicd-form{gap:17px;padding:20px}.cicd-form label:not(.check){gap:8px;font-size:12px}.cicd-form label small{font-size:10px;line-height:1.5}.check{gap:10px;font-size:12px}.check input{width:16px;height:16px}.cicd-form .primary-action{width:auto;height:42px;padding:0 18px;font-size:12px}.callback-body{gap:16px;padding:20px}.callback-body label>span,.callback-body label>small{font-size:11px}.callback-body code{padding:11px;font-size:10px}.callback-body button{font-size:11px}.callback-body p,.callback-empty{font-size:11px}.callback-empty{padding:22px}
.pipeline-panel{margin-top:16px}.pipeline-heading{gap:16px}.pipeline-tools{display:flex;align-items:center;gap:8px}.pipeline-tools input,.pipeline-tools select{height:36px;border:1px solid var(--line);border-radius:8px;outline:0;padding:0 10px;color:var(--text);background:rgba(15,23,42,.22);font-size:11px}.pipeline-tools input{width:190px}.pipeline-panel>header>button,.pipeline-tools button{height:36px;font-size:11px}.pipeline-state{border-radius:6px;padding:5px 8px;font-size:9px;white-space:nowrap}.pipeline-table td,.deployment-table td{font-size:11px}.pipeline-table td:first-child code{font-size:10px}.image-uri{max-width:310px;font-size:10px}.pipeline-table td small,.deployment-table td small{font-size:10px}.deployment-log summary{font-size:10px}.deployment-log pre{font:10px/1.6 ui-monospace,monospace}.rollback-action{height:34px;padding:0 11px;font-size:10px}.empty-panel p{font-size:12px}
.activity-panel{overflow:hidden;margin:0 0 18px;border:1px solid var(--line);border-radius:15px;background:var(--panel);box-shadow:0 8px 28px rgba(38,57,84,.04)}.activity-panel>header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid var(--line)}.activity-panel>header div>span,.activity-panel>header strong,.activity-panel>header small{display:block}.activity-panel>header div>span{color:#76a7ff;font-size:9px;font-weight:850;letter-spacing:.13em}.activity-panel>header strong{margin-top:6px;font-size:14px}.activity-panel>header small{margin-top:4px;color:var(--muted);font-size:10px}.activity-live{display:flex!important;align-items:center;gap:6px;border:1px solid rgba(34,197,94,.18);border-radius:20px;padding:5px 8px;color:#22c55e;font-size:9px!important;font-weight:800}.activity-live i{width:6px;height:6px;border-radius:50%;background:currentColor}.activity-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;background:var(--line)}.activity-list>a{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;gap:12px;min-width:0;padding:15px 18px;color:inherit;background:var(--panel-solid);text-decoration:none}.activity-list>a:hover{background:rgba(59,130,246,.035)}.activity-kind{border:1px solid rgba(59,130,246,.18);border-radius:7px;padding:5px 7px;color:#60a5fa;background:rgba(59,130,246,.06);font-size:9px;font-weight:800}.activity-kind.rollback{border-color:rgba(245,158,11,.2);color:#f59e0b;background:rgba(245,158,11,.06)}.activity-list>a>div{min-width:0}.activity-list strong,.activity-list code,.activity-list p{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.activity-list strong{font-size:12px}.activity-list strong small{display:inline;margin-left:5px;color:var(--muted);font-size:9px}.activity-list code{margin-top:6px;color:#8f7dd3;font-size:10px}.activity-list p{margin:5px 0 0;color:var(--muted);font-size:9px}.activity-list aside{display:grid;justify-items:end;gap:8px}.activity-list time{color:var(--muted);font-size:8px;white-space:nowrap}
.onboarding-panel{overflow:hidden;margin-top:16px;border:1px solid rgba(59,130,246,.2);border-radius:16px;background:linear-gradient(135deg,rgba(239,246,255,.06),var(--panel) 42%);box-shadow:0 10px 32px rgba(38,57,84,.04)}.onboarding-panel>header{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px}.onboarding-title>span,.onboarding-title>strong{display:block}.onboarding-title>span,.section-kicker{color:#6595e7;font-size:9px;font-weight:850;letter-spacing:.13em}.onboarding-title>strong{margin-top:7px;font-size:16px}.onboarding-title>p{margin:5px 0 0;color:var(--muted);font-size:11px}.onboarding-panel>header>button{height:38px;flex:0 0 auto;border:0;border-radius:9px;padding:0 14px;color:#fff;background:#2563eb;font-size:11px;font-weight:800}.onboarding-panel.expanded>header{border-bottom:1px solid var(--line)}.onboarding-body{padding:20px}.onboarding-steps{display:grid;grid-template-columns:repeat(3,1fr);margin:0 0 18px;padding:0;list-style:none}.onboarding-steps li{position:relative;display:grid;grid-template-columns:30px minmax(0,1fr);align-items:center;gap:10px}.onboarding-steps li:not(:last-child)::after{content:'';position:absolute;top:15px;right:14px;left:178px;height:1px;background:var(--line)}.onboarding-steps li>span{display:grid;width:30px;height:30px;place-items:center;border:1px solid var(--line);border-radius:50%;color:#718096;background:var(--panel-solid);font-size:10px;font-weight:850}.onboarding-steps li.done>span{border-color:rgba(34,197,94,.25);color:#16803d;background:rgba(34,197,94,.08)}.onboarding-steps strong,.onboarding-steps small{display:block;overflow:hidden;max-width:250px;text-overflow:ellipsis;white-space:nowrap}.onboarding-steps strong{font-size:11px}.onboarding-steps small{margin-top:4px;color:var(--muted);font-size:9px}.onboarding-grid{display:grid;grid-template-columns:minmax(310px,.72fr) minmax(0,1.28fr);overflow:hidden;border:1px solid var(--line);border-radius:13px;background:var(--panel-solid)}.onboarding-config{padding:20px;border-right:1px solid var(--line)}.config-section{padding:0 0 19px}.config-section+.config-section{padding-top:19px;border-top:1px solid var(--line)}.config-section>strong{display:block;margin:7px 0 11px;font-size:13px}.config-section>small{display:block;margin-top:9px;color:var(--muted);font-size:10px;line-height:1.55}.config-section code{color:#536a8c;font:10px ui-monospace,monospace}.runtime-options{display:grid;grid-template-columns:repeat(4,1fr);gap:6px}.runtime-options button{height:34px;border:1px solid var(--line);border-radius:8px;color:#5f7088;background:transparent;font-size:10px;font-weight:800}.runtime-options button.active{border-color:#6b9be9;color:#1d5fbe;background:rgba(59,130,246,.08);box-shadow:inset 0 0 0 1px rgba(59,130,246,.08)}.config-section label{display:grid;grid-template-columns:minmax(0,1fr) auto}.config-section label input{min-width:0;height:40px;border:1px solid var(--line);border-radius:9px 0 0 9px;outline:0;padding:0 11px;color:var(--text);background:var(--panel);font:10px ui-monospace,monospace}.config-section label input:focus{border-color:#78a5ed}.config-section label button{border:1px solid #78a5ed;border-left:0;border-radius:0 9px 9px 0;padding:0 11px;color:#286ac2;background:rgba(59,130,246,.07);font-size:10px;font-weight:750}.config-section .input-error{color:#dc5b5b}.secrets-section ul{display:grid;gap:7px;margin:0;padding:0;list-style:none}.secrets-section li{display:grid;gap:4px;border:1px solid var(--line);border-radius:8px;padding:9px 10px;background:rgba(148,163,184,.035)}.secrets-section li code{color:#286ac2;font-weight:750}.secrets-section li span{overflow:hidden;color:var(--muted);font-size:9px;text-overflow:ellipsis;white-space:nowrap}.secrets-section .local-warning{margin:10px 0 0;border:1px solid rgba(245,158,11,.25);border-radius:8px;padding:10px;color:#a56509;background:rgba(245,158,11,.06);font-size:10px;line-height:1.55}.generator-notes{display:grid;gap:6px}.generator-notes p{margin:0;color:#59708e;font-size:10px;line-height:1.5}.workflow-preview{display:grid;min-width:0;grid-template-rows:auto minmax(360px,1fr) auto;background:#101827}.workflow-preview>header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 15px;border-bottom:1px solid rgba(255,255,255,.08)}.workflow-preview>header span,.workflow-preview>header strong{display:block}.workflow-preview>header span{color:#7f91aa;font-size:8px}.workflow-preview>header strong{margin-top:4px;color:#d9e4f2;font:10px ui-monospace,monospace}.workflow-preview>header>div:last-child{display:flex;gap:6px}.workflow-preview button{height:31px;border:1px solid rgba(255,255,255,.13);border-radius:7px;padding:0 10px;color:#c2d0e2;background:rgba(255,255,255,.04);font-size:9px;font-weight:750}.workflow-preview button.download{border-color:#3878d1;color:#fff;background:#286ac2}.workflow-preview button:disabled{cursor:not-allowed;opacity:.4}.workflow-preview pre{max-height:620px;overflow:auto;margin:0;padding:17px}.workflow-preview pre code{color:#c5d1df;font:10px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre}.workflow-empty{display:grid;place-items:center;padding:40px;color:#718096;font-size:11px}.workflow-preview>footer{display:grid;grid-template-columns:auto 1fr;gap:10px;padding:12px 15px;border-top:1px solid rgba(255,255,255,.08);color:#8ea0b8}.workflow-preview>footer span{color:#72a4ef;font-size:9px;font-weight:850}.workflow-preview>footer p{margin:0;font-size:9px;line-height:1.5}
@media(max-width:1100px){.delivery-flow{grid-template-columns:1fr 1fr}.delivery-flow li:nth-child(2){border-right:0}.delivery-flow li:nth-child(-n+2){border-bottom:1px solid var(--line)}.connection-strip{grid-template-columns:1fr 1fr}.connection-strip>div{border:0;border-bottom:1px solid var(--line);padding:12px}.connection-strip>button{margin:12px}.cicd-layout{grid-template-columns:1fr}.activity-list{grid-template-columns:1fr}}
@media(max-width:1100px){.onboarding-grid{grid-template-columns:1fr}.onboarding-config{border-right:0;border-bottom:1px solid var(--line)}.onboarding-steps li:not(:last-child)::after{display:none}}
@media(max-width:700px){.delivery-overview>header{padding:20px;flex-direction:column}.delivery-actions{width:100%}.delivery-actions a,.delivery-actions button{flex:1;justify-content:center}.delivery-flow{grid-template-columns:1fr}.delivery-flow li{border-right:0!important;border-bottom:1px solid var(--line)!important}.delivery-flow li:last-child{border-bottom:0!important}.connection-strip{grid-template-columns:1fr}.pipeline-heading{align-items:stretch!important;flex-direction:column}.pipeline-tools{display:grid;grid-template-columns:1fr 1fr}.pipeline-tools input{grid-column:1/-1;width:100%}.pipeline-tools button{height:36px!important}.cicd-summary article{padding:16px}.cicd-heading p{font-size:12px}.onboarding-panel>header{align-items:flex-start;flex-direction:column}.onboarding-panel>header>button{width:100%}.onboarding-body{padding:14px}.onboarding-steps{grid-template-columns:1fr;gap:10px}.runtime-options{grid-template-columns:1fr 1fr}.onboarding-config{padding:15px}.workflow-preview>header{align-items:flex-start;flex-direction:column}.workflow-preview>header>div:last-child{width:100%}.workflow-preview>header button{flex:1}.workflow-preview pre{max-height:500px}.onboarding-title>p{line-height:1.5}}
:global(:root[data-theme='light']) .cicd-panel,:global(:root[data-theme='light']) .callback-panel,:global(:root[data-theme='light']) .pipeline-panel,:global(:root[data-theme='light']) .connection-strip{box-shadow:0 8px 28px rgba(38,57,84,.045)}
:global(:root[data-theme='light']) .delivery-overview>header p{color:#5f7088}
:global(:root[data-theme='light']) .delivery-actions a,:global(:root[data-theme='light']) .connection-strip>button{color:#41658f;background:#fff}
:global(:root[data-theme='light']) .delivery-flow li>span{color:#63748b;background:#f7f9fc}
:global(:root[data-theme='light']) .pipeline-tools input,:global(:root[data-theme='light']) .pipeline-tools select{background:#f8fafc}
:global(:root[data-theme='light']) .callback-body code,:global(:root[data-theme='light']) .deployment-log pre{color:#315c94;background:#f3f6fa}
:global(:root[data-theme='light']) .cicd-success,:global(:root[data-theme='light']) .cicd-panel>header>span.live,:global(:root[data-theme='light']) .pipeline-state.success{color:#16803d}
:global(:root[data-theme='light']) .onboarding-panel{background:linear-gradient(135deg,#f4f8ff,#fff 42%)}
:global(:root[data-theme='light']) .onboarding-grid{background:#fff}
:global(:root[data-theme='light']) .config-section label input{background:#fbfcfe}
</style>
