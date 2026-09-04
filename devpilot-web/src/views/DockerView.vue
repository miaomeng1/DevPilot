<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { dockerApi, type DockerContainer, type DockerOverview } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

interface ComposeStack {
  key: string
  project: string
  serverId: string
  serverName: string
  containers: DockerContainer[]
  running: number
  unhealthy: number
  cpuUsage: number
  memoryUsage: number
}

const auth = useAuthStore()
const servers = useServerStore()
const selectedServer = ref('')
const query = ref('')
const stateFilter = ref('all')
const overview = ref<DockerOverview>()
const containers = ref<DockerContainer[]>([])
const loading = ref(false)
const errorMessage = ref('')
const stackMessage = ref('')
const selectedStack = ref<ComposeStack | null>(null)
const stackAction = ref('')
let pollTimer: number | undefined

const stats = computed(() => overview.value ?? {
  containers: 0, running: 0, stopped: 0, images: 0, volumes: 0, networks: 0,
})
const visible = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return containers.value.filter((container) => {
    const matchesState = stateFilter.value === 'all' || container.state === stateFilter.value
    const matchesText = !needle || [container.name, container.image, container.shortId, container.ipAddress, container.composeProject, container.composeService]
      .some((value) => value?.toLowerCase().includes(needle))
    return matchesState && matchesText
  })
})
const canOperate = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const composeStacks = computed<ComposeStack[]>(() => {
  const grouped = new Map<string, DockerContainer[]>()
  for (const container of containers.value) {
    if (!container.composeProject) continue
    const key = `${container.serverId}:${container.composeProject}`
    grouped.set(key, [...(grouped.get(key) || []), container])
  }
  return [...grouped.entries()].map(([key, stackContainers]) => {
    const first = stackContainers[0]!
    const project = first.composeProject || 'compose'
    return {
      key, project, serverId: first.serverId,
      serverName: servers.servers.find((server) => server.id === first.serverId)?.name || '未知服务器',
      containers: stackContainers.sort((left, right) => (left.composeService || left.name).localeCompare(right.composeService || right.name)),
      running: stackContainers.filter((container) => container.state === 'running').length,
      unhealthy: stackContainers.filter((container) => container.health === 'unhealthy' || container.state === 'dead').length,
      cpuUsage: stackContainers.reduce((total, container) => total + container.cpuUsage, 0),
      memoryUsage: stackContainers.reduce((total, container) => total + Number(container.memoryUsage), 0),
    }
  }).sort((left, right) => Number(right.unhealthy > 0) - Number(left.unhealthy > 0) || left.project.localeCompare(right.project))
})

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const [summary, list] = await Promise.all([
      dockerApi.overview(selectedServer.value || undefined),
      dockerApi.list(selectedServer.value || undefined),
    ])
    overview.value = summary
    containers.value = list
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Docker 数据加载失败')
  } finally {
    loading.value = false
  }
}

function bytes(value: string) {
  let amount = Number(value)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1 }
  return `${amount.toFixed(index ? 1 : 0)} ${units[index]}`
}

function memory(container: DockerContainer) {
  const limit = Number(container.memoryLimit)
  const usage = Number(container.memoryUsage)
  return limit > 0 ? `${bytes(container.memoryUsage)} / ${bytes(container.memoryLimit)} · ${(usage / limit * 100).toFixed(1)}%` : bytes(container.memoryUsage)
}

function stateClass(container: DockerContainer) {
  if (container.state === 'running') return container.health === 'unhealthy' ? 'warning' : 'online'
  return container.state === 'exited' || container.state === 'dead' ? 'offline' : 'unknown'
}

async function restartStack() {
  const stack = selectedStack.value
  if (!stack || stackAction.value) return
  const targets = stack.containers.filter((container) => container.state === 'running')
  stackAction.value = stack.key
  errorMessage.value = ''
  stackMessage.value = ''
  let queued = 0
  try {
    for (const container of targets) {
      await dockerApi.operate(container.id, 'restart')
      queued += 1
    }
    stackMessage.value = `${stack.project} 的 ${queued} 个运行中服务已按顺序加入重启队列。`
    selectedStack.value = null
    await load(true)
  } catch (error) {
    errorMessage.value = `${queued}/${targets.length} 个服务已入队。${apiErrorMessage(error, 'Stack 重启请求失败')}`
  } finally {
    stackAction.value = ''
  }
}

