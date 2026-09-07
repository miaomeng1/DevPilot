<script setup lang="ts">
import { onMounted, ref } from 'vue'
import axios from 'axios'
import { serverApi, type ServerNode, type CreateServerResult } from '@/api/servers'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { beginRenewal, readRenewal, finishRenewal, type PendingRenewal } from '@/utils/agentTokenRenewal'

const props = defineProps<{ server: ServerNode }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore()
const pending = ref<PendingRenewal | null>(null)
const revision = ref('')
const confirmed = ref(false)
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const abandonConfirmed = ref(false)
const result = ref<CreateServerResult | null>(null)
const revealed = ref(false)
const copied = ref(false)

async function load() {
  busy.value = true
  error.value = ''
  revision.value = ''
  try {
    pending.value = readRenewal(auth.user?.id, props.server.id)
    revision.value = pending.value?.expectedRevision ?? (await serverApi.registration(props.server.id)).revision
  } catch (cause) { error.value = apiErrorMessage(cause, '无法读取签发状态，请重试') }
  finally { busy.value = false }
}
async function submit() {
  if (busy.value || !confirmed.value || !revision.value) return
  busy.value = true
  error.value = ''
  conflict.value = false
  try {
    pending.value = beginRenewal(auth.user?.id, props.server.id, revision.value)
    result.value = await serverApi.renewToken(props.server.id, { ...pending.value, confirmed: true })
  } catch (cause) {
    error.value = apiErrorMessage(cause, '签发响应未确认，请恢复同一次请求，不要重复轮换')
    conflict.value = axios.isAxiosError(cause) && cause.response?.status === 409 && cause.response.data?.code === 40977
  } finally { busy.value = false }
}
async function reconsider() {
  if (busy.value || !abandonConfirmed.value || !conflict.value || !pending.value) return
  try {
    finishRenewal(auth.user?.id, props.server.id, pending.value.requestId)
    pending.value = null
    revision.value = ''
    confirmed.value = false
    abandonConfirmed.value = false
    conflict.value = false
    await load()
  } catch (cause) { error.value = apiErrorMessage(cause, '无法结束旧请求') }
}
function close() {
  if (busy.value) return
  try {
    if (result.value && pending.value) finishRenewal(auth.user?.id, props.server.id, pending.value.requestId)
    result.value = null
    emit('close')
  } catch (cause) { error.value = apiErrorMessage(cause, '无法清除恢复标识，请重试') }
}
async function copyToken() {
  try { await navigator.clipboard.writeText(result.value!.agentToken); copied.value = true }
  catch { error.value = '复制失败，请展开后手动复制并妥善保管' }
}
onMounted(load)
</script>

<template>
  <div class="modal-backdrop" @click.self="close">
    <section class="server-dialog" role="dialog" aria-modal="true" aria-labelledby="renewal-title">
      <header><div><span>AGENT CREDENTIAL</span><h2 id="renewal-title">重新签发 Token</h2></div><button type="button" :disabled="busy" aria-label="关闭" @click="close">×</button></header>
      <div class="dialog-body renewal-body">
        <p><strong>{{ server.name }}</strong> · Server ID {{ server.id }}</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <template v-if="!result">
          <p>旧 Token 将立即撤销。请在目标机更新 Agent 配置并重启 Agent，否则它将无法继续上报。不会删除服务器、应用或重启业务容器。</p>
          <p v-if="pending">发现未完成的签发请求，将恢复同一次结果。不会因刷新页面再次轮换。</p>
          <label class="renewal-consent"><input v-model="confirmed" type="checkbox" :disabled="busy" /><span>我确认目标服务器，并了解旧 Token 失效后需要更新 Agent 配置。</span></label>
          <template v-if="conflict">
            <label class="renewal-consent"><input v-model="abandonConfirmed" type="checkbox" /><span>我已核对服务器，放弃旧结果恢复，重新读取当前版本；之后仍需再次确认签发。</span></label>
            <button type="button" :disabled="busy || !abandonConfirmed" @click="reconsider">结束旧请求，重新核对</button>
          </template>
          <button v-if="!revision" type="button" :disabled="busy" @click="load">重新读取状态</button>
        </template>
        <template v-else>
          <p>新 Token 已签发，旧 Token 已撤销。请先妥善保存，再关闭窗口；关闭后结束本次结果恢复。</p>
          <div class="copy-field"><code>{{ revealed ? result.agentToken : '••••••••••••••••••••••••' }}</code><button type="button" @click="revealed = !revealed">{{ revealed ? '隐藏' : '显示' }}</button><button type="button" @click="copyToken">{{ copied ? '已复制' : '复制 Token' }}</button></div>
          <p>已有 Agent：只替换受保护配置文件中的 <code>agent.token</code>，保留其他配置，然后重启 Agent 并检查心跳。</p>
          <details><summary>尚未安装 Agent？查看安装命令</summary><p>注意：安装脚本会重写 Agent 配置，不适合直接覆盖已有自定义配置。</p><pre>{{ result.installCommand }}</pre></details>
          <p>本次结果加密暂存 24 小时；浏览器只保存恢复标识，不保存 Token。签发成功不代表 Agent 已恢复在线。</p>
        </template>
      </div>
      <footer><button type="button" :disabled="busy" @click="close">{{ result ? '已保存，关闭' : '暂时关闭' }}</button><button v-if="!result" class="dialog-primary" type="button" :disabled="busy || !confirmed || !revision || conflict" @click="submit">{{ busy ? '处理中…' : pending ? '恢复同一次签发' : '确认撤销并签发' }}</button></footer>
    </section>
  </div>
</template>

<style scoped>
.renewal-body { display: grid; gap: 16px; overflow-wrap: anywhere; }
.renewal-consent { display: flex; align-items: flex-start; gap: 10px; }
.renewal-consent input { width: auto; flex: 0 0 auto; margin-top: 4px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
.copy-field { flex-wrap: wrap; }
</style>
