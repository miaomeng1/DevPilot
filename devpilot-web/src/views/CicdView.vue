<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { applicationApi, type Application } from '@/api/applications'
import { cicdApi, type CicdConfiguration, type CicdConfigurationPayload, type CicdDeployment, type PipelineRun } from '@/api/cicd'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const applications = ref<Application[]>([])
const selectedId = ref('')
const configuration = ref<CicdConfiguration | null>(null)
const runs = ref<PipelineRun[]>([])
const deployments = ref<CicdDeployment[]>([])
const loading = ref(false)
const saving = ref(false)
const rollingBack = ref('')
const errorMessage = ref('')
const successMessage = ref('')
const revealedSecret = ref('')
let pollTimer: number | undefined

const form = reactive<CicdConfigurationPayload>({
  repositoryProvider: 'GITHUB', repositoryUrl: '', branchName: 'main', deploymentProvider: 'COOLIFY', deploymentMode: 'API',
  deploymentWebhookUrl: '', providerBaseUrl: '', providerApiToken: '', providerResourceId: '',
  autoDeploy: true, productionApproval: true, autoRollback: true, healthTimeoutSeconds: 120, rotateCallbackSecret: false,
})

const selectedApp = computed(() => applications.value.find((item) => item.id === selectedId.value) || null)
const canConfigure = computed(() => auth.hasAnyRole(['ADMIN']))
const summary = computed(() => ({
  total: runs.value.length,
  passed: runs.value.filter((run) => run.status === 'SUCCEEDED').length,
  failed: runs.value.filter((run) => run.status === 'FAILED').length,
  deployed: deployments.value.filter((item) => item.status === 'HEALTHY').length,
}))

async function initialize() {
  loading.value = true
  errorMessage.value = ''
  try {
    applications.value = await applicationApi.list()
    selectedId.value = applications.value[0]?.id || ''
    if (selectedId.value) await loadApplication()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'CI/CD workspace could not be loaded')
  } finally { loading.value = false }
}

async function loadApplication(silent = false) {
  if (!selectedId.value) return
  if (!silent) loading.value = true
  errorMessage.value = ''
  revealedSecret.value = ''
  try {
    const [configurationResult, pipelineRuns, deploymentHistory] = await Promise.all([
      cicdApi.configuration(selectedId.value).catch(() => null),
      cicdApi.runs(selectedId.value),
      cicdApi.deployments(selectedId.value),
    ])
    configuration.value = configurationResult
    runs.value = pipelineRuns
    deployments.value = deploymentHistory
    Object.assign(form, configurationResult ? {
      repositoryProvider: configurationResult.repositoryProvider,
      repositoryUrl: configurationResult.repositoryUrl,
      branchName: configurationResult.branchName,
      deploymentProvider: configurationResult.deploymentProvider,
      deploymentMode: configurationResult.deploymentMode,
      deploymentWebhookUrl: '',
      providerBaseUrl: '', providerApiToken: '', providerResourceId: configurationResult.providerResourceId || '',
      autoDeploy: configurationResult.autoDeploy,
      productionApproval: configurationResult.productionApproval,
      autoRollback: configurationResult.autoRollback,
      healthTimeoutSeconds: configurationResult.healthTimeoutSeconds,
      rotateCallbackSecret: false,
    } : {
      repositoryProvider: 'GITHUB', repositoryUrl: '', branchName: 'main', deploymentProvider: 'COOLIFY', deploymentMode: 'API',
      deploymentWebhookUrl: '', providerBaseUrl: '', providerApiToken: '', providerResourceId: '',
      autoDeploy: true, productionApproval: true, autoRollback: true, healthTimeoutSeconds: 120, rotateCallbackSecret: false,
    })
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Pipeline history could not be loaded')
  } finally { loading.value = false }
}

