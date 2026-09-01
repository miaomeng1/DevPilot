<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { serverApi, type CreateServerResult, type ServerNode } from '@/api/servers'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const store = useServerStore()
const query = ref('')
const dialogOpen = ref(false)
const serverName = ref('')
const creating = ref(false)
const created = ref<CreateServerResult | null>(null)
const errorMessage = ref('')
const copied = ref<'token' | 'command' | null>(null)
let pollTimer: number | undefined

const canCreate = computed(() => auth.hasAnyRole(['ADMIN']))
const filteredServers = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return store.servers
  return store.servers.filter((server) =>
    [server.name, server.hostname, server.ip, server.os].some((value) => value?.toLowerCase().includes(needle)),
  )
})

const statusCounts = computed(() => ({
  online: store.servers.filter((server) => server.status === 'ONLINE').length,
  offline: store.servers.filter((server) => server.status === 'OFFLINE').length,
  pending: store.servers.filter((server) => server.status === 'UNKNOWN').length,
}))

function openDialog() {
  serverName.value = ''
  created.value = null
  errorMessage.value = ''
  copied.value = null
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
  if (created.value) void store.load(true)
}

async function createServer() {
  if (serverName.value.trim().length < 2) return
  creating.value = true
  errorMessage.value = ''
  try {
    created.value = await serverApi.create(serverName.value.trim())
    store.prepend(created.value.server)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法创建服务器')
  } finally {
    creating.value = false
  }
}

async function copy(kind: 'token' | 'command', value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = kind
  window.setTimeout(() => {
    if (copied.value === kind) copied.value = null
  }, 1800)
}

function bytes(value: string | null) {
  if (!value) return '—'
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  let normalized = amount
  while (normalized >= 1024 && index < units.length - 1) {
    normalized /= 1024
    index += 1
  }
  return `${normalized.toFixed(index > 2 ? 1 : 0)} ${units[index]}`
}

function relativeTime(value: string | null) {
  if (!value) return 'Never'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(`${value}Z`).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  return `${Math.floor(seconds / 3600)}h ago`
}

function identity(server: ServerNode) {
  return server.hostname || 'Waiting for Agent'
}

