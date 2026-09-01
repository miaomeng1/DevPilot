<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { alertsApi, type AlertMetricType, type AlertRule, type AlertRulePayload } from '@/api/alerts'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const servers = useServerStore()
const rules = ref<AlertRule[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)
const errorMessage = ref('')
const webhook = ref({ enabled: false, configured: false, destinationType: 'NONE' })
const webhookUrl = ref('')
const webhookSaving = ref(false)

const metricOptions: { value: AlertMetricType; label: string; detail: string }[] = [
  { value: 'SERVER_CPU', label: 'Server CPU', detail: 'CPU utilization percentage' },
  { value: 'SERVER_MEMORY', label: 'Server memory', detail: 'Memory utilization percentage' },
  { value: 'SERVER_DISK', label: 'Server disk', detail: 'Root disk utilization percentage' },
  { value: 'AGENT_OFFLINE', label: 'Agent offline', detail: 'Heartbeat timeout state' },
  { value: 'CONTAINER_STOPPED', label: 'Container stopped', detail: 'Any discovered non-running container' },
  { value: 'APP_UNHEALTHY', label: 'Application unhealthy', detail: 'Failed Agent-side health check' },
]

const form = reactive<AlertRulePayload>({
  name: '', metricType: 'SERVER_CPU', operator: 'GT', threshold: 90,
  durationSeconds: 300, severity: 'WARNING', serverId: null, enabled: true,
})

const isMetricRule = computed(() => form.metricType.startsWith('SERVER_'))
const summary = computed(() => ({
  total: rules.value.length,
  enabled: rules.value.filter((rule) => rule.enabled).length,
  critical: rules.value.filter((rule) => rule.enabled && rule.severity === 'CRITICAL').length,
}))

function labelForMetric(type: AlertMetricType) {
  return metricOptions.find((item) => item.value === type)?.label || type
}

function durationLabel(seconds: number) {
  if (seconds === 0) return 'Immediate'
  if (seconds % 3600 === 0) return `${seconds / 3600}h`
  if (seconds % 60 === 0) return `${seconds / 60}m`
  return `${seconds}s`
}

function conditionLabel(rule: AlertRule) {
  if (!rule.metricType.startsWith('SERVER_')) return 'State is abnormal'
  const symbols = { GT: '>', GTE: '≥', LT: '<', LTE: '≤', EQ: '=', NE: '≠' }
  return `${symbols[rule.operator]} ${rule.threshold}% for ${durationLabel(rule.durationSeconds)}`
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    rules.value = await alertsApi.rules()
    if (auth.hasAnyRole(['ADMIN'])) webhook.value = await alertsApi.webhook()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Alert rules could not be loaded')
  } finally { loading.value = false }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', metricType: 'SERVER_CPU', operator: 'GT', threshold: 90,
    durationSeconds: 300, severity: 'WARNING', serverId: null, enabled: true })
  dialogOpen.value = true
}

function openEdit(rule: AlertRule) {
  editingId.value = rule.id
  Object.assign(form, { name: rule.name, metricType: rule.metricType, operator: rule.operator,
    threshold: rule.threshold, durationSeconds: rule.durationSeconds, severity: rule.severity,
    serverId: rule.serverId, enabled: rule.enabled })
  dialogOpen.value = true
}

function metricChanged() {
  if (isMetricRule.value) {
    if (form.threshold === null) form.threshold = 90
  } else {
    form.operator = 'EQ'
    form.threshold = 1
  }
}

async function saveRule() {
  if (!form.name || (isMetricRule.value && form.threshold === null)) return
  saving.value = true
  errorMessage.value = ''
  try {
    if (editingId.value) await alertsApi.updateRule(editingId.value, { ...form })
    else await alertsApi.createRule({ ...form })
    dialogOpen.value = false
    await load()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Alert rule could not be saved')
  } finally { saving.value = false }
}

