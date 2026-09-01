<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { applicationApi, type Application, type ApplicationPayload } from '@/api/applications'
import { dockerApi, type DockerContainer } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const servers = useServerStore()
const applications = ref<Application[]>([])
const containers = ref<DockerContainer[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const query = ref('')
const environmentFilter = ref('ALL')
const errorMessage = ref('')
let pollTimer: number | undefined

const form = reactive<ApplicationPayload>({
  name: '', code: '', description: '', environment: 'DEV', serverId: '', containerSnapshotId: '',
  currentVersion: '', healthCheckUrl: '', accessUrl: '',
})

const canManage = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return applications.value.filter((application) => {
    const environmentMatches = environmentFilter.value === 'ALL' || application.environment === environmentFilter.value
    const textMatches = !needle || [application.name, application.code, application.serverName, application.containerName, application.dockerImage]
      .some((value) => value?.toLowerCase().includes(needle))
    return environmentMatches && textMatches
  })
})
const summary = computed(() => ({
  total: applications.value.length,
  healthy: applications.value.filter((application) => application.healthStatus === 'HEALTHY').length,
  attention: applications.value.filter((application) => application.healthStatus === 'UNHEALTHY' || ['WARNING', 'ERROR', 'OFFLINE'].includes(application.status)).length,
  production: applications.value.filter((application) => application.environment === 'PRODUCTION').length,
}))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    applications.value = await applicationApi.list()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Applications could not be loaded')
  } finally {
    loading.value = false
  }
}

async function loadContainers() {
  form.containerSnapshotId = ''
  containers.value = form.serverId ? await dockerApi.list(form.serverId) : []
  if (containers.value.length === 1) form.containerSnapshotId = containers.value[0]!.id
}

function openDialog() {
  Object.assign(form, {
    name: '', code: '', description: '', environment: 'DEV', serverId: servers.servers[0]?.id || '',
    containerSnapshotId: '', currentVersion: '', healthCheckUrl: '', accessUrl: '',
  })
  errorMessage.value = ''
  dialogOpen.value = true
  void loadContainers()
}

async function createApplication() {
  if (!form.name || !form.code || !form.serverId || !form.containerSnapshotId) return
  saving.value = true
  errorMessage.value = ''
  try {
    const created = await applicationApi.create({ ...form, code: form.code?.trim().toLowerCase() })
    applications.value.unshift(created)
    dialogOpen.value = false
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Application could not be created')
  } finally {
    saving.value = false
  }
}

function statusClass(application: Application) {
  if (application.status === 'RUNNING') return application.healthStatus === 'UNHEALTHY' ? 'warning' : 'online'
  if (application.status === 'WARNING') return 'warning'
  if (application.status === 'ERROR' || application.status === 'OFFLINE') return 'offline'
  return 'unknown'
}

