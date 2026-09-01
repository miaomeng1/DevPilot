<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { applicationApi, type Application, type ApplicationPayload, type DeploymentRecord } from '@/api/applications'
import { dockerApi, type DockerContainer } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const servers = useServerStore()
const application = ref<Application>()
const deployments = ref<DeploymentRecord[]>([])
const containers = ref<DockerContainer[]>([])
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const editOpen = ref(false)
const releaseOpen = ref(false)
const deleteOpen = ref(false)
const deleteConfirmation = ref('')
let pollTimer: number | undefined

const form = reactive<ApplicationPayload>({ name: '', code: '', description: '', environment: 'DEV', serverId: '', containerSnapshotId: '', currentVersion: '', healthCheckUrl: '', accessUrl: '' })
const release = reactive<{ version: string, dockerImage: string, result: 'SUCCESS' | 'FAILED', logs: string }>({ version: '', dockerImage: '', result: 'SUCCESS', logs: '' })
const id = computed(() => String(route.params.id))
const canManage = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const canDelete = computed(() => auth.hasAnyRole(['ADMIN']))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const [detail, history] = await Promise.all([applicationApi.get(id.value), applicationApi.deployments(id.value)])
    application.value = detail
    deployments.value = history
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Application could not be loaded')
  } finally {
    loading.value = false
  }
}

async function loadContainers() {
  containers.value = form.serverId ? await dockerApi.list(form.serverId) : []
  if (!containers.value.some((container) => container.id === form.containerSnapshotId)) form.containerSnapshotId = ''
}

async function openEdit() {
  const current = application.value
  if (!current) return
  Object.assign(form, {
    name: current.name, code: current.code, description: current.description || '', environment: current.environment,
    serverId: current.serverId, containerSnapshotId: current.containerSnapshotId || '', currentVersion: current.currentVersion || '',
    healthCheckUrl: current.healthCheckUrl || '', accessUrl: current.accessUrl || '',
  })
  await loadContainers()
  editOpen.value = true
}

async function saveApplication() {
  saving.value = true
  errorMessage.value = ''
  try {
    application.value = await applicationApi.update(id.value, { ...form })
    editOpen.value = false
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Application changes could not be saved')
  } finally {
    saving.value = false
  }
}

function openRelease() {
  release.version = application.value?.currentVersion || ''
  release.dockerImage = application.value?.dockerImage || ''
  release.result = 'SUCCESS'
  release.logs = ''
  releaseOpen.value = true
}

async function recordRelease() {
  saving.value = true
  errorMessage.value = ''
  try {
    await applicationApi.recordDeployment(id.value, { ...release })
    releaseOpen.value = false
    await load(true)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Release record could not be saved')
  } finally {
    saving.value = false
  }
}

async function removeApplication() {
  if (deleteConfirmation.value !== application.value?.code) return
  saving.value = true
  try {
    await applicationApi.remove(id.value)
    await router.replace('/applications')
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Application could not be deleted')
    deleteOpen.value = false
  } finally {
    saving.value = false
  }
}

function bytes(value: string | null) {
  if (!value) return '—'
  let amount = Number(value)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1 }
  return `${amount.toFixed(index ? 1 : 0)} ${units[index]}`
}

