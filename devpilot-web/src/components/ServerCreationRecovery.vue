<script setup lang="ts">
import { ref } from 'vue'
import { serverApi, type ServerCreationState } from '@/api/servers'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { completeServerCreation, type PendingServerCreation } from '@/utils/serverCreationRequest'
const props = defineProps<{ pending: PendingServerCreation; disabled?: boolean }>()
const emit = defineEmits<{ finished: [] }>()
const auth = useAuthStore()
const state = ref<ServerCreationState | null>(null)
const busy = ref(false)
const confirmed = ref(false)
const error = ref('')
const labels = { AVAILABLE: '创建结果仍可恢复', EXPIRED: '凭据恢复窗口已过期', UNAVAILABLE: '原凭据已清除或被替换', DELETED: '原服务器已删除' }
async function inspect() {
  if (busy.value || props.disabled) return
  busy.value = true
  state.value = null
  confirmed.value = false
  error.value = ''
  try { state.value = await serverApi.creationState(props.pending.requestId) }
  catch (cause) { error.value = apiErrorMessage(cause, '无法核对结果，请保留原请求重试') }
  finally { busy.value = false }
}
function finish() {
  if (!state.value || !confirmed.value || busy.value || props.disabled) return
  try {
    completeServerCreation(auth.user?.id, props.pending.requestId)
    emit('finished')
  } catch (cause) { error.value = apiErrorMessage(cause, '无法结束旧请求') }
}
</script>

<template>
  <aside class="creation-recovery">
    <button type="button" :disabled="busy || disabled" @click="inspect">核对原服务器（只读）</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <p><strong>{{ state.name }}</strong> · Server ID {{ state.serverId }} · {{ labels[state.status] }}</p>
      <p v-if="state.status !== 'DELETED'">这是已创建的服务器，不需要重建。需要新 Token 时，请到服务器列表核对相同 ID 并重新签发。</p>
      <p v-else>旧请求对应的服务器已删除，重试不会自动重建；结束旧请求也不会恢复该服务器。</p>
      <RouterLink to="/servers">查看服务器列表 →</RouterLink>
      <label><input v-model="confirmed" type="checkbox" :disabled="disabled" /><span>我已核对上述记录，确认结束旧创建请求。此操作只清除浏览器恢复标识，不删除服务器、不签发凭据；以后添加表示创建另一台服务器。</span></label>
      <button type="button" :disabled="!confirmed || busy || disabled" @click="finish">结束旧创建请求</button>
    </template>
  </aside>
</template>

<style scoped>
.creation-recovery { border: 1px solid #d9e2ef; border-radius: 10px; padding: 12px; display: grid; gap: 10px; overflow-wrap: anywhere; }
.creation-recovery p { margin: 0; }
.creation-recovery label { display: flex; gap: 8px; align-items: flex-start; }
.creation-recovery input { width: auto; flex: 0 0 auto; margin-top: 4px; }
</style>
