<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { nginxApi, type NginxConfigSummary, type NginxHost } from '@/api/nginx'
import { apiErrorMessage } from '@/api/client'

const hosts = ref<NginxHost[]>([])
const configs = ref<NginxConfigSummary[]>([])
const selectedServer = ref('')
const query = ref('')
const loading = ref(false)
const errorMessage = ref('')
let pollTimer: number | undefined

const visible = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return configs.value.filter((config) => !needle || [config.filename, config.serverName, config.contentHash]
    .some((value) => value.toLowerCase().includes(needle)))
})
const selectedHost = computed(() => hosts.value.find((host) => host.serverId === selectedServer.value))
const summary = computed(() => ({
  hosts: hosts.value.length,
  available: hosts.value.filter((host) => host.available).length,
  files: selectedServer.value ? configs.value.length : hosts.value.reduce((total, host) => total + host.configCount, 0),
  issues: hosts.value.filter((host) => host.enabled && !host.available).length,
}))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const [hostList, configList] = await Promise.all([nginxApi.hosts(), nginxApi.configs(selectedServer.value || undefined)])
    hosts.value = hostList
    configs.value = configList
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Nginx inventory could not be loaded')
  } finally {
    loading.value = false
  }
}

function bytes(value: number) {
  return value < 1024 ? `${value} B` : `${(value / 1024).toFixed(1)} KiB`
}

function formatTime(value: string | null) {
  if (!value) return 'Never'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(true), 15_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="nginx-view">
    <header class="page-heading nginx-heading"><div><p class="eyebrow">EDGE CONFIGURATION</p><h1>Nginx</h1><span>Validated, versioned configuration changes through connected Agents.</span></div><div class="docker-server-filter"><label>Server</label><select v-model="selectedServer" @change="load()"><option value="">All servers</option><option v-for="host in hosts" :key="host.serverId" :value="host.serverId">{{ host.serverName }}</option></select></div></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <div v-if="selectedHost?.errorMessage" class="nginx-host-warning"><span>!</span><div><strong>{{ selectedHost.available ? 'Agent warning' : 'Nginx unavailable' }}</strong><small>{{ selectedHost.errorMessage }}</small></div></div>

    <div class="nginx-summary">
      <article><span>Agent hosts</span><strong>{{ summary.hosts }}</strong><small>Nginx status reported</small></article>
      <article class="available"><span>Available</span><strong>{{ summary.available }}</strong><small>Ready for validated changes</small></article>
      <article><span>Configuration files</span><strong>{{ summary.files }}</strong><small>Discovered .conf files</small></article>
      <article class="issues"><span>Issues</span><strong>{{ summary.issues }}</strong><small>Enabled but unavailable</small></article>
    </div>

    <article class="nginx-table-panel">
      <header><div class="table-search"><span>⌕</span><input v-model="query" placeholder="Filter filename, server, or SHA-256" /></div><div class="nginx-table-meta"><span v-if="selectedHost" class="nginx-version">{{ selectedHost.nginxVersion || 'Version unavailable' }} · {{ selectedHost.configPath }}</span><button class="refresh-button" @click="load()">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></div></header>
      <div v-if="loading && !configs.length" class="table-empty"><span class="loading-ring" /><strong>Reading Agent configuration inventory</strong></div>
      <div v-else-if="!visible.length" class="table-empty"><span class="server-empty-glyph">N</span><strong>{{ query ? 'No matching configuration' : 'No Nginx configuration discovered' }}</strong><small>Enable Nginx management in the Agent and point configPath at a directory containing regular .conf files.</small></div>
      <div v-else class="server-table-wrap"><table class="server-table nginx-table"><thead><tr><th>Configuration</th><th>Server</th><th>Size</th><th>Content SHA-256</th><th>Last observed</th><th>Safety workflow</th></tr></thead><tbody><tr v-for="config in visible" :key="config.id">
        <td><RouterLink class="node-cell node-link" :to="`/nginx/configs/${config.id}`"><span>N</span><div><strong>{{ config.filename }}</strong><small>View · Edit · History</small></div></RouterLink></td>
        <td><strong class="cell-primary">{{ config.serverName }}</strong><small class="cell-secondary">Agent managed</small></td>
        <td><strong class="cell-primary">{{ bytes(config.contentBytes) }}</strong><small class="cell-secondary">UTF-8</small></td>
        <td><code class="hash-value">{{ config.contentHash }}</code></td>
        <td><strong class="cell-primary">{{ formatTime(config.lastSeenAt) }}</strong><small class="cell-secondary">Latest snapshot</small></td>
        <td><span class="safety-flow"><i />stage → test → backup → reload</span></td>
      </tr></tbody></table></div>
    </article>
  </section>
</template>
