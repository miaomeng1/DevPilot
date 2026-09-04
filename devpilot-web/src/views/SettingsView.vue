<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { settingsApi, type UpdateSystemSettings } from '@/api/settings'
import { observabilityApi, type ObservabilityStatus } from '@/api/observability'
import { publicApiAdmin, type ApiAccessToken } from '@/api/public-api'
import { automationApi, type AutomationDelivery, type AutomationEventType, type AutomationWebhook } from '@/api/automation'
import { apiErrorMessage } from '@/api/client'
import { useSystemStore } from '@/stores/system'

const system = useSystemStore()
const loading = ref(false)
const saving = ref(false)
const saved = ref(false)
const errorMessage = ref('')
const webhookConfigured = ref(false)
const webhookDestinationType = ref('NONE')
const observability = ref<ObservabilityStatus | null>(null)
const copied = ref('')
const apiTokens = ref<ApiAccessToken[]>([])
const tokenName = ref('Home automation')
const tokenLifetime = ref(90)
const tokenBusy = ref(false)
const oneTimeToken = ref('')
const webhooks = ref<AutomationWebhook[]>([])
const deliveries = ref<AutomationDelivery[]>([])
const webhookName = ref('Personal automation')
const webhookEndpoint = ref('')
const webhookEvents = ref<AutomationEventType[]>(['ALERT_FIRING', 'ALERT_RESOLVED', 'DEPLOYMENT_HEALTHY', 'DEPLOYMENT_FAILED'])
const webhookBusy = ref(false)
const oneTimeWebhookSecret = ref('')
const eventChoices: { value: AutomationEventType; label: string }[] = [{ value: 'ALERT_FIRING', label: '告警触发' }, { value: 'ALERT_RESOLVED', label: '告警恢复' }, { value: 'DEPLOYMENT_HEALTHY', label: '部署成功' }, { value: 'DEPLOYMENT_FAILED', label: '部署失败' }]
const form = reactive<UpdateSystemSettings>({
  systemName: 'DevPilot', logoUrl: '', defaultTheme: 'LIGHT', accessTokenTtlMinutes: 120,
  refreshTokenTtlHours: 168, agentHeartbeatTimeoutSeconds: 30, metricIntervalSeconds: 10,
  logDefaultLines: '100', webhookEnabled: false, webhookUrl: '',
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [value, telemetry, tokens, hooks, recentDeliveries] = await Promise.all([settingsApi.get(), observabilityApi.status(), publicApiAdmin.tokens(), automationApi.webhooks(), automationApi.deliveries()])
    Object.assign(form, { ...value, logoUrl: value.logoUrl || '', logDefaultLines: String(value.logDefaultLines), webhookUrl: '' })
    webhookConfigured.value = value.webhookConfigured
    webhookDestinationType.value = value.webhookDestinationType
    observability.value = telemetry
    apiTokens.value = tokens
    webhooks.value = hooks
    deliveries.value = recentDeliveries
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'System settings could not be loaded') }
  finally { loading.value = false }
}

async function createApiToken() {
  if (!tokenName.value.trim()) return
  tokenBusy.value = true
  errorMessage.value = ''
  try {
    const expiry = new Date(Date.now() + tokenLifetime.value * 86_400_000).toISOString().slice(0, 19)
    const result = await publicApiAdmin.createToken(tokenName.value.trim(), expiry)
    apiTokens.value.unshift(result.token)
    oneTimeToken.value = result.oneTimeSecret
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'API Token 创建失败') }
  finally { tokenBusy.value = false }
}

async function revokeApiToken(token: ApiAccessToken) {
  tokenBusy.value = true
  try {
    await publicApiAdmin.revokeToken(token.id)
    token.status = 'REVOKED'
    token.revokedAt = new Date().toISOString()
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'API Token 撤销失败') }
  finally { tokenBusy.value = false }
}

function shortDate(value: string | null) {
  return value ? new Date(value).toLocaleDateString() : '从未 Never'
}

async function createWebhook() {
  if (!webhookName.value.trim() || !webhookEndpoint.value.trim() || !webhookEvents.value.length) return
  webhookBusy.value = true
  try {
    const result = await automationApi.create(webhookName.value.trim(), webhookEndpoint.value.trim(), webhookEvents.value)
    webhooks.value.unshift(result.subscription)
    oneTimeWebhookSecret.value = result.oneTimeSecret
    webhookEndpoint.value = ''
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'Webhook 订阅创建失败') }
  finally { webhookBusy.value = false }
}