async function save() {
  const missingWebhook = form.deploymentMode === 'WEBHOOK' && !configuration.value && !form.deploymentWebhookUrl
  const missingApi = form.deploymentMode === 'API' && (!configuration.value || !configuration.value.providerBaseUrlConfigured || !configuration.value.providerApiTokenConfigured)
    && (!form.providerBaseUrl || !form.providerApiToken || !form.providerResourceId)
  if (!selectedId.value || !form.repositoryUrl || !form.branchName || missingWebhook || missingApi) return
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const saved = await cicdApi.saveConfiguration(selectedId.value, { ...form })
    configuration.value = saved
    revealedSecret.value = saved.oneTimeCallbackSecret || ''
    form.deploymentWebhookUrl = ''
    form.providerBaseUrl = ''
    form.providerApiToken = ''
    form.rotateCallbackSecret = false
    successMessage.value = 'CI/CD configuration saved. Provider credentials and callback secrets are encrypted at rest.'
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'CI/CD configuration could not be saved')
  } finally { saving.value = false }
}

async function rollback(deployment: CicdDeployment) {
  if (!selectedId.value || rollingBack.value) return
  rollingBack.value = deployment.id
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await cicdApi.rollback(selectedId.value, deployment.id)
    successMessage.value = `Rollback to ${deployment.imageUri} was accepted by ${deployment.provider}.`
    await loadApplication(true)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Rollback could not be triggered')
  } finally { rollingBack.value = '' }
}

async function copy(value: string) {
  await navigator.clipboard.writeText(value)
  successMessage.value = 'Copied to clipboard.'
}

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

function tone(value: string) {
  if (['SUCCEEDED', 'PASSED', 'HEALTHY'].includes(value)) return 'success'
  if (['FAILED', 'CANCELLED', 'UNHEALTHY', 'HEALTH_FAILED'].includes(value)) return 'danger'
  if (['RUNNING', 'TRIGGERING'].includes(value)) return 'running'
  return 'neutral'
}