onMounted(() => {
  void store.load()
  pollTimer = window.setInterval(() => void store.load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="servers-view">
    <header class="page-heading servers-heading">
      <div><p class="eyebrow">INFRASTRUCTURE</p><h1>Servers</h1><span>Every Linux host connected to this control plane.</span></div>
      <button v-if="canCreate" class="primary-compact" type="button" @click="openDialog"><b>＋</b>Add server</button>
    </header>

    <div class="server-summary">
      <div><span>Total nodes</span><strong>{{ store.servers.length }}</strong><small>Managed infrastructure</small></div>
      <div class="online"><span>Online</span><strong>{{ statusCounts.online }}</strong><small>Heartbeat within 30s</small></div>
      <div class="offline"><span>Offline</span><strong>{{ statusCounts.offline }}</strong><small>Needs attention</small></div>
      <div class="pending"><span>Pending</span><strong>{{ statusCounts.pending }}</strong><small>Agent not registered</small></div>
    </div>

    <article class="server-table-panel">
      <header>
        <div class="table-search"><span>⌕</span><input v-model="query" placeholder="Filter by name, host, IP or OS" /></div>
        <div class="table-meta"><i :class="{ spinning: store.loading }">↻</i><span>{{ store.loading ? 'Refreshing' : `${filteredServers.length} nodes` }}</span><button type="button" @click="store.load(true)">Refresh</button></div>
      </header>

      <div v-if="store.loading && !store.loaded" class="table-empty"><span class="loading-ring" /><strong>Loading infrastructure</strong></div>
      <div v-else-if="filteredServers.length === 0" class="table-empty">
        <span class="server-empty-glyph">⌁</span>
        <strong>{{ query ? 'No matching servers' : 'No servers connected yet' }}</strong>
        <small>{{ query ? 'Try a different filter.' : 'Generate an Agent token to connect your first Linux host.' }}</small>
        <button v-if="canCreate && !query" type="button" @click="openDialog">Add first server</button>
      </div>
      <div v-else class="server-table-wrap">
        <table class="server-table">
          <thead><tr><th>Node</th><th>Status</th><th>Platform</th><th>Capacity</th><th>Agent</th><th>Last heartbeat</th></tr></thead>
          <tbody>
            <tr v-for="server in filteredServers" :key="server.id">
              <td><RouterLink class="node-cell node-link" :to="`/servers/${server.id}`"><span>{{ server.name.slice(0, 2).toUpperCase() }}</span><div><strong>{{ server.name }}</strong><small>{{ identity(server) }} · {{ server.ip || 'IP pending' }}</small></div></RouterLink></td>
              <td><span class="status-badge" :class="server.status.toLowerCase()"><i />{{ server.status }}</span></td>
              <td><strong class="cell-primary">{{ server.os || 'Not reported' }}</strong><small class="cell-secondary">{{ server.architecture || '—' }} · {{ server.kernel || 'kernel pending' }}</small></td>
              <td><strong class="cell-primary">{{ server.cpuCores ? `${server.cpuCores} cores` : '—' }}</strong><small class="cell-secondary">{{ bytes(server.memoryTotal) }} RAM</small></td>
              <td><strong class="cell-primary mono">{{ server.agentVersion || 'Awaiting' }}</strong><small class="cell-secondary">DevPilot Agent</small></td>
              <td><strong class="cell-primary">{{ relativeTime(server.lastHeartbeat) }}</strong><small class="cell-secondary">{{ server.registeredAt ? 'Registered' : 'Never connected' }}</small></td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="closeDialog">
      <section class="server-dialog" role="dialog" aria-modal="true" aria-labelledby="server-dialog-title">
        <header><div><span>{{ created ? 'AGENT CREDENTIAL · READY' : 'CONNECT INFRASTRUCTURE · 01' }}</span><h2 id="server-dialog-title">{{ created ? 'Install DevPilot Agent' : 'Add a server' }}</h2></div><button type="button" aria-label="Close" @click="closeDialog">×</button></header>

        <template v-if="!created">
          <div class="dialog-body">
            <p>Create a server placeholder, then run the generated install command on the target Linux host.</p>
            <label><span>Server name</span><input v-model.trim="serverName" maxlength="100" placeholder="prod-server-01" autofocus @keyup.enter="createServer" /><small>Use a name that describes environment or workload.</small></label>
            <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
          </div>
          <footer><button type="button" @click="closeDialog">Cancel</button><button class="dialog-primary" type="button" :disabled="creating || serverName.length < 2" @click="createServer">{{ creating ? 'Generating…' : 'Generate Agent token' }} <b>→</b></button></footer>
        </template>

        <template v-else>
          <div class="dialog-body credential-body">
            <div class="credential-warning"><span>!</span><div><strong>Copy this credential now</strong><small>The raw Agent token is shown once and stored only as a SHA-256 hash.</small></div></div>
            <label><span>Agent token</span><div class="copy-field"><code>{{ created.agentToken }}</code><button type="button" @click="copy('token', created.agentToken)">{{ copied === 'token' ? 'Copied' : 'Copy' }}</button></div></label>
            <label><span>Install command</span><div class="copy-field command"><code>{{ created.installCommand }}</code><button type="button" @click="copy('command', created.installCommand)">{{ copied === 'command' ? 'Copied' : 'Copy' }}</button></div></label>
            <ol class="install-steps"><li><b>1</b><span>SSH into <strong>{{ created.server.name }}</strong></span></li><li><b>2</b><span>Run the command above as root</span></li><li><b>3</b><span>The node turns ONLINE after registration</span></li></ol>
          </div>
          <footer><span class="waiting-agent"><i />Waiting for Agent heartbeat</span><button class="dialog-primary" type="button" @click="closeDialog">Done</button></footer>
        </template>
      </section>
    </div>
  </section>
</template>
