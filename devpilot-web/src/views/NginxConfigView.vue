<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { nginxApi, type NginxCommand, type NginxConfig, type NginxHistory } from '@/api/nginx'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()
const config = ref<NginxConfig>()
const history = ref<NginxHistory[]>([])
const editor = ref('')
const loading = ref(true)
const errorMessage = ref('')
const command = ref<NginxCommand>()
const selectedHistory = ref<NginxHistory>()
const diffOpen = ref(false)
const saveOpen = ref(false)
const rollbackOpen = ref(false)
let destroyed = false

const id = computed(() => String(route.params.id))
const canManage = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const changed = computed(() => config.value !== undefined && editor.value !== config.value.content)
const busy = computed(() => command.value && ['REQUESTED', 'CLAIMED'].includes(command.value.status))
const lineCount = computed(() => editor.value.split('\n').length)
const byteCount = computed(() => new TextEncoder().encode(editor.value).length)

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [detail, versions] = await Promise.all([nginxApi.get(id.value), nginxApi.history(id.value)])
    config.value = detail
    editor.value = detail.content
    history.value = versions
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Nginx configuration could not be loaded')
  } finally {
    loading.value = false
  }
}

async function refreshHistory() {
  history.value = await nginxApi.history(id.value)
}

async function save() {
  if (!changed.value || busy.value) return
  errorMessage.value = ''
  saveOpen.value = false
  try {
    command.value = await nginxApi.update(id.value, editor.value)
    await watchCommand()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Nginx update could not be queued')
  }
}

async function rollback() {
  if (!selectedHistory.value || busy.value) return
  errorMessage.value = ''
  rollbackOpen.value = false
  try {
    command.value = await nginxApi.rollback(id.value, selectedHistory.value.id)
    await watchCommand()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Nginx rollback could not be queued')
  }
}

async function watchCommand() {
  while (command.value && ['REQUESTED', 'CLAIMED'].includes(command.value.status) && !destroyed) {
    await new Promise((resolve) => window.setTimeout(resolve, 800))
    if (destroyed || !command.value) return
    command.value = await nginxApi.command(command.value.id)
  }
  await refreshHistory()
  if (command.value?.status === 'SUCCEEDED') {
    const detail = await nginxApi.get(id.value)
    config.value = detail
    editor.value = detail.content
  }
}

function inspectVersion(version: NginxHistory) {
  selectedHistory.value = version
  diffOpen.value = true
}

function requestRollback(version: NginxHistory) {
  selectedHistory.value = version
  rollbackOpen.value = true
}

function formatTime(value: string | null) {
  if (!value) return 'Pending'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(`${value}Z`))
}

function shortHash(value: string) {
  return `${value.slice(0, 12)}…${value.slice(-8)}`
}

onMounted(() => void load())
onBeforeUnmount(() => { destroyed = true })
</script>

