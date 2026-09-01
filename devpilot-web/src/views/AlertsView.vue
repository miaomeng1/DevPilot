<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { alertsApi, type AlertEvent, type AlertSummary } from '@/api/alerts'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const servers = useServerStore()
const events = ref<AlertEvent[]>([])
const summary = ref<AlertSummary>({ active: 0, critical: 0 })
const statusFilter = ref('')
const severityFilter = ref('')
const serverFilter = ref('')
const loading = ref(false)
const errorMessage = ref('')
let timer: number | undefined

const counts = computed(() => ({
  firing: events.value.filter((event) => event.status === 'FIRING').length,
  acknowledged: events.value.filter((event) => event.status === 'ACKNOWLEDGED').length,
  resolved: events.value.filter((event) => event.status === 'RESOLVED').length,
}))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const [eventList, totals] = await Promise.all([alertsApi.events({
      status: statusFilter.value || undefined, severity: severityFilter.value || undefined,
      serverId: serverFilter.value || undefined,
    }), alertsApi.summary()])
    events.value = eventList
    summary.value = totals
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'Alert events could not be loaded') }
  finally { loading.value = false }
}

async function acknowledge(event: AlertEvent) {
  try { await alertsApi.acknowledge(event.id); await load(true) }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Alert could not be acknowledged') }
}

function formatTime(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function age(value: string) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(`${value}Z`).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  return `${Math.floor(seconds / 86400)}d ago`
}

onMounted(async () => { await servers.load(); await load(); timer = window.setInterval(() => void load(true), 10_000) })
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <section class="alerts-view">
    <header class="page-heading alert-heading"><div><p class="eyebrow">INCIDENT TIMELINE</p><h1>Alert events</h1><span>Active conditions, human acknowledgement, recovery, and delivery status.</span></div><RouterLink v-if="auth.hasAnyRole(['ADMIN'])" class="primary-compact" to="/alerts/rules"><b>⚙</b>Manage rules</RouterLink></header>
    <nav class="alert-tabs"><RouterLink class="active" to="/alerts">Events</RouterLink><RouterLink to="/alerts/rules">Rules</RouterLink></nav>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="alert-summary"><article class="issues"><span>Active</span><strong>{{ summary.active }}</strong><small>Firing or acknowledged</small></article><article class="critical"><span>Critical</span><strong>{{ summary.critical }}</strong><small>Active critical incidents</small></article><article><span>Acknowledged</span><strong>{{ counts.acknowledged }}</strong><small>In current result set</small></article><article class="available"><span>Resolved</span><strong>{{ counts.resolved }}</strong><small>In current result set</small></article></div>

    <article class="alert-table-panel event-panel"><header><div class="alert-filters"><select v-model="statusFilter" @change="load()"><option value="">All states</option><option value="FIRING">Firing</option><option value="ACKNOWLEDGED">Acknowledged</option><option value="RESOLVED">Resolved</option></select><select v-model="severityFilter" @change="load()"><option value="">All severities</option><option value="CRITICAL">Critical</option><option value="WARNING">Warning</option><option value="INFO">Info</option></select><select v-model="serverFilter" @change="load()"><option value="">All servers</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></div><button class="refresh-button" @click="load()">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></header>
      <div v-if="loading && !events.length" class="table-empty"><span class="loading-ring" /><strong>Loading incident timeline</strong></div>
      <div v-else-if="!events.length" class="table-empty alert-clear"><span>✓</span><strong>No alert events match this view</strong><small>When a configured condition remains true for its duration, its incident appears here.</small></div>
      <div v-else class="alert-event-list"><article v-for="event in events" :key="event.id" :class="['alert-event-card', event.severity.toLowerCase(), event.status.toLowerCase()]"><div class="event-marker"><span>!</span><i /></div><div class="event-main"><header><div><span class="severity-pill" :class="event.severity.toLowerCase()">{{ event.severity }}</span><strong>{{ event.ruleName }}</strong></div><small>{{ age(event.startedAt) }}</small></header><p>{{ event.message }}</p><div class="event-meta"><span>{{ event.serverName }}</span><span>{{ event.resourceType }} · {{ event.resourceName }}</span><span>Started {{ formatTime(event.startedAt) }}</span><span>Webhook {{ event.notificationStatus }}</span></div></div><aside><span class="event-status" :class="event.status.toLowerCase()"><i />{{ event.status }}</span><button v-if="event.status === 'FIRING' && auth.hasAnyRole(['ADMIN','DEVELOPER'])" @click="acknowledge(event)">Acknowledge</button><small v-if="event.resolvedAt">Recovered<br />{{ formatTime(event.resolvedAt) }}</small><small v-else-if="event.acknowledgedAt">by {{ event.acknowledgedByName }}<br />{{ formatTime(event.acknowledgedAt) }}</small></aside></article></div>
    </article>
  </section>
</template>
