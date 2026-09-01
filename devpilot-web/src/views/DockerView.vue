<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { dockerApi, type DockerContainer, type DockerOverview } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useServerStore } from '@/stores/servers'

const servers = useServerStore()
const selectedServer = ref('')
const query = ref('')
const stateFilter = ref('all')
const overview = ref<DockerOverview>()
const containers = ref<DockerContainer[]>([])
const loading = ref(false)
const errorMessage = ref('')
let pollTimer: number | undefined

const stats = computed(() => overview.value ?? {
  containers: 0, running: 0, stopped: 0, images: 0, volumes: 0, networks: 0,
})
const visible = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return containers.value.filter((container) => {
    const matchesState = stateFilter.value === 'all' || container.state === stateFilter.value
    const matchesText = !needle || [container.name, container.image, container.shortId, container.ipAddress]
      .some((value) => value?.toLowerCase().includes(needle))
    return matchesState && matchesText
  })
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

onMounted(() => {
  void servers.load()
  void load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="docker-view">
    <header class="page-heading docker-heading"><div><p class="eyebrow">WORKLOAD RUNTIME</p><h1>Docker</h1><span>Discovered containers and live runtime utilization.</span></div><div class="docker-server-filter"><label>Server</label><select v-model="selectedServer" @change="load()"><option value="">All servers</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></div></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="docker-overview" :class="{ 'is-loading': loading && !overview }">
      <article><span>Containers</span><strong>{{ stats.containers }}</strong><small>Discovered runtime units</small></article>
      <article class="running"><span>Running</span><strong>{{ stats.running }}</strong><small>Actively executing</small></article>
      <article class="stopped"><span>Stopped</span><strong>{{ stats.stopped }}</strong><small>Exited or created</small></article>
      <article><span>Images</span><strong>{{ stats.images }}</strong><small>Local image inventory</small></article>
      <article><span>Volumes</span><strong>{{ stats.volumes }}</strong><small>Persistent storage</small></article>
      <article><span>Networks</span><strong>{{ stats.networks }}</strong><small>Docker networks</small></article>
    </div>

    <article class="docker-table-panel">
      <header><div class="table-search"><span>⌕</span><input v-model="query" placeholder="Filter container, image, ID or IP" /></div><div class="docker-table-tools"><div class="state-tabs"><button v-for="state in ['all','running','exited']" :key="state" :class="{ active: stateFilter === state }" @click="stateFilter = state">{{ state }}</button></div><button class="refresh-button" @click="load()">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></div></header>
      <div v-if="loading && !overview" class="table-empty"><span class="loading-ring" /><strong>Inspecting Docker Engine</strong></div>
      <div v-else-if="!visible.length" class="table-empty"><span class="server-empty-glyph">⬡</span><strong>No containers found</strong><small>{{ selectedServer ? 'The Agent has not reported containers for this server.' : 'Connect an online Agent with access to the Docker socket.' }}</small></div>
      <div v-else class="server-table-wrap"><table class="server-table docker-table"><thead><tr><th>Container</th><th>State</th><th>Image</th><th>CPU</th><th>Memory</th><th>Network</th><th>IP / Ports</th></tr></thead><tbody><tr v-for="container in visible" :key="container.id">
        <td><RouterLink class="node-cell node-link" :to="`/docker/containers/${container.id}`"><span>⬡</span><div><strong>{{ container.name }}</strong><small class="mono">{{ container.shortId }}</small></div></RouterLink></td>
        <td><span class="status-badge" :class="stateClass(container)"><i />{{ container.state.toUpperCase() }}</span><small class="cell-secondary">{{ container.health || container.status }}</small></td>
        <td><strong class="cell-primary docker-image">{{ container.image }}</strong><small class="cell-secondary">{{ container.networkMode || 'default network' }}</small></td>
        <td><strong class="cell-primary metric-value">{{ container.cpuUsage.toFixed(1) }}%</strong></td>
        <td><strong class="cell-primary">{{ memory(container) }}</strong></td>
        <td><strong class="cell-primary">↓ {{ bytes(container.networkRx) }}</strong><small class="cell-secondary">↑ {{ bytes(container.networkTx) }}</small></td>
        <td><strong class="cell-primary mono">{{ container.ipAddress || '—' }}</strong><small class="cell-secondary">{{ container.ports[0] || 'No published ports' }}</small></td>
      </tr></tbody></table></div>
    </article>
  </section>
</template>