onMounted(() => {
  void servers.load()
  void load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="docker-view">
    <header class="page-heading docker-heading"><div><p class="eyebrow">运行时工作负载 · WORKLOAD RUNTIME</p><h1>Docker 运行中心</h1><span>自动发现容器、Compose Stack 与实时资源使用。</span></div><div class="docker-server-filter"><label>服务器 Server</label><select v-model="selectedServer" @change="load()"><option value="">全部服务器 All servers</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></div></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <p v-if="stackMessage" class="stack-success">{{ stackMessage }}</p>

    <div class="docker-overview" :class="{ 'is-loading': loading && !overview }">
      <article><span>容器 Containers</span><strong>{{ stats.containers }}</strong><small>已发现运行单元</small></article>
      <article class="running"><span>运行中 Running</span><strong>{{ stats.running }}</strong><small>正在执行</small></article>
      <article class="stopped"><span>已停止 Stopped</span><strong>{{ stats.stopped }}</strong><small>Exited / Created</small></article>
      <article><span>镜像 Images</span><strong>{{ stats.images }}</strong><small>本地镜像清单</small></article>
      <article><span>存储卷 Volumes</span><strong>{{ stats.volumes }}</strong><small>持久化存储</small></article>
      <article><span>网络 Networks</span><strong>{{ stats.networks }}</strong><small>Docker 网络</small></article>
    </div>

    <section v-if="composeStacks.length" class="compose-panel">
      <header><div><span>COMPOSE STACKS</span><strong>关联服务视图</strong><small>按 Docker Compose project 标签自动分组，不需要手工维护。</small></div><b>{{ composeStacks.length }} STACKS</b></header>
      <div class="compose-grid">
        <article v-for="stack in composeStacks" :key="stack.key" :class="{ attention: stack.unhealthy }">
          <header><div class="stack-mark">{{ stack.project.slice(0, 2).toUpperCase() }}</div><div><strong>{{ stack.project }}</strong><small>{{ stack.serverName }} · {{ stack.running }}/{{ stack.containers.length }} 运行中</small></div><span :class="stack.unhealthy ? 'warning' : 'healthy'"><i />{{ stack.unhealthy ? `${stack.unhealthy} ATTENTION` : 'HEALTHY' }}</span></header>
          <div class="stack-services"><RouterLink v-for="container in stack.containers" :key="container.id" :to="`/docker/containers/${container.id}`"><i :class="stateClass(container)" /><span>{{ container.composeService || container.name }}</span><small>{{ container.state }}</small></RouterLink></div>
          <footer><div><span>CPU {{ stack.cpuUsage.toFixed(1) }}%</span><span>内存 {{ bytes(String(stack.memoryUsage)) }}</span></div><button v-if="canOperate && stack.running" type="button" @click="selectedStack = stack">管理 Stack</button></footer>
        </article>
      </div>
    </section>

    <article class="docker-table-panel">
      <header><div class="table-search"><span>⌕</span><input v-model="query" placeholder="搜索容器、Stack、服务、镜像、ID 或 IP" /></div><div class="docker-table-tools"><div class="state-tabs"><button v-for="state in ['all','running','exited']" :key="state" :class="{ active: stateFilter === state }" @click="stateFilter = state">{{ state }}</button></div><button class="refresh-button" @click="load()">{{ loading ? '刷新中…' : '刷新 Refresh' }}</button></div></header>
      <div v-if="loading && !overview" class="table-empty"><span class="loading-ring" /><strong>正在读取 Docker Engine</strong></div>
      <div v-else-if="!visible.length" class="table-empty"><span class="server-empty-glyph">⬡</span><strong>没有找到容器</strong><small>{{ selectedServer ? 'Agent 尚未上报这台服务器的容器。' : '请连接一台允许 Agent 访问 Docker Socket 的服务器。' }}</small></div>
      <div v-else class="server-table-wrap"><table class="server-table docker-table"><thead><tr><th>容器 Container</th><th>状态 State</th><th>镜像 Image</th><th>CPU</th><th>内存 Memory</th><th>网络 Network</th><th>IP / 端口</th></tr></thead><tbody><tr v-for="container in visible" :key="container.id">
        <td><RouterLink class="node-cell node-link" :to="`/docker/containers/${container.id}`"><span>⬡</span><div><strong>{{ container.name }}</strong><small class="mono">{{ container.shortId }}</small></div></RouterLink></td>
        <td><span class="status-badge" :class="stateClass(container)"><i />{{ container.state.toUpperCase() }}</span><small class="cell-secondary">{{ container.health || container.status }}</small></td>
        <td><strong class="cell-primary docker-image">{{ container.image }}</strong><small class="cell-secondary">{{ container.composeProject ? `${container.composeProject} / ${container.composeService}` : (container.networkMode || 'default network') }}</small></td>
        <td><strong class="cell-primary metric-value">{{ container.cpuUsage.toFixed(1) }}%</strong></td>
        <td><strong class="cell-primary">{{ memory(container) }}</strong></td>
        <td><strong class="cell-primary">↓ {{ bytes(container.networkRx) }}</strong><small class="cell-secondary">↑ {{ bytes(container.networkTx) }}</small></td>
        <td><strong class="cell-primary mono">{{ container.ipAddress || '—' }}</strong><small class="cell-secondary">{{ container.ports[0] || 'No published ports' }}</small></td>
      </tr></tbody></table></div>
    </article>

    <div v-if="selectedStack" class="stack-modal-backdrop" @click.self="selectedStack = null">
      <section class="stack-dialog" role="dialog" aria-modal="true" aria-labelledby="stack-dialog-title">
        <header><div><span>STACK OPERATION</span><h2 id="stack-dialog-title">管理 {{ selectedStack.project }}</h2><p>{{ selectedStack.serverName }} · {{ selectedStack.containers.length }} 个关联服务</p></div><button type="button" aria-label="关闭" @click="selectedStack = null">×</button></header>
        <div class="stack-dialog-services"><div v-for="container in selectedStack.containers" :key="container.id"><i :class="stateClass(container)" /><strong>{{ container.composeService || container.name }}</strong><small>{{ container.name }} · {{ container.state }}</small></div></div>
        <aside><strong>受控顺序重启 Stack</strong><p>只重启当前正在运行的容器，并按上方顺序逐个加入 Agent 命令队列。数据库和持久卷不会删除，但服务会出现短暂中断。</p></aside>
        <footer><button type="button" @click="selectedStack = null">取消</button><button class="stack-restart" type="button" :disabled="!!stackAction" @click="restartStack">{{ stackAction ? '正在入队…' : `确认重启 ${selectedStack.running} 个服务` }}</button></footer>
      </section>
    </div>
  </section>
</template>
