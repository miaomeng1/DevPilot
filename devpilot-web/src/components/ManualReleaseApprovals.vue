<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { apiClient, apiErrorMessage, type ApiResponse } from '@/api/client'
import type { PipelineRun } from '@/api/cicd'
import { useAuthStore } from '@/stores/auth'

interface ApprovalContext {
  applicationId: string; applicationName: string; environment: string; serverId: string
  buildRunId: string; buildExternalRunId: string; commitSha: string; imageUri: string; fingerprint: string
}
interface Approval extends Omit<ApprovalContext, 'fingerprint' | 'applicationName'> {
  id: string; approvedUsername: string; approvedAt: string; expiresAt: string
  revokedAt: string | null; consumedByRunId: string | null
}
interface Pending { requestId: string; context: ApprovalContext }
const props = defineProps<{ applicationId: string; runs: PipelineRun[]; repositoryProvider?: string }>()
const auth = useAuthStore()
const canApprove = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']))
const builds = computed(() => props.runs.filter(run => run.externalRunId.startsWith('build:') && run.status === 'SUCCEEDED'
  && run.testStatus === 'PASSED' && run.securityStatus === 'PASSED' && /@sha256:[a-f0-9]{64}$/.test(run.imageUri || '')))
const selected = ref(''), context = ref<ApprovalContext | null>(null), pending = ref<Pending | null>(null)
const records = ref<Approval[]>([]), busy = ref(false), confirmed = ref(false), error = ref('')
const conflict = ref(false), discardConfirmed = ref(false), notice = ref('')
const endpoint = `/cicd/applications/${props.applicationId}`
function storageKey() {
  if (!auth.user?.id || !selected.value) throw Error('请先登录并选择构建')
  return `devpilot.release-approval.${auth.user.id}.${props.applicationId}.${selected.value}`
}
function readPending(): Pending | null {
  const raw = localStorage.getItem(storageKey())
  if (!raw) return null
  const value = JSON.parse(raw)
  if (!value || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.requestId || '')
      || value.context?.applicationId !== props.applicationId || value.context?.buildRunId !== selected.value
      || !/^[a-f0-9]{64}$/.test(value.context?.fingerprint || '')
      || typeof value.context?.commitSha !== 'string' || typeof value.context?.imageUri !== 'string') {
    throw Error('恢复标识无效，请先核对确认记录，不能自动重复确认')
  }
  return value
}
async function loadRecords() {
  try { records.value = (await apiClient.get<ApiResponse<Approval[]>>(`${endpoint}/release-approvals`)).data.data }
  catch (cause) { error.value = apiErrorMessage(cause, '无法读取确认记录') }
}
async function inspect() {
  if (busy.value || !selected.value) return
  busy.value = true; error.value = ''; confirmed.value = false; context.value = null; notice.value = ''
  try {
    pending.value = readPending()
    context.value = pending.value?.context ?? (await apiClient.get<ApiResponse<ApprovalContext>>(`${endpoint}/builds/${selected.value}/approval-context`)).data.data
  } catch (cause) { error.value = apiErrorMessage(cause, '无法读取确认目标') }
  finally { busy.value = false }
}
async function approve() {
  if (busy.value || !confirmed.value || !context.value) return
  busy.value = true; error.value = ''; conflict.value = false
  try {
    pending.value = readPending() ?? { requestId: crypto.randomUUID(), context: context.value }
    localStorage.setItem(storageKey(), JSON.stringify(pending.value))
    const snapshot = pending.value.context
    const result = (await apiClient.post<ApiResponse<Approval>>(`${endpoint}/builds/${selected.value}/approval`, {
      requestId: pending.value.requestId, commitSha: snapshot.commitSha, imageUri: snapshot.imageUri,
      expectedFingerprint: snapshot.fingerprint, confirmed: true,
    })).data.data
    if (readPending()?.requestId === pending.value.requestId) localStorage.removeItem(storageKey())
    pending.value = null; context.value = null; confirmed.value = false
    notice.value = `已记录确认 ${result.id}。这不是部署成功；请核对下方记录的当前有效性。`
    await loadRecords()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '确认结果未收到，请保留原请求并重试')
    conflict.value = axios.isAxiosError(cause) && cause.response?.status === 409
  } finally { busy.value = false }
}
async function reread() {
  if (busy.value || !conflict.value || !discardConfirmed.value) return
  try {
    if (pending.value && readPending()?.requestId === pending.value.requestId) localStorage.removeItem(storageKey())
    pending.value = null; conflict.value = false; discardConfirmed.value = false
    await inspect()
  } catch (cause) { error.value = apiErrorMessage(cause, '无法结束旧确认请求') }
}
async function revoke(record: Approval) {
  if (busy.value || !window.confirm(`撤销确认 ${record.id}？这不会取消已经开始的部署。`)) return
  busy.value = true
  try {
    await apiClient.delete(`${endpoint}/release-approvals/${record.id}`)
    notice.value = `确认 ${record.id} 已撤销，不能再用于发起发布。`
    await loadRecords()
  }
  catch (cause) { error.value = apiErrorMessage(cause, '撤销失败') }
  finally { busy.value = false }
}
function state(record: Approval) {
  if (record.revokedAt) return '已撤销'
  if (record.consumedByRunId) return `已使用 · ${record.consumedByRunId}`
  return Date.parse(record.expiresAt + 'Z') <= Date.now() ? '已过期' : '待使用（发布时复核配置）'
}
async function copy(value: string) {
  try { await navigator.clipboard.writeText(value); notice.value = '确认 ID 已复制' }
  catch { error.value = '复制失败，请手动复制确认 ID' }
}
onMounted(loadRecords)
// Refresh confirmation consumption when polling observes a release transition.
// Do not watch timestamps: unchanged CI polls must not generate extra requests.
watch(() => props.runs.map(run => `${run.id}:${run.manualApprovalId || ''}:${run.deployStatus}`).join('|'), loadRecords)
</script>

