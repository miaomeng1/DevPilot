<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { applicationApi, type Application, type ApplicationPayload } from '@/api/applications'
import { dockerApi, type DockerContainer } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const servers = useServerStore()
const applications = ref<Application[]>([])
const containers = ref<DockerContainer[]>([])
const discoveredContainers = ref<DockerContainer[]>([])
const loading = ref(false)
const discoveryLoading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const query = ref('')
const environmentFilter = ref('ALL')
const statusFilter = ref('ALL')
const errorMessage = ref('')
const discoveryError = ref('')
let pollTimer: number | undefined

const form = reactive<ApplicationPayload>({
  name: '', code: '', description: '', environment: 'DEV', serverId: '', containerSnapshotId: '',
  currentVersion: '', healthCheckUrl: '', accessUrl: '',
})

const canManage = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const selectedContainer = computed(() => containers.value.find((container) => container.id === form.containerSnapshotId) || null)
const selectedServer = computed(() => servers.servers.find((server) => server.id === form.serverId) || null)
const unmanagedContainers = computed(() => {
  const snapshotIds = new Set(applications.value.map((application) => application.containerSnapshotId).filter(Boolean))
  const containerIds = new Set(applications.value.map((application) => application.containerId).filter(Boolean))
  return discoveredContainers.value
    .filter((container) => !snapshotIds.has(container.id) && !containerIds.has(container.containerId))
    .sort((left, right) => Number(right.state.toLowerCase() === 'running') - Number(left.state.toLowerCase() === 'running'))
})
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return applications.value.filter((application) => {
    const environmentMatches = environmentFilter.value === 'ALL' || application.environment === environmentFilter.value
    const statusMatches = statusFilter.value === 'ALL'
      || (statusFilter.value === 'HEALTHY' && application.healthStatus === 'HEALTHY')
      || (statusFilter.value === 'ATTENTION' && (application.healthStatus === 'UNHEALTHY' || ['WARNING', 'ERROR', 'OFFLINE'].includes(application.status)))
    const textMatches = !needle || [application.name, application.code, application.serverName, application.containerName, application.dockerImage]
      .some((value) => value?.toLowerCase().includes(needle))
    return environmentMatches && statusMatches && textMatches
  })
})
const summary = computed(() => ({
  total: applications.value.length,
  healthy: applications.value.filter((application) => application.healthStatus === 'HEALTHY').length,
  attention: applications.value.filter((application) => application.healthStatus === 'UNHEALTHY' || ['WARNING', 'ERROR', 'OFFLINE'].includes(application.status)).length,
  production: applications.value.filter((application) => application.environment === 'PRODUCTION').length,
}))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    applications.value = await applicationApi.list()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法加载应用 Applications')
  } finally {
    loading.value = false
  }
}

async function loadDiscovery(silent = false) {
  if (!silent) discoveryLoading.value = true
  discoveryError.value = ''
  try {
    discoveredContainers.value = await dockerApi.list()
  } catch (error) {
    discoveryError.value = apiErrorMessage(error, '无法读取 Docker 容器')
  } finally {
    discoveryLoading.value = false
  }
}

async function refreshCatalog() {
  await Promise.all([load(), loadDiscovery()])
}

async function loadContainers(preferredId = '') {
  form.containerSnapshotId = preferredId
  containers.value = form.serverId ? await dockerApi.list(form.serverId) : []
  if (!containers.value.some((container) => container.id === form.containerSnapshotId)) {
    form.containerSnapshotId = containers.value.length === 1 ? containers.value[0]!.id : ''
  }
}

function normalizedContainerName(container: DockerContainer) {
  return container.name.replace(/^\/+/, '') || `container-${container.shortId}`
}

