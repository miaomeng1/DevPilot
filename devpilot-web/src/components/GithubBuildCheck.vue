<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { apiClient, type ApiResponse } from '@/api/client'
import type { PipelineRun } from '@/api/cicd'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ applicationId: string; runs: PipelineRun[]; repositoryProvider?: string }>()
const auth = useAuthStore()
const allowed = computed(() => auth.hasAnyRole(['ADMIN', 'DEVELOPER']) && props.repositoryProvider === 'GITHUB')
const builds = computed(() => props.runs.filter(run => /^build:github-[1-9]\d*-[1-9]\d*$/.test(run.externalRunId)))
interface Result { buildId: string; state: string; message: string; retryAfterSeconds: number; checkedAt: string }
const expanded = ref(false), selected = ref(''), token = ref(''), busy = ref(false), error = ref('')
const result = ref<Result | null>(null)
const recoveryIdentity = computed(() => {
  if (!result.value || result.value.buildId !== selected.value
    || !['FAILED', 'CANCELLED', 'SUCCESS_AWAITING_BUILD_EVIDENCE'].includes(result.value.state)) return null
  const run = builds.value.find(value => value.id === selected.value)
  const parts = run?.externalRunId.match(/^build:github-([1-9]\d*)-([1-9]\d*)$/)
  return parts ? { runId: parts[1], attempt: parts[2] } : null
})
let generation = 0
let pending: AbortController | null = null
function clear() {
  generation++; pending?.abort(); pending = null
  token.value = ''; result.value = null; error.value = ''; busy.value = false
}
watch(() => props.applicationId, () => { clear(); selected.value = ''; expanded.value = false })
watch(() => auth.user?.id, () => { clear(); selected.value = ''; expanded.value = false })
watch(allowed, value => { if (!value) { clear(); expanded.value = false } })
watch(selected, clear)
onBeforeUnmount(clear)
function toggle() { expanded.value = !expanded.value; if (!expanded.value) clear() }
async function check() {
  if (busy.value || !allowed.value || !builds.value.some(run => run.id === selected.value)) return
  if (!/^[A-Za-z0-9_]{10,512}$/.test(token.value)) { error.value = '请输入有效的 GitHub 查询凭据'; return }
  const current = ++generation
  const applicationId = props.applicationId, buildId = selected.value
  pending = new AbortController()
  busy.value = true; error.value = ''; result.value = null
  try {
    // Intentionally not stored in local/session storage or retained in a saved form.
    const request = apiClient.post<ApiResponse<Result>>(`/cicd/applications/${applicationId}/builds/${buildId}/github-check`,
      { repositoryToken: token.value }, { signal: pending.signal, timeout: 25000 })
    token.value = ''
    const response = await request
    if (generation === current) {
      if (String(response.data.data.buildId) !== buildId) throw Error('Mismatched build response')
      result.value = response.data.data
    }
  } catch {
    if (generation === current) error.value = '核对请求失败；本地状态未更改。请检查连接或重新登录后再试。'
  } finally {
    if (generation === current) { busy.value = false; pending = null }
  }
}
</script>

<template>
  <section v-if="allowed && builds.length" class="github-check">
    <button type="button" class="check-toggle" :aria-expanded="expanded" @click="toggle">
      {{ expanded ? '收起核对' : '核对 GitHub 构建 · Read-only check' }}
    </button>
    <form v-if="expanded" @submit.prevent="check">
      <p>用于回调丢失或状态过期时查看指定运行的真实结果。不会发布、重跑或修改本地流水线状态。</p>
      <label>构建记录
        <select v-model="selected"><option value="" disabled>选择本次构建</option>
          <option v-for="run in builds" :key="run.id" :value="run.id">{{ run.externalRunId }} · {{ run.commitSha.slice(0, 12) }}</option>
        </select>
      </label>
      <label>GitHub Token（本仓库 Actions: read）
        <input v-model="token" type="password" autocomplete="off" :disabled="busy" maxlength="512" placeholder="仅用于本次请求，不保存" />
      </label>
      <small>输入将在发送、关闭或切换应用后清空。不要使用镜像仓库或部署平台密钥。</small>
      <button type="submit" :disabled="busy || !selected || !token">{{ busy ? '正在核对…' : '只读核对 Check' }}</button>
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="result" class="check-result" role="status">
        <strong>{{ result.message }}</strong>
        <small>{{ result.state }} · 核对时间 {{ result.checkedAt }}</small>
        <small v-if="result.retryAfterSeconds">建议 {{ result.retryAfterSeconds }} 秒后重试。</small>
        <small>本次结果不等同于上线成功，生产发布仍需构建证据及人工确认。</small>
        <details v-if="recoveryIdentity">
          <summary>回调丢失时如何恢复 · Recover build</summary>
          <p>先检查 GitHub 的 quality、security、image 是否全部成功，并修复回调连接。新版 workflow 可在 Run workflow 选择 operation=recover_build，build_run_id 填 {{ recoveryIdentity.runId }}；无需填写发布确认 ID。</p>
          <small>当前记录为 attempt {{ recoveryIdentity.attempt }}。恢复入口会核对该 Run 当前 attempt，若已重跑，可能补报的是新 attempt；请核对恢复后的记录。旧模板或过期制品不支持此操作，不能跳过失败的测试或扫描。</small>
          <small>本面板不会执行恢复。恢复任务只补报构建，不部署；成功后仍需在 DevPilot 人工确认，再选择 operation=release 发布。</small>
        </details>
      </div>
    </form>
  </section>
</template>

<style scoped>
.github-check{margin:16px 0;padding:16px;border:1px solid var(--border-color,#334155);border-radius:12px}
.check-toggle{background:transparent;border:0;color:inherit;cursor:pointer;font:inherit;text-align:left}
form{display:grid;gap:12px;margin-top:12px;max-width:760px}p{margin:0;line-height:1.6}label{display:grid;gap:6px}
select,input{width:100%;min-width:0;box-sizing:border-box;padding:10px;border:1px solid #64748b;border-radius:6px;background:var(--bg-color,#0f172a);color:inherit}
small{display:block;line-height:1.6;opacity:.8}button[type=submit]{justify-self:start;padding:9px 14px;border-radius:6px;border:1px solid #64748b;background:transparent;color:inherit;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}.check-result{display:grid;gap:6px;padding:12px;border-left:3px solid #60a5fa}
</style>