<template>
  <section class="manual-approvals">
    <header><h2>人工确认 · Release approval</h2><button type="button" :disabled="busy" @click="loadRecords">刷新记录</button></header>
    <p>确认绑定具体 commit、digest 和目标配置，24 小时内有效。构建不会因此重新执行；确认本身不部署。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="canApprove">
      <label>选择成功构建<select v-model="selected" :disabled="busy" @change="context = null; pending = null; confirmed = false; conflict = false; discardConfirmed = false"><option value="">请选择</option><option v-for="build in builds" :key="build.id" :value="build.id">{{ build.externalRunId }} · {{ build.commitSha.slice(0, 12) }}</option></select></label>
      <button type="button" :disabled="busy || !selected" @click="inspect">核对发布目标</button>
      <div v-if="context" class="approval-context">
        <p>{{ context.applicationName }} · {{ context.environment }} · Server {{ context.serverId }}</p>
        <code>{{ context.commitSha }}</code><code>{{ context.imageUri }}</code>
        <p v-if="pending">发现未完成的确认请求；重试复用原快照和请求标识，不改写确认时间。</p>
        <label class="consent"><input v-model="confirmed" type="checkbox" :disabled="busy" /><span>我已核对目标、commit 和完整镜像 digest，确认允许发布此版本。</span></label>
        <button type="button" :disabled="busy || !confirmed || conflict" @click="approve">{{ pending ? '恢复同一次确认' : '确认此版本' }}</button>
        <template v-if="conflict"><label class="consent"><input v-model="discardConfirmed" type="checkbox" /><span>我已核对记录，结束旧请求并重新读取目标；之后需要再次确认。</span></label><button type="button" :disabled="busy || !discardConfirmed" @click="reread">重新读取目标</button></template>
      </div>
    </template>
    <p v-if="!records.length">暂无人工确认记录。旧 CI 发起人/任务开始时间不等同于这里的确认记录。</p>
    <article v-for="record in records" :key="record.id">
      <strong>{{ state(record) }}</strong><p>{{ record.approvedUsername }} · {{ record.approvedAt }} UTC</p>
      <p>{{ record.environment }} · Server {{ record.serverId }} · {{ record.buildExternalRunId }}</p>
      <code>{{ record.imageUri }}</code><code>确认 ID：{{ record.id }}</code>
      <p v-for="run in runs.filter(item => item.manualApprovalId === record.id)" :key="run.id">
        引用此确认的发布：{{ run.externalRunId }} · {{ run.deployStatus }}。
        {{ record.consumedByRunId === run.externalRunId ? '已通过确认消费校验；上线结果以部署和健康验证为准。' : '仅收到确认引用，不代表已经通过发布校验或完成部署。' }}
      </p>
      <template v-if="!record.revokedAt && !record.consumedByRunId">
        <p v-if="repositoryProvider === 'GITHUB'">GitHub Run workflow：选择 operation=release，build_run_id 填来源构建的数字 Run ID，manual_approval_id 填此确认 ID。recover_build 仅补报构建记录，不会发布。</p>
        <p v-else-if="repositoryProvider === 'GITLAB'">GitLab：打开来源构建所在流水线的 production 手动作业，填写变量 DEVPILOT_MANUAL_APPROVAL_ID 为此确认 ID。不要启动新流水线或重建镜像。</p>
        <p v-else>请核对仓库流水线是否支持传递人工确认 ID；不能仅凭此记录判断已具备发布条件。</p>
        <p>每份确认只能用于一次发布，过期后需重新确认。</p>
      </template>
      <button type="button" @click="copy(record.id)">复制确认 ID</button>
      <button v-if="canApprove && !record.revokedAt && !record.consumedByRunId" type="button" :disabled="busy" @click="revoke(record)">撤销确认</button>
    </article>
  </section>
</template>

<style scoped>
.manual-approvals { margin-top: 16px; padding: 20px; border: 1px solid var(--line, #d9e2ef); border-radius: 14px; display: grid; gap: 12px; }
header { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
h2 { margin: 0; font-size: 16px; } p { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
label, .approval-context, article { display: grid; gap: 10px; }
article { padding: 14px 0; border-top: 1px solid var(--line, #d9e2ef); }
code { display: block; overflow-wrap: anywhere; font-size: 12px; }
button, select { padding: 8px 12px; border: 1px solid var(--line, #d9e2ef); border-radius: 8px; background: transparent; color: inherit; }
button { cursor: pointer; } button:disabled { opacity: .5; cursor: default; }
.consent { display: flex; align-items: flex-start; } .consent input { width: auto; margin-top: 4px; }
@media(max-width:600px) { header { align-items: flex-start; flex-direction: column; } }
</style>
