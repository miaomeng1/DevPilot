<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { settingsApi, type UpdateSystemSettings } from '@/api/settings'
import { apiErrorMessage } from '@/api/client'
import { useSystemStore } from '@/stores/system'

const system = useSystemStore()
const loading = ref(false)
const saving = ref(false)
const saved = ref(false)
const errorMessage = ref('')
const webhookConfigured = ref(false)
const webhookDestinationType = ref('NONE')
const form = reactive<UpdateSystemSettings>({
  systemName: 'DevPilot', logoUrl: '', defaultTheme: 'LIGHT', accessTokenTtlMinutes: 120,
  refreshTokenTtlHours: 168, agentHeartbeatTimeoutSeconds: 30, metricIntervalSeconds: 10,
  logDefaultLines: '100', webhookEnabled: false, webhookUrl: '',
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const value = await settingsApi.get()
    Object.assign(form, { ...value, logoUrl: value.logoUrl || '', logDefaultLines: String(value.logDefaultLines), webhookUrl: '' })
    webhookConfigured.value = value.webhookConfigured
    webhookDestinationType.value = value.webhookDestinationType
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'System settings could not be loaded') }
  finally { loading.value = false }
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
      </div>

      <aside class="settings-preview"><header><strong>Live identity preview</strong><small>Saved values update all new page views</small></header><div class="identity-preview"><img v-if="form.logoUrl" :src="form.logoUrl" alt="Logo preview" /><div v-else class="brand-symbol compact"><i /><i /><i /></div><div><strong>{{ form.systemName || 'DevPilot' }}</strong><small>Developer Cloud Console</small></div></div><dl><div><dt>Access token</dt><dd>{{ form.accessTokenTtlMinutes }} min</dd></div><div><dt>Refresh session</dt><dd>{{ form.refreshTokenTtlHours }} h</dd></div><div><dt>Agent offline</dt><dd>{{ form.agentHeartbeatTimeoutSeconds }} s</dd></div><div><dt>Collection</dt><dd>{{ form.metricIntervalSeconds }} s</dd></div></dl><p>Existing access tokens retain their signed expiry. Refresh sessions and newly issued tokens adopt changes immediately.</p></aside>
    </div>
  </section>
</template>
