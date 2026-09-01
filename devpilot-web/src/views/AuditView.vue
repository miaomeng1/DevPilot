<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { auditApi, type AuditLog } from '@/api/audit'
import { apiErrorMessage } from '@/api/client'

const logs = ref<AuditLog[]>([])
const actions = ref<string[]>([])
const selected = ref<AuditLog | null>(null)
const loading = ref(false)
const query = ref('')
const action = ref('')
const result = ref('')
const page = ref(1)
const size = 25
const total = ref(0)
const errorMessage = ref('')
const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))

async function load(resetPage = false) {
  if (resetPage) page.value = 1
  loading.value = true; errorMessage.value = ''
  try {
    const data = await auditApi.list({ action: action.value || undefined, result: result.value || undefined,
      query: query.value.trim() || undefined, page: page.value, size })
    logs.value = data.items; total.value = data.total
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'Audit log could not be loaded') }
  finally { loading.value = false }
}

function changePage(value: number) { page.value = value; void load() }
function formatTime(value: string) { return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(`${value}Z`)) }
function pretty(value: string | null) {
  if (!value) return 'No request parameters recorded.'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
function actionLabel(value: string) { return value.toLowerCase().split('_').map((part) => part[0]?.toUpperCase() + part.slice(1)).join(' ') }

onMounted(async () => {
  try { actions.value = await auditApi.actions() } catch { actions.value = [] }
  await load()
})
</script>

<template>
  <section class="audit-view">
    <header class="page-heading"><div><p class="eyebrow">IMMUTABLE OPERATIONS TRAIL</p><h1>Audit log</h1><span>Successful and failed control-plane mutations with structurally redacted inputs.</span></div><button class="refresh-button audit-refresh" @click="load()">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <article class="audit-panel"><header><form class="audit-filters" @submit.prevent="load(true)"><div class="table-search"><span>⌕</span><input v-model="query" placeholder="User, resource, or action" /></div><select v-model="action" @change="load(true)"><option value="">All actions</option><option v-for="item in actions" :key="item" :value="item">{{ actionLabel(item) }}</option></select><select v-model="result" @change="load(true)"><option value="">All results</option><option value="SUCCESS">Success</option><option value="FAILED">Failed</option></select><button>Apply</button></form><small>{{ total }} events</small></header>
      <div v-if="loading && !logs.length" class="table-empty"><span class="loading-ring" /><strong>Loading audit events</strong></div><div v-else-if="!logs.length" class="table-empty"><span class="server-empty-glyph">A</span><strong>No audit events match this view</strong></div>
      <div v-else class="server-table-wrap"><table class="server-table audit-table"><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Resource</th><th>Server / IP</th><th>Result</th><th></th></tr></thead><tbody><tr v-for="entry in logs" :key="entry.id">
        <td><strong class="cell-primary">{{ formatTime(entry.occurredAt) }}</strong><small class="cell-secondary">UTC persisted</small></td><td><div class="audit-actor"><span>{{ (entry.username || 'SY').slice(0,2).toUpperCase() }}</span><div><strong>{{ entry.username || 'System / unauthenticated' }}</strong><small>{{ entry.userId ? `User ${entry.userId}` : 'No authenticated principal' }}</small></div></div></td>
        <td><strong class="audit-action">{{ actionLabel(entry.action) }}</strong><small class="cell-secondary">{{ entry.action }}</small></td><td><strong class="cell-primary">{{ entry.resourceName || entry.resourceId || '—' }}</strong><small class="cell-secondary">{{ entry.resourceType }}<template v-if="entry.resourceId"> · {{ entry.resourceId }}</template></small></td><td><strong class="cell-primary">{{ entry.serverName || '—' }}</strong><small class="cell-secondary">{{ entry.ipAddress || 'IP unavailable' }}</small></td><td><span class="audit-result" :class="entry.result.toLowerCase()"><i />{{ entry.result }}</span><small v-if="entry.errorMessage" class="audit-error">{{ entry.errorMessage }}</small></td><td><button class="audit-detail-button" @click="selected = entry">Inspect</button></td>
      </tr></tbody></table></div>
      <footer><span>Page {{ page }} of {{ pages }}</span><div><button :disabled="page <= 1" @click="changePage(page - 1)">← Previous</button><button :disabled="page >= pages" @click="changePage(page + 1)">Next →</button></div></footer>
    </article>

    <div v-if="selected" class="modal-backdrop" @click.self="selected = null"><section class="server-dialog audit-dialog" role="dialog" aria-modal="true"><header><div><span>AUDIT EVENT · {{ selected.id }}</span><h2>{{ actionLabel(selected.action) }}</h2></div><button aria-label="Close" @click="selected = null">×</button></header><div class="dialog-body audit-detail"><dl><div><dt>Actor</dt><dd>{{ selected.username || 'Unauthenticated' }}</dd></div><div><dt>Result</dt><dd><span class="audit-result" :class="selected.result.toLowerCase()">{{ selected.result }}</span></dd></div><div><dt>Resource</dt><dd>{{ selected.resourceType }} · {{ selected.resourceName || selected.resourceId || '—' }}</dd></div><div><dt>Server</dt><dd>{{ selected.serverName || '—' }}</dd></div><div><dt>Source IP</dt><dd>{{ selected.ipAddress || '—' }}</dd></div><div><dt>Occurred</dt><dd>{{ formatTime(selected.occurredAt) }}</dd></div></dl><div v-if="selected.errorMessage" class="audit-detail-error"><strong>Failure detail</strong><p>{{ selected.errorMessage }}</p></div><div><strong>Sanitized request</strong><pre>{{ pretty(selected.requestParams) }}</pre></div></div><footer><span>Passwords, tokens, webhook credentials, and configuration bodies are redacted before persistence.</span><button class="dialog-primary" @click="selected = null">Done</button></footer></section></div>
  </section>
</template>