async function toggleRule(rule: AlertRule) {
  try {
    await alertsApi.updateRule(rule.id, { name: rule.name, metricType: rule.metricType, operator: rule.operator,
      threshold: rule.threshold, durationSeconds: rule.durationSeconds, severity: rule.severity,
      serverId: rule.serverId, enabled: !rule.enabled })
    await load()
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'Rule state could not be changed') }
}

async function removeRule(rule: AlertRule) {
  if (!window.confirm(`Delete alert rule “${rule.name}”? Existing event history will be preserved.`)) return
  try { await alertsApi.deleteRule(rule.id); await load() }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Alert rule could not be deleted') }
}

async function saveWebhook() {
  webhookSaving.value = true
  errorMessage.value = ''
  try {
    webhook.value = await alertsApi.updateWebhook(webhook.value.enabled, webhookUrl.value.trim() || undefined)
    webhookUrl.value = ''
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'Webhook configuration could not be saved') }
  finally { webhookSaving.value = false }
}

onMounted(async () => { await servers.load(); await load() })
</script>

<template>
  <section class="alerts-view">
    <header class="page-heading alert-heading"><div><p class="eyebrow">INCIDENT DETECTION</p><h1>Alert rules</h1><span>Durable threshold evaluation across infrastructure and application health.</span></div><button v-if="auth.hasAnyRole(['ADMIN'])" class="primary-compact" @click="openCreate"><b>＋</b>Create rule</button></header>
    <nav class="alert-tabs"><RouterLink to="/alerts">Events</RouterLink><RouterLink class="active" to="/alerts/rules">Rules</RouterLink></nav>
    <p v-if="errorMessage && !dialogOpen" class="inline-error">{{ errorMessage }}</p>

    <div class="alert-summary"><article><span>Rules</span><strong>{{ summary.total }}</strong><small>Configured detectors</small></article><article class="available"><span>Enabled</span><strong>{{ summary.enabled }}</strong><small>Evaluated every 10 seconds</small></article><article class="issues"><span>Critical policies</span><strong>{{ summary.critical }}</strong><small>Enabled critical severity</small></article><article><span>Webhook</span><strong class="summary-word">{{ webhook.enabled ? 'ON' : 'OFF' }}</strong><small>{{ webhook.configured ? webhook.destinationType : 'Not configured' }}</small></article></div>

    <div class="alert-rule-layout">
      <article class="alert-table-panel">
        <header><div><strong>Detection policies</strong><small>Conditions persist through control-plane restarts</small></div><button class="refresh-button" @click="load">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></header>
        <div v-if="loading && !rules.length" class="table-empty"><span class="loading-ring" /><strong>Loading alert rules</strong></div>
        <div v-else-if="!rules.length" class="table-empty"><span class="server-empty-glyph">!</span><strong>No alert rules configured</strong><small>Create a policy for CPU, memory, disk, Agent, container, or application state.</small><button v-if="auth.hasAnyRole(['ADMIN'])" @click="openCreate">Create first rule</button></div>
        <div v-else class="server-table-wrap"><table class="server-table alert-rule-table"><thead><tr><th>Rule</th><th>Condition</th><th>Scope</th><th>Severity</th><th>Status</th><th v-if="auth.hasAnyRole(['ADMIN'])">Actions</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id">
          <td><div class="alert-rule-name"><span :class="rule.severity.toLowerCase()">!</span><div><strong>{{ rule.name }}</strong><small>{{ labelForMetric(rule.metricType) }}</small></div></div></td>
          <td><strong class="cell-primary">{{ conditionLabel(rule) }}</strong><small class="cell-secondary">Duration gate</small></td>
          <td><strong class="cell-primary">{{ rule.serverName }}</strong><small class="cell-secondary">{{ rule.serverId ? 'Single server' : 'Global policy' }}</small></td>
          <td><span class="severity-pill" :class="rule.severity.toLowerCase()">{{ rule.severity }}</span></td>
          <td><span class="status-badge" :class="rule.enabled ? 'online' : 'unknown'"><i />{{ rule.enabled ? 'ENABLED' : 'DISABLED' }}</span></td>
          <td v-if="auth.hasAnyRole(['ADMIN'])"><div class="row-actions"><button @click="openEdit(rule)">Edit</button><button @click="toggleRule(rule)">{{ rule.enabled ? 'Disable' : 'Enable' }}</button><button class="danger" @click="removeRule(rule)">Delete</button></div></td>
        </tr></tbody></table></div>
      </article>

      <article v-if="auth.hasAnyRole(['ADMIN'])" class="webhook-panel"><header><div><strong>Webhook delivery</strong><small>Encrypted with the deployment master key</small></div><span :class="{ live: webhook.enabled }"><i />{{ webhook.enabled ? 'ACTIVE' : 'OFF' }}</span></header><div><p>Firing and resolved transitions support Feishu, WeCom, Discord, and custom JSON endpoints.</p><label><span>Destination URL</span><input v-model="webhookUrl" type="url" autocomplete="off" :placeholder="webhook.configured ? 'Configured · enter a value to replace' : 'https://hooks.example.com/devpilot'" /></label><label class="webhook-toggle"><input v-model="webhook.enabled" type="checkbox" /><span>Enable notifications</span></label><button :disabled="webhookSaving || (webhook.enabled && !webhook.configured && !webhookUrl)" @click="saveWebhook">{{ webhookSaving ? 'Saving…' : 'Save webhook' }}</button><small>Failed requests retry up to five times with bounded exponential backoff. Redirects are not followed.</small></div></article>
    </div>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="dialogOpen = false"><section class="server-dialog alert-rule-dialog" role="dialog" aria-modal="true"><header><div><span>ALERT POLICY</span><h2>{{ editingId ? 'Edit rule' : 'Create alert rule' }}</h2></div><button aria-label="Close" @click="dialogOpen = false">×</button></header><div class="dialog-body application-form">
      <label><span>Rule name</span><input v-model.trim="form.name" maxlength="120" placeholder="Production server CPU high" /></label>
      <div class="form-grid"><label><span>Metric type</span><select v-model="form.metricType" @change="metricChanged"><option v-for="item in metricOptions" :key="item.value" :value="item.value">{{ item.label }}</option></select><small>{{ metricOptions.find((item) => item.value === form.metricType)?.detail }}</small></label><label><span>Server scope</span><select v-model="form.serverId"><option :value="null">All servers</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label></div>
      <div class="form-grid" v-if="isMetricRule"><label><span>Condition</span><select v-model="form.operator"><option value="GT">Greater than (&gt;)</option><option value="GTE">Greater than or equal (≥)</option><option value="LT">Less than (&lt;)</option><option value="LTE">Less than or equal (≤)</option><option value="EQ">Equal (=)</option><option value="NE">Not equal (≠)</option></select></label><label><span>Threshold (%)</span><input v-model.number="form.threshold" type="number" min="0" max="100" step="0.1" /></label></div>
      <div class="form-grid"><label><span>Sustained duration</span><select v-model.number="form.durationSeconds"><option :value="0">Immediate</option><option :value="30">30 seconds</option><option :value="60">1 minute</option><option :value="300">5 minutes</option><option :value="600">10 minutes</option><option :value="1800">30 minutes</option></select></label><label><span>Severity</span><select v-model="form.severity"><option value="INFO">Info</option><option value="WARNING">Warning</option><option value="CRITICAL">Critical</option></select></label></div>
      <label class="rule-enabled"><input v-model="form.enabled" type="checkbox" /><span>Enable rule immediately after saving</span></label>
      <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    </div><footer><button @click="dialogOpen = false">Cancel</button><button class="dialog-primary" :disabled="saving || !form.name" @click="saveRule">{{ saving ? 'Saving…' : 'Save rule' }} <b>→</b></button></footer></section></div>
  </section>
</template>