watch(selectedId, () => void loadApplication())
onMounted(() => {
  void initialize()
  pollTimer = window.setInterval(() => void loadApplication(true), 15_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="cicd-view">
    <header class="page-heading cicd-heading">
      <div><span>DELIVERY CONTROL PLANE</span><h1>CI/CD</h1><p>Signed pipeline evidence, immutable images and controlled production deployment.</p></div>
      <label class="app-selector"><span>Application</span><select v-model="selectedId"><option v-for="app in applications" :key="app.id" :value="app.id">{{ app.name }} · {{ app.environment }}</option></select></label>
    </header>

    <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    <p v-if="successMessage" class="cicd-success">{{ successMessage }}</p>
    <div v-if="!applications.length && !loading" class="empty-panel"><strong>No applications yet</strong><p>Create and bind an application before configuring CI/CD.</p></div>

    <template v-else-if="selectedApp">
      <div class="cicd-summary">
        <article><span>Runs</span><strong>{{ summary.total }}</strong><small>Latest 100 retained here</small></article>
        <article><span>Passed</span><strong>{{ summary.passed }}</strong><small>Quality + security green</small></article>
        <article><span>Failed</span><strong>{{ summary.failed }}</strong><small>Deployment blocked</small></article>
        <article><span>Healthy</span><strong>{{ summary.deployed }}</strong><small>Post-deploy checks passed</small></article>
      </div>

      <div class="cicd-layout">
        <section class="cicd-panel">
          <header><div><strong>Pipeline contract</strong><small>Repository, protected branch and deployment adapter</small></div><span :class="{ live: configuration }"><i />{{ configuration ? 'Configured' : 'Not configured' }}</span></header>
          <form class="cicd-form" @submit.prevent="save">
            <label><span>CI provider</span><select v-model="form.repositoryProvider"><option>GITHUB</option><option>GITLAB</option><option>WOODPECKER</option></select></label>
            <label><span>Repository URL</span><input v-model="form.repositoryUrl" type="url" placeholder="https://github.com/org/repository" maxlength="1000" required /></label>
            <label><span>Protected branch</span><input v-model="form.branchName" maxlength="255" required /></label>
            <label><span>Deployment provider</span><select v-model="form.deploymentProvider"><option>COOLIFY</option><option>DOKPLOY</option></select></label>
            <label><span>Deployment mode</span><select v-model="form.deploymentMode"><option value="API">API · exact image</option><option value="WEBHOOK">Webhook · provider managed</option></select></label>
            <template v-if="form.deploymentMode === 'API'">
              <label><span>Provider base URL</span><input v-model="form.providerBaseUrl" type="url" :placeholder="configuration?.providerBaseUrlConfigured ? 'Leave blank to keep encrypted URL' : 'https://deploy.example.com'" maxlength="1000" /></label>
              <label><span>Resource ID</span><input v-model="form.providerResourceId" placeholder="Application UUID / ID" pattern="[A-Za-z0-9_-]+" maxlength="255" /></label>
              <label class="wide"><span>Provider API token</span><input v-model="form.providerApiToken" type="password" autocomplete="new-password" :placeholder="configuration?.providerApiTokenConfigured ? 'Leave blank to keep encrypted token' : 'Minimum-privilege API token'" maxlength="4000" /><small>DevPilot updates the exact sha-* image, then calls the provider deploy API.</small></label>
            </template>
            <label v-else class="wide"><span>Deployment webhook</span><input v-model="form.deploymentWebhookUrl" type="url" :placeholder="configuration?.deploymentWebhookConfigured ? 'Leave blank to keep the encrypted value' : 'Required for webhook mode'" maxlength="4000" /><small>The URL is encrypted and never returned by the API.</small></label>
            <label class="check"><input v-model="form.autoDeploy" type="checkbox" /><span>Automatically deploy only after tests and security scans pass</span></label>
            <label class="check"><input v-model="form.productionApproval" type="checkbox" /><span>Require protected-environment approval in the CI provider</span></label>
            <label class="check"><input v-model="form.autoRollback" type="checkbox" /><span>Automatically roll back to the latest verified healthy image when health checks fail</span></label>
            <label><span>Health timeout (seconds)</span><input v-model.number="form.healthTimeoutSeconds" type="number" min="30" max="1800" required /></label>
            <label v-if="configuration" class="check danger-check"><input v-model="form.rotateCallbackSecret" type="checkbox" /><span>Rotate callback secret and invalidate the old CI secret</span></label>
            <footer class="wide"><button v-if="canConfigure" class="primary-action" :disabled="saving" type="submit">{{ saving ? 'Saving…' : 'Save configuration' }}</button><small v-else>Administrator role is required to change deployment settings.</small></footer>
          </form>
        </section>

        <aside class="callback-panel">
          <header><strong>Signed callback</strong><small>CI reports evidence to DevPilot</small></header>
          <div v-if="configuration" class="callback-body">
            <label><span>Callback path</span><code>{{ configuration.callbackUrl }}</code><button @click="copy(configuration.callbackUrl)">Copy</button></label>
            <label v-if="revealedSecret" class="secret-reveal"><span>One-time callback secret</span><code>{{ revealedSecret }}</code><button @click="copy(revealedSecret)">Copy now</button><small>This value will not be shown again. Store it as a protected CI secret.</small></label>
            <p>Expected header: <code>X-DevPilot-Signature: sha256=&lt;HMAC&gt;</code></p>
            <p>Successful callbacks must prove test and security gates passed and use a digest or <code>sha-*</code> image tag.</p>
          </div>
          <div v-else class="callback-empty">Save the first configuration to generate a 256-bit callback secret.</div>
        </aside>
      </div>

      <section class="pipeline-panel">
        <header><div><strong>Pipeline evidence</strong><small>Commit, gates, immutable image and deployment request</small></div><button @click="loadApplication()">Refresh</button></header>
        <div class="table-scroll"><table class="console-table pipeline-table"><thead><tr><th>Run / commit</th><th>Pipeline</th><th>Tests</th><th>Security</th><th>Image</th><th>Deployment</th><th>Updated</th></tr></thead><tbody>
          <tr v-for="run in runs" :key="run.id"><td><a v-if="run.runUrl" :href="run.runUrl" target="_blank" rel="noreferrer">{{ run.externalRunId }}</a><strong v-else>{{ run.externalRunId }}</strong><code>{{ run.commitSha.slice(0, 12) }}</code></td><td><span class="pipeline-state" :class="tone(run.status)">{{ run.status }}</span></td><td><span class="pipeline-state" :class="tone(run.testStatus)">{{ run.testStatus }}</span></td><td><span class="pipeline-state" :class="tone(run.securityStatus)">{{ run.securityStatus }}</span></td><td><code class="image-uri">{{ run.imageUri || 'Not produced' }}</code></td><td><span class="pipeline-state" :class="tone(run.deployStatus)">{{ run.deployStatus }}</span><small v-if="run.deployError">{{ run.deployError }}</small></td><td>{{ formatTime(run.updatedAt) }}</td></tr>
          <tr v-if="!runs.length"><td colspan="7" class="table-empty">No signed pipeline callbacks received.</td></tr>
        </tbody></table></div>
      </section>

      <section class="pipeline-panel deployment-panel">
        <header><div><strong>Deployment verification & rollback</strong><small>Provider acceptance is followed by a fresh Agent health check</small></div></header>
        <div class="table-scroll"><table class="console-table deployment-table"><thead><tr><th>Started</th><th>Kind</th><th>Exact image</th><th>Provider</th><th>Health state</th><th>Evidence</th><th>Action</th></tr></thead><tbody>
          <tr v-for="deployment in deployments" :key="deployment.id">
            <td>{{ formatTime(deployment.startedAt) }}</td><td><span class="pipeline-state" :class="tone(deployment.deploymentKind)">{{ deployment.deploymentKind }}</span></td><td><code class="image-uri">{{ deployment.imageUri }}</code></td>
            <td>{{ deployment.provider }}<small v-if="deployment.providerDeploymentId">{{ deployment.providerDeploymentId }}</small></td>
            <td><span class="pipeline-state" :class="tone(deployment.status)">{{ deployment.status }}</span><small v-if="deployment.status === 'TRIGGERED'">Deadline {{ formatTime(deployment.healthDeadlineAt) }}</small></td>
            <td><details v-if="deployment.logs" class="deployment-log"><summary>View collected logs</summary><pre>{{ deployment.logs }}</pre></details><span v-else>Awaiting evidence</span></td>
            <td><button v-if="canConfigure && deployment.status === 'HEALTHY'" class="rollback-action" :disabled="!!rollingBack" @click="rollback(deployment)">{{ rollingBack === deployment.id ? 'Rolling back…' : 'Roll back here' }}</button><span v-else>—</span></td>
          </tr>
          <tr v-if="!deployments.length"><td colspan="7" class="table-empty">No deployments have been triggered.</td></tr>
        </tbody></table></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.cicd-view{max-width:1480px;margin:0 auto}.cicd-heading{align-items:center}.app-selector{display:grid;gap:6px;color:#718096;font-size:8px;font-weight:700}.app-selector select,.cicd-form input,.cicd-form select{height:38px;border:1px solid var(--line);border-radius:8px;padding:0 10px;color:var(--text);background:var(--panel);font-size:8px}.app-selector select{min-width:240px}.cicd-success{margin:0 0 12px;border:1px solid rgba(34,197,94,.2);border-radius:8px;padding:10px;color:#4ade80;background:rgba(34,197,94,.05);font-size:8px}.cicd-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;overflow:hidden;border:1px solid var(--line);border-radius:11px;background:var(--line)}.cicd-summary article{padding:16px;background:var(--panel)}.cicd-summary span,.cicd-summary small{display:block;color:#64748b;font-size:8px}.cicd-summary strong{display:block;margin:8px 0 5px;font-size:21px}.cicd-layout{display:grid;grid-template-columns:minmax(0,1fr) 350px;gap:14px;margin-top:14px}.cicd-panel,.callback-panel,.pipeline-panel{overflow:hidden;border:1px solid var(--line);border-radius:11px;background:var(--panel)}.cicd-panel>header,.callback-panel>header,.pipeline-panel>header{display:flex;min-height:58px;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);padding:0 15px}.cicd-panel header strong,.cicd-panel header small,.callback-panel header strong,.callback-panel header small,.pipeline-panel header strong,.pipeline-panel header small{display:block}.cicd-panel header strong,.callback-panel header strong,.pipeline-panel header strong{font-size:10px}.cicd-panel header small,.callback-panel header small,.pipeline-panel header small{margin-top:4px;color:#617087;font-size:8px}.cicd-panel>header>span{display:flex;align-items:center;gap:5px;color:#64748b;font-size:7px}.cicd-panel>header>span i{width:5px;height:5px;border-radius:50%;background:currentColor}.cicd-panel>header>span.live{color:#4ade80}.cicd-form{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:15px}.cicd-form label:not(.check){display:grid;gap:6px;color:#7d8ba0;font-size:8px;font-weight:700}.cicd-form label small{color:#5f6d81;font-weight:400}.cicd-form .wide{grid-column:1/-1}.check{display:flex;grid-column:1/-1;align-items:center;gap:8px;color:#8795a9;font-size:8px}.check input{accent-color:#2563eb}.danger-check{color:#f59e0b}.cicd-form footer{display:flex;align-items:center;justify-content:flex-end}.primary-action,.pipeline-panel>header>button,.callback-body button{height:31px;border:0;border-radius:7px;padding:0 12px;color:#fff;background:#2563eb;font-size:8px;font-weight:750}.callback-body{display:grid;gap:13px;padding:15px}.callback-body label{display:grid;grid-template-columns:1fr auto;gap:6px}.callback-body label>span,.callback-body label>small{grid-column:1/-1;color:#68778c;font-size:8px}.callback-body code{overflow:hidden;border:1px solid var(--line);border-radius:6px;padding:9px;color:#93c5fd;background:#080d18;font:8px ui-monospace,monospace;text-overflow:ellipsis}.callback-body button{height:auto}.callback-body p,.callback-empty{margin:0;color:#64748b;font-size:8px;line-height:1.6}.secret-reveal{border:1px solid rgba(245,158,11,.2);border-radius:8px;padding:10px;background:rgba(245,158,11,.04)}.callback-empty{padding:18px}.pipeline-panel{margin-top:14px}.pipeline-panel>header>button{border:1px solid var(--line);color:#8ba9d7;background:transparent}.pipeline-table{min-width:1120px}.pipeline-table td:first-child strong,.pipeline-table td:first-child a,.pipeline-table td:first-child code{display:block}.pipeline-table td:first-child a{color:#82adf5;text-decoration:none;font-weight:750}.pipeline-table td:first-child code{margin-top:5px;color:#69788d}.pipeline-state{display:inline-flex;border-radius:4px;padding:4px 6px;color:#94a3b8;background:rgba(100,116,139,.08);font-size:6px;font-weight:850}.pipeline-state.success{color:#4ade80;background:rgba(34,197,94,.08)}.pipeline-state.danger{color:#f87171;background:rgba(239,68,68,.08)}.pipeline-state.running{color:#60a5fa;background:rgba(59,130,246,.08)}.image-uri{display:block;max-width:260px;overflow:hidden;color:#a78bfa;text-overflow:ellipsis;white-space:nowrap}.pipeline-table td small{display:block;max-width:180px;overflow:hidden;margin-top:4px;color:#f87171;text-overflow:ellipsis;white-space:nowrap}.empty-panel{border:1px solid var(--line);border-radius:11px;padding:30px;text-align:center;background:var(--panel)}.empty-panel p{color:#64748b;font-size:8px}.table-empty{text-align:center;color:#64748b!important}@media(max-width:1050px){.cicd-layout{grid-template-columns:1fr}.cicd-summary{grid-template-columns:1fr 1fr}}@media(max-width:700px){.cicd-heading{align-items:stretch;flex-direction:column}.app-selector select{width:100%}.cicd-form{grid-template-columns:1fr}.cicd-form .wide{grid-column:1}.cicd-summary{gap:8px;border:0;background:transparent}.cicd-summary article{border:1px solid var(--line);border-radius:9px}}
.deployment-table{min-width:1180px}.deployment-log{max-width:480px;color:#7e8ca1}.deployment-log summary{cursor:pointer;color:#82adf5;font-size:7px;font-weight:750}.deployment-log pre{max-height:360px;overflow:auto;margin:8px 0 0;border:1px solid var(--line);border-radius:6px;padding:10px;color:#b8c4d6;background:#080d18;font:7px/1.55 ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}.rollback-action{height:28px;border:1px solid rgba(245,158,11,.25);border-radius:6px;padding:0 9px;color:#fbbf24;background:rgba(245,158,11,.05);font-size:7px;font-weight:750}.rollback-action:disabled{opacity:.5}.deployment-table td small{display:block;max-width:190px;overflow:hidden;margin-top:4px;color:#f59e0b;text-overflow:ellipsis;white-space:nowrap}
</style>