async function toggleWebhook(item: AutomationWebhook) {
  webhookBusy.value = true
  try { Object.assign(item, await automationApi.enabled(item.id, !item.enabled)) }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Webhook 状态更新失败') }
  finally { webhookBusy.value = false }
}

async function deleteWebhook(item: AutomationWebhook) {
  webhookBusy.value = true
  try { await automationApi.remove(item.id); webhooks.value = webhooks.value.filter((value) => value.id !== item.id) }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Webhook 删除失败') }
  finally { webhookBusy.value = false }
}

async function retryDelivery(item: AutomationDelivery) {
  webhookBusy.value = true
  try { await automationApi.retry(item.id); item.status = 'PENDING'; item.attemptCount = 0; item.errorMessage = null }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Webhook 重发失败') }
  finally { webhookBusy.value = false }
}

async function copy(value: string, key: string) {
  await navigator.clipboard.writeText(value)
  copied.value = key
  window.setTimeout(() => { if (copied.value === key) copied.value = '' }, 1600)
}

async function save() {
  saving.value = true
  saved.value = false
  errorMessage.value = ''
  try {
    const value = await settingsApi.update({ ...form })
    webhookConfigured.value = value.webhookConfigured
    webhookDestinationType.value = value.webhookDestinationType
    form.webhookUrl = ''
    await system.refresh()
    saved.value = true
    window.setTimeout(() => { saved.value = false }, 2500)
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'System settings could not be saved') }
  finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <section class="settings-view">
    <header class="page-heading settings-heading"><div><p class="eyebrow">CONTROL PLANE POLICY</p><h1>System settings</h1><span>Branding, session policy, Agent cadence, logs, and notification delivery.</span></div><button class="primary-compact" :disabled="saving || loading" @click="save"><b>{{ saved ? '✓' : '↥' }}</b>{{ saved ? 'Saved' : saving ? 'Saving…' : 'Save changes' }}</button></header>
    <nav class="settings-tabs"><RouterLink class="active" to="/settings">General</RouterLink><RouterLink to="/settings/users">Users & roles</RouterLink></nav>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="settings-layout" :class="{ 'is-loading': loading }">
      <div class="settings-stack">
        <article class="settings-panel"><header><span>01</span><div><strong>Console identity</strong><small>Applied to login, navigation, and browser titles</small></div></header><div class="settings-form"><label><span>System name</span><input v-model.trim="form.systemName" maxlength="80" /></label><label><span>Logo URL</span><input v-model.trim="form.logoUrl" maxlength="1000" placeholder="/logo.svg or https://cdn.example.com/logo.svg" /><small>Use an HTTPS URL or a path served by the web container.</small></label><div class="theme-choice"><span>Default theme</span><div><label v-for="item in ['DARK','LIGHT','SYSTEM']" :key="item" :class="{ selected: form.defaultTheme === item }"><input v-model="form.defaultTheme" type="radio" :value="item" /><i :class="item.toLowerCase()" />{{ item }}</label></div></div></div></article>

        <article class="settings-panel"><header><span>02</span><div><strong>Authentication policy</strong><small>New sessions use updated lifetimes immediately</small></div></header><div class="settings-form settings-grid"><label><span>Access token lifetime</span><div class="unit-input"><input v-model.number="form.accessTokenTtlMinutes" type="number" min="5" max="1440" /><b>minutes</b></div><small>5 minutes to 24 hours</small></label><label><span>Refresh session lifetime</span><div class="unit-input"><input v-model.number="form.refreshTokenTtlHours" type="number" min="1" max="2160" /><b>hours</b></div><small>1 hour to 90 days</small></label></div></article>

        <article class="settings-panel"><header><span>03</span><div><strong>Agent & telemetry</strong><small>Agents receive collection cadence through heartbeat responses</small></div></header><div class="settings-form settings-grid"><label><span>Offline timeout</span><div class="unit-input"><input v-model.number="form.agentHeartbeatTimeoutSeconds" type="number" min="15" max="600" /><b>seconds</b></div><small>ONLINE changes to OFFLINE after this gap</small></label><label><span>Collection interval</span><div class="unit-input"><input v-model.number="form.metricIntervalSeconds" type="number" min="5" max="300" /><b>seconds</b></div><small>Metrics, Docker, and Nginx inventories</small></label><label><span>Default log tail</span><select v-model="form.logDefaultLines"><option value="100">100 lines</option><option value="500">500 lines</option></select><small>Initial Docker log stream window</small></label></div></article>

        <article class="settings-panel"><header><span>04</span><div><strong>Alert webhook</strong><small>Credential encrypted at rest with AES-GCM</small></div><span class="settings-state" :class="{ live: form.webhookEnabled }"><i />{{ form.webhookEnabled ? webhookDestinationType : 'OFF' }}</span></header><div class="settings-form"><label><span>Webhook URL</span><input v-model.trim="form.webhookUrl" type="url" maxlength="2000" autocomplete="off" :placeholder="webhookConfigured ? 'Configured · leave blank to keep current credential' : 'https://hooks.example.com/devpilot'" /></label><label class="settings-check"><input v-model="form.webhookEnabled" type="checkbox" /><span>Send FIRING and RESOLVED transitions</span></label><small class="settings-help">Feishu, WeCom, and Discord receive native text payloads. Other endpoints receive structured DevPilot JSON. Delivery retries five times and never follows redirects.</small></div></article>

        <article class="settings-panel observability-panel"><header><span>05</span><div><strong>可观测性导出 Observability</strong><small>Prometheus 拉取或通过 OTLP/HTTP 主动推送</small></div><span class="settings-state" :class="{ live: observability?.prometheusEnabled || observability?.otlpEnabled }"><i />{{ observability?.prometheusEnabled || observability?.otlpEnabled ? 'READY' : 'OFF' }}</span></header><div class="observability-options"><section><div class="export-title"><span class="export-mark prometheus">P</span><div><strong>Prometheus scrape</strong><small>独立 Bearer Token · 默认关闭</small></div><b :class="{ live: observability?.prometheusEnabled }">{{ observability?.prometheusEnabled ? '已启用' : '未配置' }}</b></div><p>暴露 JVM、HTTP 与 DevPilot 服务器、容器、应用和告警汇总指标。</p><code>GET {{ observability?.prometheusPath || '/actuator/prometheus' }}</code><button type="button" @click="copy('authorization:\n  credentials_file: /etc/prometheus/devpilot-token', 'prom')">{{ copied === 'prom' ? '已复制 ✓' : '复制 Prometheus 配置' }}</button></section><section><div class="export-title"><span class="export-mark otlp">O</span><div><strong>OpenTelemetry OTLP</strong><small>{{ observability?.otlpProtocol || 'OTLP/HTTP protobuf' }}</small></div><b :class="{ live: observability?.otlpEnabled }">{{ observability?.otlpEnabled ? '已启用' : '未配置' }}</b></div><p>使用标准 OTEL 环境变量发送同一组低基数指标，无需在页面保存密钥。</p><code>OTEL_METRICS_ENABLED=true</code><button type="button" @click="copy('OTEL_METRICS_ENABLED=true\nOTEL_EXPORTER_OTLP_METRICS_ENDPOINT=https://collector.example/v1/metrics', 'otlp')">{{ copied === 'otlp' ? '已复制 ✓' : '复制环境变量' }}</button></section></div><footer>快照每 {{ observability?.snapshotIntervalSeconds || 30 }} 秒更新 · 不导出镜像地址、应用名或密钥</footer></article>

        <article class="settings-panel api-token-panel"><header><span>06</span><div><strong>自动化 API Tokens</strong><small>稳定只读 API v1 · 可撤销 · 明文仅显示一次</small></div><span class="settings-state" :class="{ live: apiTokens.some((item) => item.status === 'ACTIVE') }"><i />{{ apiTokens.filter((item) => item.status === 'ACTIVE').length }} ACTIVE</span></header><div v-if="oneTimeToken" class="one-time-token"><div><strong>现在复制 Save this token now</strong><small>关闭或刷新后无法再次查看。</small></div><code>{{ oneTimeToken }}</code><button type="button" @click="copy(oneTimeToken, 'api')">{{ copied === 'api' ? '已复制 ✓' : '复制 Copy' }}</button><button type="button" class="dismiss-token" @click="oneTimeToken = ''">完成</button></div><div class="token-create"><label><span>名称 Name</span><input v-model.trim="tokenName" maxlength="120" /></label><label><span>有效期 Expiry</span><select v-model.number="tokenLifetime"><option :value="30">30 天</option><option :value="90">90 天</option><option :value="365">1 年</option></select></label><button type="button" :disabled="tokenBusy || !tokenName.trim()" @click="createApiToken">＋ 创建只读 Token</button></div><div class="token-list"><div v-if="!apiTokens.length" class="token-empty">还没有 API Token · Create one for scripts, dashboards, or home automation.</div><article v-for="token in apiTokens" :key="token.id"><div><strong>{{ token.name }}</strong><code>{{ token.prefix }}••••••••</code></div><span>READ</span><small>最近使用 {{ shortDate(token.lastUsedAt) }} · 到期 {{ shortDate(token.expiresAt) }}</small><b :class="token.status.toLowerCase()">{{ token.status }}</b><button v-if="token.status === 'ACTIVE'" type="button" :disabled="tokenBusy" @click="revokeApiToken(token)">撤销</button></article></div><footer><code>GET /api/v1/status</code><button type="button" @click="copy('curl -H &quot;Authorization: Bearer $DEVPILOT_API_TOKEN&quot; https://devpilot.example.com/api/v1/status', 'curl')">{{ copied === 'curl' ? '已复制 ✓' : '复制 curl 示例' }}</button></footer></article>

        <article class="settings-panel automation-panel"><header><span>07</span><div><strong>事件订阅 Event webhooks</strong><small>CloudEvents 1.0 · HMAC-SHA256 · 持久重试</small></div><span class="settings-state" :class="{ live: webhooks.some((item) => item.enabled) }"><i />{{ webhooks.filter((item) => item.enabled).length }} LIVE</span></header><div v-if="oneTimeWebhookSecret" class="one-time-token"><div><strong>签名 Secret 仅显示一次</strong><small>接收端必须验证原始 UTF-8 body。</small></div><code>{{ oneTimeWebhookSecret }}</code><button type="button" @click="copy(oneTimeWebhookSecret, 'whsec')">{{ copied === 'whsec' ? '已复制 ✓' : '复制 Secret' }}</button><button type="button" class="dismiss-token" @click="oneTimeWebhookSecret = ''">完成</button></div><div class="webhook-create"><div class="webhook-fields"><label><span>名称 Name</span><input v-model.trim="webhookName" maxlength="120" /></label><label><span>HTTPS Endpoint</span><input v-model.trim="webhookEndpoint" type="url" maxlength="2000" placeholder="https://automation.example.com/devpilot" /></label><button type="button" :disabled="webhookBusy || !webhookName.trim() || !webhookEndpoint.trim() || !webhookEvents.length" @click="createWebhook">＋ 创建订阅</button></div><div class="event-checks"><label v-for="choice in eventChoices" :key="choice.value"><input v-model="webhookEvents" type="checkbox" :value="choice.value" /><span>{{ choice.label }}</span><small>{{ choice.value }}</small></label></div></div><div class="webhook-grid"><section><header>订阅 Subscriptions</header><div v-if="!webhooks.length" class="token-empty">尚未配置事件接收端。</div><article v-for="item in webhooks" :key="item.id"><div><strong>{{ item.name }}</strong><small>{{ item.endpointHost }} · {{ item.eventTypes.length }} events</small></div><b :class="{ live: item.enabled }">{{ item.enabled ? 'LIVE' : 'PAUSED' }}</b><button type="button" @click="toggleWebhook(item)">{{ item.enabled ? '暂停' : '启用' }}</button><button type="button" class="remove-hook" @click="deleteWebhook(item)">删除</button></article></section><section><header>最近投递 Deliveries</header><div v-if="!deliveries.length" class="token-empty">事件发生后，投递状态会显示在这里。</div><article v-for="item in deliveries.slice(0, 6)" :key="item.id"><div><strong>{{ item.eventType }}</strong><small>{{ item.subscriptionName }} · {{ item.subject }}</small></div><b :class="item.status.toLowerCase()">{{ item.status }}<small v-if="item.responseCode">HTTP {{ item.responseCode }}</small></b><button v-if="item.status === 'FAILED'" type="button" @click="retryDelivery(item)">重发</button></article></section></div><footer><span>签名头 <code>X-DevPilot-Signature-256: sha256=…</code></span><span>同一事件重发保持相同 Delivery ID</span></footer></article>
      </div>

      <aside class="settings-preview"><header><strong>Live identity preview</strong><small>Saved values update all new page views</small></header><div class="identity-preview"><img v-if="form.logoUrl" :src="form.logoUrl" alt="Logo preview" /><div v-else class="brand-symbol compact"><i /><i /><i /></div><div><strong>{{ form.systemName || 'DevPilot' }}</strong><small>Developer Cloud Console</small></div></div><dl><div><dt>Access token</dt><dd>{{ form.accessTokenTtlMinutes }} min</dd></div><div><dt>Refresh session</dt><dd>{{ form.refreshTokenTtlHours }} h</dd></div><div><dt>Agent offline</dt><dd>{{ form.agentHeartbeatTimeoutSeconds }} s</dd></div><div><dt>Collection</dt><dd>{{ form.metricIntervalSeconds }} s</dd></div></dl><p>Existing access tokens retain their signed expiry. Refresh sessions and newly issued tokens adopt changes immediately.</p></aside>
    </div>
  </section>
</template>