function inferredCode(container: DockerContainer) {
  const candidate = normalizedContainerName(container).toLowerCase()
    .replace(/[_\s.]+/g, '-')
    .replace(/[^a-z0-9-]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  const base = !candidate ? `app-${container.shortId.toLowerCase()}` : /^[a-z]/.test(candidate) ? candidate : `app-${candidate}`
  return applications.value.some((application) => application.code === base) ? `${base}-${container.shortId.toLowerCase()}` : base
}

function inferredVersion(container: DockerContainer) {
  const image = container.image.split('@')[0] || ''
  const lastSegment = image.split('/').pop() || ''
  return lastSegment.includes(':') ? lastSegment.slice(lastSegment.lastIndexOf(':') + 1) : 'latest'
}

function inferredAccessUrl(container: DockerContainer) {
  const port = publicPort(container.ports)
  const server = servers.servers.find((item) => item.id === container.serverId)
  return port && (server?.ip || server?.hostname) ? `http://${server.ip || server.hostname}:${port}` : ''
}

function containerServerName(container: DockerContainer) {
  return servers.servers.find((server) => server.id === container.serverId)?.name || '未知服务器'
}

async function openDialog(container?: DockerContainer) {
  Object.assign(form, {
    name: container ? normalizedContainerName(container) : '', code: container ? inferredCode(container) : '',
    description: '', environment: 'DEV', serverId: container?.serverId || servers.servers[0]?.id || '',
    containerSnapshotId: container?.id || '', currentVersion: container ? inferredVersion(container) : '',
    healthCheckUrl: '', accessUrl: container ? inferredAccessUrl(container) : '',
  })
  errorMessage.value = ''
  dialogOpen.value = true
  try {
    await loadContainers(container?.id)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法加载容器 Container')
  }
}

async function createApplication() {
  if (!form.name || !form.code || !form.serverId) return
  saving.value = true
  errorMessage.value = ''
  try {
    const created = await applicationApi.create({ ...form, containerSnapshotId: form.containerSnapshotId || null, code: form.code?.trim().toLowerCase() })
    applications.value.unshift(created)
    dialogOpen.value = false
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法创建应用 Application')
  } finally {
    saving.value = false
  }
}

function statusClass(application: Application) {
  if (application.status === 'RUNNING') return application.healthStatus === 'UNHEALTHY' ? 'warning' : 'online'
  if (application.status === 'WARNING') return 'warning'
  if (application.status === 'ERROR' || application.status === 'OFFLINE') return 'offline'
  return 'unknown'
}

function formatTime(value: string | null) {
  if (!value) return '尚未检查'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function publishedPort(ports: string[]) {
  return ports.find((port) => port.includes('→')) || ''
}

function containerPort(ports: string[]) {
  const first = ports[0] || ''
  const target = first.includes('→') ? first.split('→')[1] || '' : first
  return target.split('/')[0] || ''
}

function publicPort(ports: string[]) {
  const mapping = publishedPort(ports)
  if (!mapping) return ''
  const source = mapping.split('→')[0] || ''
  return source.match(/:(\d+)$/)?.[1] || ''
}

function runtimeEndpoint(application: Application) {
  if (application.accessUrl) return application.accessUrl
  const port = publicPort(application.ports || [])
  const server = servers.servers.find((item) => item.id === application.serverId)
  if (port && (server?.ip || server?.hostname)) return `http://${server.ip || server.hostname}:${port}`
  if (application.containerIpAddress && containerPort(application.ports || [])) {
    return `${application.containerIpAddress}:${containerPort(application.ports || [])} · 内部网络`
  }
  return '未配置访问入口'
}

function useDetectedEndpoints() {
  const container = selectedContainer.value
  const port = container ? publicPort(container.ports) : ''
  if (!container || !port) return
  const host = selectedServer.value?.ip || selectedServer.value?.hostname || '服务器IP'
  if (!form.accessUrl) form.accessUrl = `http://${host}:${port}`
  if (!form.healthCheckUrl) form.healthCheckUrl = `http://127.0.0.1:${port}/healthz`
}

onMounted(async () => {
  await servers.load()
  await Promise.all([load(), loadDiscovery()])
  pollTimer = window.setInterval(() => void Promise.all([load(true), loadDiscovery(true)]), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="applications-view">
    <header class="page-heading applications-heading">
      <div><p class="eyebrow">服务目录 · SERVICE CATALOG</p><h1>应用工作台</h1><span>把镜像、容器、端口、健康检查与发布记录组织成可管理的服务。</span></div>
      <div class="heading-actions"><RouterLink class="secondary-compact" to="/templates">一键模板 Templates</RouterLink><RouterLink class="secondary-compact" to="/cicd">进入发布中心 CI/CD</RouterLink><button v-if="canManage" class="primary-compact" type="button" @click="openDialog()"><b>＋</b>登记应用</button></div>
    </header>
    <p v-if="errorMessage && !dialogOpen" class="inline-error">{{ errorMessage }}</p>

    <section v-if="canManage && (unmanagedContainers.length || discoveryLoading || discoveryError)" class="discovery-panel">
      <header>
        <div><span>自动发现 · AUTO DISCOVERY</span><strong>发现 {{ unmanagedContainers.length }} 个尚未纳管的容器</strong><small>DevPilot 已从 Agent 清单中识别运行实例，确认信息后即可加入应用工作台。</small></div>
        <button type="button" :disabled="discoveryLoading" @click="loadDiscovery()">{{ discoveryLoading ? '扫描中…' : '重新扫描' }}</button>
      </header>
      <p v-if="discoveryError" class="discovery-error">{{ discoveryError }}</p>
      <div v-else-if="unmanagedContainers.length" class="discovery-list">
        <article v-for="container in unmanagedContainers.slice(0, 6)" :key="container.id">
          <div class="discovery-state" :class="container.state.toLowerCase()"><i />{{ container.state }}</div>
          <div class="discovery-identity"><strong>{{ normalizedContainerName(container) }}</strong><code>{{ container.image }}</code><small>{{ containerServerName(container) }} · {{ container.ports.join(' · ') || '无公开端口' }}</small></div>
          <button type="button" @click="openDialog(container)">纳管 Import <b>→</b></button>
        </article>
      </div>
      <footer v-if="unmanagedContainers.length > 6">另有 {{ unmanagedContainers.length - 6 }} 个容器，可在 Docker 页面查看完整清单。</footer>
    </section>

    <div class="application-summary">
      <article><span>全部应用 Applications</span><strong>{{ summary.total }}</strong><small>已登记服务</small></article>
      <article class="healthy"><span>健康 Healthy</span><strong>{{ summary.healthy }}</strong><small>Agent 探测通过</small></article>
      <article class="attention"><span>需处理 Attention</span><strong>{{ summary.attention }}</strong><small>运行或健康异常</small></article>
      <article><span>生产 Production</span><strong>{{ summary.production }}</strong><small>生产环境服务</small></article>
    </div>

    <article class="application-workspace">
      <header class="application-toolbar">
        <div class="table-search"><span>⌕</span><input v-model="query" placeholder="搜索应用、容器、镜像或服务器" /></div>
        <div class="application-tools"><select v-model="statusFilter"><option value="ALL">全部状态</option><option value="HEALTHY">健康 Healthy</option><option value="ATTENTION">需处理 Attention</option></select><select v-model="environmentFilter"><option value="ALL">全部环境</option><option v-for="environment in ['DEV','TEST','STAGING','PRODUCTION']" :key="environment">{{ environment }}</option></select><button class="refresh-button" @click="refreshCatalog()">{{ loading || discoveryLoading ? '刷新中…' : '刷新' }}</button></div>
      </header>
      <div v-if="loading && !applications.length" class="table-empty"><span class="loading-ring" /><strong>正在加载服务目录</strong></div>
      <div v-else-if="!filtered.length" class="table-empty"><span class="server-empty-glyph">◈</span><strong>{{ query ? '没有匹配的应用' : '还没有登记应用' }}</strong><small>绑定一个已发现的 Docker 容器，并配置 Agent 健康检查。</small><button v-if="canManage && !query" @click="openDialog()">登记第一个应用</button></div>
      <div v-else class="application-card-grid">
        <RouterLink v-for="application in filtered" :key="application.id" class="application-card" :to="`/applications/${application.id}`">
          <header><div class="application-identity"><span>{{ application.name.slice(0,2).toUpperCase() }}</span><div><strong>{{ application.name }}</strong><small>{{ application.code }} · {{ application.environment }}</small></div></div><span class="status-badge" :class="statusClass(application)"><i />{{ application.status }}</span></header>
          <div class="application-health-row"><div><span>健康状态 Health</span><strong class="health-label" :class="application.healthStatus.toLowerCase()">{{ application.healthStatus }}</strong><small>{{ application.healthMessage || '等待首次探测' }}</small></div><div><span>当前版本 Version</span><strong>{{ application.currentVersion || '未标记' }}</strong><small>{{ formatTime(application.lastDeployedAt) }}</small></div></div>
          <div class="runtime-block"><span>运行入口 Runtime</span><strong>{{ runtimeEndpoint(application) }}</strong><div class="port-list"><code v-for="port in application.ports" :key="port">{{ port }}</code><code v-if="!application.ports.length">未发现公开端口</code></div></div>
          <dl><div><dt>容器 Container</dt><dd>{{ application.containerName || '不可用' }}</dd></div><div><dt>服务器 Server</dt><dd>{{ application.serverName }}</dd></div></dl>
          <footer><code>{{ application.dockerImage || '镜像不可用' }}</code><span>查看详情 →</span></footer>
        </RouterLink>
      </div>
    </article>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="dialogOpen = false">
      <section class="server-dialog application-dialog" role="dialog" aria-modal="true" aria-labelledby="application-dialog-title">
        <header><div><span>服务目录 · SERVICE CATALOG</span><h2 id="application-dialog-title">登记一个应用</h2></div><button aria-label="关闭" @click="dialogOpen = false">×</button></header>
        <div class="dialog-body application-form">
          <p>应用是稳定的业务对象，即使底层容器因为发布而更换，也能持续追踪。</p>
          <div class="form-grid"><label><span>名称 Name</span><input v-model.trim="form.name" maxlength="120" placeholder="订单服务" /></label><label><span>编码 Code</span><input v-model.trim="form.code" maxlength="64" pattern="[a-z][a-z0-9-]+" placeholder="order-api" /></label></div>
          <div class="form-grid"><label><span>环境 Environment</span><select v-model="form.environment"><option v-for="environment in ['DEV','TEST','STAGING','PRODUCTION']" :key="environment" :value="environment">{{ environment }}</option></select></label><label><span>服务器 Server</span><select v-model="form.serverId" @change="loadContainers()"><option disabled value="">选择服务器</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label></div>
          <label><span>Docker 容器 Container（可选）</span><select v-model="form.containerSnapshotId"><option value="">首次部署后自动关联</option><option v-for="container in containers" :key="container.id" :value="container.id">{{ container.name }} · {{ container.image }} · {{ container.state }}</option></select></label>
          <aside v-if="selectedContainer" class="detected-runtime"><div><span>已识别镜像</span><code>{{ selectedContainer.image }}</code></div><div><span>运行端口</span><code v-for="port in selectedContainer.ports" :key="port">{{ port }}</code><code v-if="!selectedContainer.ports.length">无公开端口</code></div><button v-if="publicPort(selectedContainer.ports)" type="button" @click="useDetectedEndpoints">使用检测到的端口</button></aside>
          <div class="form-grid"><label><span>当前版本 Version</span><input v-model.trim="form.currentVersion" maxlength="120" placeholder="v1.2.3 / sha-abc123" /></label><label><span>访问地址 Access URL</span><input v-model.trim="form.accessUrl" maxlength="1000" placeholder="https://app.example.com" /></label></div>
          <label><span>健康检查 Health URL</span><input v-model.trim="form.healthCheckUrl" maxlength="1000" placeholder="http://127.0.0.1:9090/healthz" /><small>由所选服务器上的 Agent 每 30 秒执行一次 HTTP(S) 探测。</small></label>
          <label><span>说明 Description</span><textarea v-model.trim="form.description" maxlength="1000" rows="3" placeholder="这个服务负责什么，谁依赖它。" /></label>
          <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
        </div>
        <footer><button @click="dialogOpen = false">取消</button><button class="dialog-primary" :disabled="saving || !form.name || !form.code || !form.serverId" @click="createApplication">{{ saving ? '登记中…' : '确认登记' }} <b>→</b></button></footer>
      </section>
    </div>
  </section>
</template>