function formatTime(value: string | null) {
  if (!value) return 'Not checked yet'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

onMounted(async () => {
  await servers.load()
  await load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="applications-view">
    <header class="page-heading applications-heading">
      <div><p class="eyebrow">SERVICE CATALOG</p><h1>Applications</h1><span>Docker workloads modeled as durable developer-facing services.</span></div>
      <button v-if="canManage" class="primary-compact" type="button" @click="openDialog"><b>＋</b>Register application</button>
    </header>
    <p v-if="errorMessage && !dialogOpen" class="inline-error">{{ errorMessage }}</p>

    <div class="application-summary">
      <article><span>Applications</span><strong>{{ summary.total }}</strong><small>Registered services</small></article>
      <article class="healthy"><span>Healthy</span><strong>{{ summary.healthy }}</strong><small>Passing Agent probes</small></article>
      <article class="attention"><span>Attention</span><strong>{{ summary.attention }}</strong><small>Runtime or health issue</small></article>
      <article><span>Production</span><strong>{{ summary.production }}</strong><small>Production environment</small></article>
    </div>

    <article class="application-table-panel">
      <header>
        <div class="table-search"><span>⌕</span><input v-model="query" placeholder="Filter application, container, image or server" /></div>
        <div class="application-tools"><select v-model="environmentFilter"><option value="ALL">All environments</option><option v-for="environment in ['DEV','TEST','STAGING','PRODUCTION']" :key="environment">{{ environment }}</option></select><button class="refresh-button" @click="load()">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></div>
      </header>
      <div v-if="loading && !applications.length" class="table-empty"><span class="loading-ring" /><strong>Loading service catalog</strong></div>
      <div v-else-if="!filtered.length" class="table-empty"><span class="server-empty-glyph">◈</span><strong>{{ query ? 'No matching applications' : 'No applications registered' }}</strong><small>Bind a Docker container to a named application and add an Agent-side health probe.</small><button v-if="canManage && !query" @click="openDialog">Register first application</button></div>
      <div v-else class="server-table-wrap"><table class="server-table application-table"><thead><tr><th>Application</th><th>Runtime</th><th>Health</th><th>Version / image</th><th>Server</th><th>Last check</th></tr></thead><tbody>
        <tr v-for="application in filtered" :key="application.id">
          <td><RouterLink class="node-cell node-link" :to="`/applications/${application.id}`"><span>{{ application.name.slice(0,2).toUpperCase() }}</span><div><strong>{{ application.name }}</strong><small class="mono">{{ application.code }} · {{ application.environment }}</small></div></RouterLink></td>
          <td><span class="status-badge" :class="statusClass(application)"><i />{{ application.status }}</span><small class="cell-secondary">{{ application.containerName || 'Container unavailable' }}</small></td>
          <td><strong class="health-label" :class="application.healthStatus.toLowerCase()">{{ application.healthStatus }}</strong><small class="cell-secondary">{{ application.healthMessage || 'Awaiting first probe' }}</small></td>
          <td><strong class="cell-primary">{{ application.currentVersion || 'Unversioned' }}</strong><small class="cell-secondary app-image">{{ application.dockerImage || 'Image unavailable' }}</small></td>
          <td><strong class="cell-primary">{{ application.serverName }}</strong><small class="cell-secondary">DOCKER</small></td>
          <td><strong class="cell-primary">{{ formatTime(application.healthCheckedAt) }}</strong><small class="cell-secondary">Every 30 seconds</small></td>
        </tr>
      </tbody></table></div>
    </article>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="dialogOpen = false">
      <section class="server-dialog application-dialog" role="dialog" aria-modal="true" aria-labelledby="application-dialog-title">
        <header><div><span>SERVICE CATALOG · DOCKER</span><h2 id="application-dialog-title">Register an application</h2></div><button aria-label="Close" @click="dialogOpen = false">×</button></header>
        <div class="dialog-body application-form">
          <p>An application stays stable even when its underlying Docker container changes.</p>
          <div class="form-grid"><label><span>Name</span><input v-model.trim="form.name" maxlength="120" placeholder="EasyBBS" /></label><label><span>Code</span><input v-model.trim="form.code" maxlength="64" pattern="[a-z][a-z0-9-]+" placeholder="easybbs" /></label></div>
          <div class="form-grid"><label><span>Environment</span><select v-model="form.environment"><option v-for="environment in ['DEV','TEST','STAGING','PRODUCTION']" :key="environment" :value="environment">{{ environment }}</option></select></label><label><span>Server</span><select v-model="form.serverId" @change="loadContainers"><option disabled value="">Select a server</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label></div>
          <label><span>Docker container</span><select v-model="form.containerSnapshotId"><option disabled value="">Select a discovered container</option><option v-for="container in containers" :key="container.id" :value="container.id">{{ container.name }} · {{ container.image }} · {{ container.state }}</option></select></label>
          <div class="form-grid"><label><span>Current version</span><input v-model.trim="form.currentVersion" maxlength="120" placeholder="v1.2.3" /></label><label><span>Access URL</span><input v-model.trim="form.accessUrl" maxlength="1000" placeholder="https://app.example.com" /></label></div>
          <label><span>Health check URL</span><input v-model.trim="form.healthCheckUrl" maxlength="1000" placeholder="http://127.0.0.1:9090/actuator/health" /><small>The Agent on the selected server performs this HTTP(S) check.</small></label>
          <label><span>Description</span><textarea v-model.trim="form.description" maxlength="1000" rows="3" placeholder="What this service owns and who depends on it." /></label>
          <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
        </div>
        <footer><button @click="dialogOpen = false">Cancel</button><button class="dialog-primary" :disabled="saving || !form.name || !form.code || !form.serverId || !form.containerSnapshotId" @click="createApplication">{{ saving ? 'Registering…' : 'Register application' }} <b>→</b></button></footer>
      </section>
    </div>
  </section>
</template>