<template>
  <section class="nginx-config-view">
    <div v-if="loading && !config" class="table-empty"><span class="loading-ring" /><strong>Loading Nginx configuration</strong></div>
    <template v-else-if="config">
      <RouterLink class="back-link" to="/nginx">← Nginx configurations</RouterLink>
      <header class="page-heading nginx-config-heading"><div><p class="eyebrow">{{ config.serverName }} · NGINX</p><h1>{{ config.filename }}</h1><span class="mono">SHA-256 {{ shortHash(config.contentHash) }}</span></div><div class="nginx-editor-actions"><span v-if="changed" class="unsaved-pill">Unsaved changes</span><button :disabled="!changed || busy" @click="editor = config.content">Reset</button><button v-if="canManage" class="save-config" :disabled="!changed || busy" @click="saveOpen = true">{{ busy ? 'Agent working…' : 'Validate & reload' }}</button></div></header>
      <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
      <div v-if="command" class="nginx-command-banner" :class="command.status.toLowerCase()"><span :class="{ spinning: busy }">{{ busy ? '↻' : command.status === 'SUCCEEDED' ? '✓' : '!' }}</span><div><strong>{{ command.action }} · {{ command.status }}</strong><small v-if="busy">Agent is staging the file, running nginx -t, backing up, and reloading.</small><small v-else-if="command.status === 'SUCCEEDED'">{{ command.validationOutput || 'Configuration test and reload completed.' }}</small><small v-else>{{ command.errorMessage }}<template v-if="command.validationOutput"> · {{ command.validationOutput }}</template></small></div></div>

      <div class="nginx-workspace">
        <article class="nginx-editor-panel"><header><div><strong>Configuration editor</strong><small>Only this discovered regular .conf file can be changed</small></div><span>{{ lineCount }} lines · {{ byteCount }} bytes</span></header><div class="nginx-editor-shell"><pre class="line-numbers" aria-hidden="true"><span v-for="line in lineCount" :key="line">{{ line }}</span></pre><textarea v-model="editor" aria-label="Nginx configuration editor" spellcheck="false" :readonly="!canManage || Boolean(busy)" /></div><footer><span><i />Atomic replacement</span><span><i />nginx -t required</span><span><i />Automatic backup</span><small>{{ config.serverName }} · {{ config.filename }}</small></footer></article>

        <article class="detail-panel nginx-history-panel"><header><div><strong>Version history</strong><small>Before / after content retained for every requested change</small></div><span>{{ history.length }} versions</span></header><div v-if="!history.length" class="release-empty"><span>↶</span><strong>No configuration changes yet</strong><small>The first validated save will create a restorable version.</small></div><ol v-else class="nginx-history-list"><li v-for="version in history" :key="version.id"><span class="history-state" :class="version.status.toLowerCase()">{{ version.status === 'SUCCEEDED' ? '✓' : version.status === 'FAILED' ? '!' : '·' }}</span><div><header><strong>{{ version.action }}</strong><span>{{ version.operatorName }} · {{ formatTime(version.createdAt) }}</span></header><p v-if="version.errorMessage">{{ version.errorMessage }}</p><div class="history-actions"><button @click="inspectVersion(version)">View changes</button><button v-if="canManage && version.status === 'SUCCEEDED'" :disabled="Boolean(busy)" @click="requestRollback(version)">Restore previous</button></div></div></li></ol></article>
      </div>
    </template>

    <div v-if="diffOpen && selectedHistory" class="modal-backdrop" @click.self="diffOpen = false"><section class="server-dialog nginx-diff-dialog" role="dialog" aria-modal="true"><header><div><span>{{ selectedHistory.action }} · {{ selectedHistory.status }}</span><h2>{{ selectedHistory.filename }} changes</h2></div><button aria-label="Close" @click="diffOpen = false">×</button></header><div class="nginx-diff-grid"><article><header>BEFORE</header><pre>{{ selectedHistory.oldContent }}</pre></article><article><header>AFTER</header><pre>{{ selectedHistory.newContent }}</pre></article></div><footer><span class="waiting-agent">{{ selectedHistory.operatorName }} · {{ formatTime(selectedHistory.createdAt) }}</span><button @click="diffOpen = false">Close</button></footer></section></div>

    <div v-if="saveOpen && config" class="modal-backdrop" @click.self="saveOpen = false"><section class="server-dialog remove-dialog" role="dialog" aria-modal="true" aria-labelledby="save-nginx-title"><header><div><span>VALIDATED CONFIGURATION CHANGE</span><h2 id="save-nginx-title">Validate and reload {{ config.filename }}?</h2></div><button aria-label="Close" @click="saveOpen = false">×</button></header><div class="dialog-body"><p>This can affect live traffic. The Agent will test staged content first; the active file remains unchanged unless validation succeeds. A backup and audit record will be created before reload.</p><div class="rollback-summary"><strong>{{ config.serverName }} · {{ config.filename }}</strong><small>{{ lineCount }} lines · {{ byteCount }} bytes · content is redacted from audit logs</small></div></div><footer><button @click="saveOpen = false">Cancel</button><button class="dialog-primary" :disabled="Boolean(busy)" @click="save">Validate & reload</button></footer></section></div>

    <div v-if="rollbackOpen && selectedHistory" class="modal-backdrop" @click.self="rollbackOpen = false"><section class="server-dialog remove-dialog" role="dialog" aria-modal="true"><header><div><span>VALIDATED ROLLBACK</span><h2>Restore the previous content?</h2></div><button aria-label="Close" @click="rollbackOpen = false">×</button></header><div class="dialog-body"><p>DevPilot will send the “before” content from this version through the same stage, nginx -t, backup, and reload workflow.</p><div class="rollback-summary"><strong>{{ selectedHistory.filename }}</strong><small>{{ selectedHistory.operatorName }} · {{ formatTime(selectedHistory.createdAt) }}</small></div></div><footer><button @click="rollbackOpen = false">Cancel</button><button class="dialog-primary" :disabled="Boolean(busy)" @click="rollback">Validate & restore</button></footer></section></div>
  </section>
</template>
