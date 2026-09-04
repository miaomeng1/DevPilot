<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { dockerApi, type DockerAction, type DockerCommand, type DockerContainer } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import ContainerLogsPanel from '@/components/ContainerLogsPanel.vue'

const route = useRoute()
const auth = useAuthStore()
const container = ref<DockerContainer>()
const loading = ref(false)
const errorMessage = ref('')
const command = ref<DockerCommand>()
const removeOpen = ref(false)
const removeName = ref('')
const pendingAction = ref<'stop' | 'restart' | null>(null)
let pollTimer: number | undefined
let commandTimer: number | undefined

const containerId = computed(() => String(route.params.id))
const running = computed(() => container.value?.state === 'running')
const canOperate = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const canRemove = computed(() => auth.hasAnyRole(['ADMIN']))
const commandPending = computed(() => command.value && ['REQUESTED', 'CLAIMED'].includes(command.value.status))

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    container.value = await dockerApi.get(containerId.value)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '容器详情加载失败')
  } finally {
    loading.value = false
  }
}

async function operate(action: DockerAction) {
  errorMessage.value = ''
  try {
    command.value = await dockerApi.operate(containerId.value, action)
    watchCommand()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Docker 操作提交失败')
  }
}

async function confirmOperation() {
  if (!pendingAction.value) return
  const action = pendingAction.value
  pendingAction.value = null
  await operate(action)
}

async function removeContainer() {
  if (!container.value || removeName.value !== container.value.name) return
  errorMessage.value = ''
  try {
    command.value = await dockerApi.remove(containerId.value, removeName.value)
    removeOpen.value = false
    watchCommand()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '删除操作提交失败')
  }
}

function watchCommand() {
  window.clearInterval(commandTimer)
  commandTimer = window.setInterval(async () => {
    if (!command.value) return
    try {
      command.value = await dockerApi.command(command.value.id)
      if (!['REQUESTED', 'CLAIMED'].includes(command.value.status)) {
        window.clearInterval(commandTimer)
        if (command.value.status === 'FAILED') errorMessage.value = command.value.errorMessage || 'Docker 操作失败'
        window.setTimeout(() => void load(true), 500)
      }
    } catch (error) {
      errorMessage.value = apiErrorMessage(error, '无法获取操作结果')
      window.clearInterval(commandTimer)
    }
  }, 800)
}

function bytes(value: string | undefined) {
  let amount = Number(value || 0)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1 }
  return `${amount.toFixed(index ? 1 : 0)} ${units[index]}`
}

