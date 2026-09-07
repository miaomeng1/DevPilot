<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { apiClient, type ApiResponse } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
const props = defineProps<{ applicationId: string; repositoryProvider?: string }>()
const auth = useAuthStore()
const allowed = computed(() => auth.hasAnyRole(['ADMIN']) && props.repositoryProvider === 'GITHUB')
interface Status { revision: string; state: string; credentialConfigured: boolean; expiresAt: string | null; nextCheckAt?: string | null }
const expanded = ref(false), busy = ref(false), token = ref(''), consent = ref(false), days = ref(7)
const status = ref<Status | null>(null), error = ref(''), notice = ref('')
let generation = 0
const labels: Record<string, string> = { DISABLED: '未启用 Disabled', EXPIRED: '凭据已过期 Expired',
  CONFIGURATION_CHANGED: '仓库配置已变更，需重新确认', SAVED_UNVERIFIED: '已保存，凭据尚未验证 Saved' }
function clear() { generation++; token.value = ''; consent.value = false; status.value = null; busy.value = false; error.value = ''; notice.value = '' }
watch(() => [props.applicationId, auth.user?.id, allowed.value], () => { clear(); expanded.value = false })
onBeforeUnmount(clear)
const endpoint = () => `/cicd/applications/${props.applicationId}/github-observer`
async function load() {
  if (busy.value || !allowed.value) return
  const current = ++generation
  busy.value = true; error.value = ''; status.value = null
  try {
    const response = await apiClient.get<ApiResponse<Status>>(endpoint())
    if (generation === current) status.value = response.data.data
  } catch { if (generation === current) error.value = '无法读取配置。请刷新核对后再操作。' }
  finally { if (generation === current) busy.value = false }
}
function toggle() { expanded.value = !expanded.value; if (expanded.value) void load(); else clear() }
async function change(disable = false) {
  if (busy.value || !status.value || !allowed.value) return
  if (!disable && (!consent.value || !/^[A-Za-z0-9_]{10,512}$/.test(token.value))) { error.value = '请填写有效凭据，并明确同意加密保存。'; return }
  const current = ++generation
  const url = endpoint(), revision = status.value.revision
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const request = disable ? apiClient.delete<ApiResponse<Status>>(url, { params: { revision } })
      : apiClient.put<ApiResponse<Status>>(url, { revision, consent: true, repositoryToken: token.value,
        expiresAt: new Date(Date.now() + days.value * 86400000).toISOString() })
    token.value = ''; consent.value = false
    const response = await request
    if (generation === current) {
      status.value = response.data.data
      notice.value = disable ? '已关闭自动核对并删除平台保存的查询凭据。GitHub 上的 Token 需自行撤销。'
        : '配置已保存。后续核对结果显示在构建记录中；保存不代表授权有效或应用上线成功。'
    }
  } catch {
    if (generation === current) {
      status.value = null
      error.value = '操作结果未确认或配置已变化。请先刷新配置，不能直接重复提交。'
    }
  } finally { if (generation === current) busy.value = false }
}
</script>

<template>
  <section v-if="allowed" class="observer-settings">
    <button type="button" :aria-expanded="expanded" @click="toggle">{{ expanded ? '收起自动核对设置' : '自动核对设置 · GitHub observer' }}</button>
    <div v-if="expanded" class="settings-body">
      <p>授权后，平台定期读取当前仓库待定构建，核对失败或取消结果。不会重跑 Workflow、修改 GitHub 权限或自动发布。</p>
      <button type="button" :disabled="busy" @click="load">刷新配置</button>
      <p v-if="status" role="status">{{ labels[status.state] || '配置状态未知' }}<small v-if="status.expiresAt">有效期至 {{ status.expiresAt }}</small></p>
      <p v-if="status?.state === 'SAVED_UNVERIFIED'" class="schedule-hint">
        {{ status.nextCheckAt ? `最早再次核对时间：${status.nextCheckAt}` : '等待调度检查' }}
        <small>仅表示调度等待条件，不保证该时刻执行或授权有效；仅核对待定构建，凭据到期后停止。点击“刷新配置”更新此信息。</small>
      </p>
      <form @submit.prevent="change(false)">
        <label>查询凭据 · 本仓库 Actions: read<input v-model="token" type="password" autocomplete="off" maxlength="512" :disabled="busy || !status" /></label>
        <label>平台保存期限<select v-model.number="days" :disabled="busy"><option :value="1">1 天</option><option :value="7">7 天</option><option :value="30">30 天</option></select></label>
        <label class="consent"><input v-model="consent" type="checkbox" :disabled="busy" />我同意加密保存查询凭据并开启定期只读核对</label>
        <small>期限还受 GitHub Token 自身有效期限制。凭据不会回显；关闭后清除平台副本，既有备份可能仍包含加密副本。</small>
        <small>更新凭据不会立即查询，也不会清除已有查询间隔或限流等待。请等待后续核对，并在构建记录中查看结果；反复保存不会加快核对。</small>
        <div class="actions"><button type="submit" :disabled="busy || !status || !consent || !token">保存并开启核对</button>
          <button type="button" :disabled="busy || !status?.credentialConfigured" @click="change(true)">关闭并清除凭据</button></div>
      </form>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    </div>
  </section>
</template>

<style scoped>
.observer-settings{margin:16px 0;padding:16px;border:1px solid #64748b;border-radius:12px}.settings-body,form{display:grid;gap:12px;margin-top:12px;max-width:760px}p{margin:0;line-height:1.6}label{display:grid;gap:6px}input[type=password],select{min-width:0;padding:9px;border:1px solid #64748b;border-radius:6px;background:transparent;color:inherit}button{padding:8px 12px;border:1px solid #64748b;border-radius:6px;background:transparent;color:inherit;cursor:pointer;justify-self:start}button:disabled{opacity:.5;cursor:not-allowed}small{display:block;line-height:1.6;opacity:.8}.consent{display:flex;align-items:center}.actions{display:flex;gap:10px;flex-wrap:wrap}
</style>