function formatTime(value: string | null) {
  if (!value) return 'Never'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function statusClass(value: string) {
  if (value === 'RUNNING' || value === 'HEALTHY' || value === 'SUCCESS') return 'online'
  if (value === 'WARNING' || value === 'UNKNOWN') return 'warning'
  return 'offline'
}

onMounted(async () => {
  await servers.load()
  await load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="application-detail-view">
    <div v-if="loading && !application" class="table-empty"><span class="loading-ring" /><strong>Loading application</strong></div>
    <template v-else-if="application">
      <RouterLink class="back-link" to="/applications">← Application catalog</RouterLink>
      <header class="page-heading application-detail-heading">
        <div><p class="eyebrow">{{ application.environment }} · {{ application.code }}</p><h1>{{ application.name }}</h1><span>{{ application.description || application.dockerImage || 'Docker application' }}</span></div>
        <div class="application-actions"><span class="status-badge" :class="statusClass(application.status)"><i />{{ application.status }}</span><button v-if="canManage" @click="openRelease">＋ Record release</button><button v-if="canManage" @click="openEdit">Edit</button><button v-if="canDelete" class="danger-action" @click="deleteConfirmation = ''; deleteOpen = true">Delete</button></div>
      </header>
      <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

      <div class="application-kpis">
        <article><span>Health</span><strong :class="`health-${application.healthStatus.toLowerCase()}`">{{ application.healthStatus }}</strong><small>{{ application.healthMessage || 'Awaiting Agent probe' }}</small></article>
        <article><span>Current version</span><strong>{{ application.currentVersion || 'Unversioned' }}</strong><small>{{ application.dockerImage || 'Image unavailable' }}</small></article>
        <article><span>CPU usage</span><strong>{{ application.cpuUsage === null ? '—' : `${application.cpuUsage.toFixed(1)}%` }}</strong><small>Current container sample</small></article>
        <article><span>Memory</span><strong>{{ bytes(application.memoryUsage) }}</strong><small>Limit {{ bytes(application.memoryLimit) }}</small></article>
      </div>

      <div class="application-detail-grid">
        <article class="detail-panel application-facts"><header><div><strong>Service identity</strong><small>Stable application metadata and runtime binding</small></div><span>DOCKER</span></header><dl>
          <div><dt>Server</dt><dd><RouterLink :to="`/servers/${application.serverId}`">{{ application.serverName }}</RouterLink></dd></div>
          <div><dt>Container</dt><dd><RouterLink v-if="application.containerSnapshotId" :to="`/docker/containers/${application.containerSnapshotId}`">{{ application.containerName }}</RouterLink><span v-else>Unavailable</span></dd></div>
          <div><dt>Environment</dt><dd>{{ application.environment }}</dd></div><div><dt>Deploy type</dt><dd>{{ application.deployType }}</dd></div>
          <div class="wide"><dt>Access URL</dt><dd><a v-if="application.accessUrl" :href="application.accessUrl" target="_blank" rel="noopener">{{ application.accessUrl }} ↗</a><span v-else>Not configured</span></dd></div>
          <div class="wide"><dt>Health check</dt><dd>{{ application.healthCheckUrl || 'Not configured' }}</dd></div>
          <div><dt>Last health check</dt><dd>{{ formatTime(application.healthCheckedAt) }}</dd></div><div><dt>Last release</dt><dd>{{ formatTime(application.lastDeployedAt) }}</dd></div>
        </dl></article>

        <article class="detail-panel release-panel"><header><div><strong>Release history</strong><small>Version, image, operator, result, and retained notes</small></div><span>{{ deployments.length }} records</span></header>
          <div v-if="!deployments.length" class="release-empty"><span>⇧</span><strong>No release records</strong><small>Record completed external deployments here; deployment automation belongs to the V2 roadmap.</small></div>
          <ol v-else class="release-list"><li v-for="deployment in deployments" :key="deployment.id"><span class="release-dot" :class="deployment.result.toLowerCase()" /><div class="release-main"><header><strong>{{ deployment.version }}</strong><span class="status-badge" :class="statusClass(deployment.result)"><i />{{ deployment.result }}</span></header><code>{{ deployment.dockerImage }}</code><p v-if="deployment.logs">{{ deployment.logs }}</p><small>{{ deployment.operatorName }} · {{ deployment.serverName }} · {{ formatTime(deployment.deployedAt) }}</small></div></li></ol>
        </article>
      </div>
    </template>

    <div v-if="editOpen" class="modal-backdrop" @click.self="editOpen = false"><section class="server-dialog application-dialog" role="dialog" aria-modal="true"><header><div><span>APPLICATION · {{ application?.code }}</span><h2>Edit application</h2></div><button aria-label="Close" @click="editOpen = false">×</button></header><div class="dialog-body application-form"><div class="form-grid"><label><span>Name</span><input v-model.trim="form.name" /></label><label><span>Environment</span><select v-model="form.environment"><option v-for="environment in ['DEV','TEST','STAGING','PRODUCTION']" :key="environment">{{ environment }}</option></select></label></div><div class="form-grid"><label><span>Server</span><select v-model="form.serverId" @change="loadContainers"><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label><label><span>Container</span><select v-model="form.containerSnapshotId"><option v-for="container in containers" :key="container.id" :value="container.id">{{ container.name }} · {{ container.image }}</option></select></label></div><div class="form-grid"><label><span>Current version</span><input v-model.trim="form.currentVersion" /></label><label><span>Access URL</span><input v-model.trim="form.accessUrl" /></label></div><label><span>Health check URL</span><input v-model.trim="form.healthCheckUrl" /><small>HTTP(S), requested from the Agent host every 30 seconds.</small></label><label><span>Description</span><textarea v-model.trim="form.description" rows="3" /></label><p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p></div><footer><button @click="editOpen = false">Cancel</button><button class="dialog-primary" :disabled="saving || !form.name || !form.serverId || !form.containerSnapshotId" @click="saveApplication">{{ saving ? 'Saving…' : 'Save changes' }}</button></footer></section></div>

    <div v-if="releaseOpen" class="modal-backdrop" @click.self="releaseOpen = false"><section class="server-dialog release-dialog" role="dialog" aria-modal="true"><header><div><span>RELEASE HISTORY · MANUAL RECORD</span><h2>Record a completed release</h2></div><button aria-label="Close" @click="releaseOpen = false">×</button></header><div class="dialog-body application-form"><p>This records an external release; it does not execute a deployment.</p><div class="form-grid"><label><span>Version</span><input v-model.trim="release.version" placeholder="v1.2.4" /></label><label><span>Result</span><select v-model="release.result"><option value="SUCCESS">SUCCESS</option><option value="FAILED">FAILED</option></select></label></div><label><span>Docker image</span><input v-model.trim="release.dockerImage" placeholder="example/api:v1.2.4" /></label><label><span>Release notes / logs</span><textarea v-model.trim="release.logs" maxlength="10000" rows="5" placeholder="Build URL, digest, migration notes, or failure details." /></label><p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p></div><footer><button @click="releaseOpen = false">Cancel</button><button class="dialog-primary" :disabled="saving || !release.version || !release.dockerImage" @click="recordRelease">{{ saving ? 'Recording…' : 'Record release' }}</button></footer></section></div>

    <div v-if="deleteOpen" class="modal-backdrop" @click.self="deleteOpen = false"><section class="server-dialog remove-dialog" role="dialog" aria-modal="true"><header><div><span>DESTRUCTIVE ACTION</span><h2>Delete {{ application?.name }}</h2></div><button aria-label="Close" @click="deleteOpen = false">×</button></header><div class="dialog-body"><p>This removes the application and its release history. It does not remove the Docker container.</p><label><span>Type <code>{{ application?.code }}</code> to confirm</span><input v-model.trim="deleteConfirmation" autofocus /></label></div><footer><button @click="deleteOpen = false">Cancel</button><button class="danger-confirm" :disabled="saving || deleteConfirmation !== application?.code" @click="removeApplication">Delete application</button></footer></section></div>
  </section>
</template>