function time(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`).toLocaleString()
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  window.clearInterval(commandTimer)
})
</script>

<template>
  <section class="container-detail-view">
    <RouterLink class="back-link" to="/docker">← Docker overview</RouterLink>
    <header class="page-heading container-heading"><div><p class="eyebrow">CONTAINER · {{ container?.shortId || 'LOADING' }}</p><h1>{{ container?.name || 'Loading container…' }}</h1><span>{{ container?.image || 'Image pending' }}</span></div><div v-if="container" class="container-actions"><span class="status-badge detail-status" :class="running ? 'online' : 'offline'"><i />{{ container.state.toUpperCase() }}</span><button v-if="canOperate && !running" :disabled="commandPending" @click="operate('start')">▶ Start</button><button v-if="canOperate && running" :disabled="commandPending" @click="pendingAction = 'stop'">■ Stop</button><button v-if="canOperate && running" :disabled="commandPending" @click="pendingAction = 'restart'">↻ Restart</button><button v-if="canRemove && !running" class="danger-action" :disabled="commandPending" @click="removeOpen = true; removeName = ''">Remove</button></div></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <div v-if="command" class="command-banner" :class="command.status.toLowerCase()"><span :class="{ spinning: commandPending }">↻</span><div><strong>{{ command.action }} · {{ command.status }}</strong><small>{{ commandPending ? 'Waiting for the Agent to execute this typed command…' : (command.errorMessage || 'Operation completed') }}</small></div></div>

    <div class="container-kpis" :class="{ 'is-loading': loading && !container }">
      <article><span>CPU usage</span><strong>{{ container ? `${container.cpuUsage.toFixed(1)}%` : '—' }}</strong><small>Current Docker sample</small></article>
      <article><span>Memory</span><strong>{{ bytes(container?.memoryUsage) }}</strong><small>Limit {{ bytes(container?.memoryLimit) }}</small></article>
      <article><span>Network RX</span><strong>{{ bytes(container?.networkRx) }}</strong><small>TX {{ bytes(container?.networkTx) }}</small></article>
      <article><span>Restart count</span><strong>{{ container?.restartCount ?? '—' }}</strong><small>Started {{ time(container?.startedAt) }}</small></article>
    </div>

    <div class="container-detail-grid">
      <article class="detail-panel container-facts"><header><div><strong>Runtime identity</strong><small>Docker inspect snapshot</small></div></header><dl>
        <div><dt>Container ID</dt><dd class="mono">{{ container?.containerId || '—' }}</dd></div><div><dt>Status</dt><dd>{{ container?.status || '—' }}</dd></div>
        <div><dt>Health</dt><dd>{{ container?.health || 'No health check' }}</dd></div><div><dt>IP address</dt><dd>{{ container?.ipAddress || '—' }}</dd></div>
        <div><dt>Network mode</dt><dd>{{ container?.networkMode || '—' }}</dd></div><div><dt>Created</dt><dd>{{ time(container?.createdAt) }}</dd></div>
        <div><dt>Compose Stack</dt><dd>{{ container?.composeProject || '独立容器 Standalone' }}</dd></div><div><dt>Compose Service</dt><dd>{{ container?.composeService || '—' }}</dd></div>
      </dl></article>
      <article class="detail-panel list-panel"><header><div><strong>Published ports</strong><small>Host-to-container mappings</small></div><span>{{ container?.ports.length || 0 }}</span></header><ul><li v-for="port in container?.ports" :key="port" class="mono">{{ port }}</li><li v-if="!container?.ports.length" class="empty-list">No published ports</li></ul></article>
      <article class="detail-panel list-panel volumes-panel"><header><div><strong>Volumes</strong><small>Mount sources, destinations, and access</small></div><span>{{ container?.volumes.length || 0 }}</span></header><ul><li v-for="volume in container?.volumes" :key="volume" class="mono">{{ volume }}</li><li v-if="!container?.volumes.length" class="empty-list">No mounted volumes</li></ul></article>
      <article class="detail-panel list-panel environment-panel"><header><div><strong>Environment</strong><small>Sensitive values are masked at the Agent and server</small></div><span>{{ container?.environment.length || 0 }}</span></header><ul><li v-for="entry in container?.environment" :key="entry" class="mono" :class="{ secret: entry.endsWith('=******') }">{{ entry }}</li><li v-if="!container?.environment.length" class="empty-list">No environment variables reported</li></ul></article>
    </div>

    <ContainerLogsPanel v-if="container" :container-id="container.id" :container-name="container.name" />

    <div v-if="pendingAction" class="modal-backdrop" @click.self="pendingAction = null"><section class="server-dialog remove-dialog" role="dialog" aria-modal="true" aria-labelledby="docker-operation-title"><header><div><span>RUNTIME IMPACT</span><h2 id="docker-operation-title">{{ pendingAction === 'stop' ? 'Stop' : 'Restart' }} {{ container?.name }}?</h2></div><button aria-label="Close" @click="pendingAction = null">×</button></header><div class="dialog-body"><p>{{ pendingAction === 'stop' ? 'The service will become unavailable until it is started again.' : 'The service may be briefly unavailable while Docker restarts the container.' }} DevPilot will send only this typed Docker operation and record its final result.</p></div><footer><button @click="pendingAction = null">Cancel</button><button class="dialog-primary" :disabled="Boolean(commandPending)" @click="confirmOperation">Confirm {{ pendingAction }}</button></footer></section></div>

    <div v-if="removeOpen" class="modal-backdrop" @click.self="removeOpen = false"><section class="server-dialog remove-dialog"><header><div><span>DESTRUCTIVE ACTION</span><h2>Remove {{ container?.name }}</h2></div><button @click="removeOpen = false">×</button></header><div class="dialog-body"><p>This removes the stopped container. Volumes are preserved. Type the exact container name to confirm.</p><label><span>Container name</span><input v-model="removeName" :placeholder="container?.name" autofocus /></label></div><footer><button @click="removeOpen = false">Cancel</button><button class="danger-confirm" :disabled="removeName !== container?.name" @click="removeContainer">Remove container</button></footer></section></div>
  </section>
</template>
