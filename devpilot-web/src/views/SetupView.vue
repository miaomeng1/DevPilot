<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { apiClient, apiErrorMessage, type ApiResponse } from '@/api/client'
import { useServerStore } from '@/stores/servers'
import { serverApi } from '@/api/servers'
import { useAuthStore } from '@/stores/auth'
import { beginServerCreation, completeServerCreation, readServerCreation, type PendingServerCreation } from '@/utils/serverCreationRequest'
import ServerCreationRecovery from '@/components/ServerCreationRecovery.vue'

interface SetupStatus {
  publicUrl: string; providerUrl: string | null; providerTokenConfigured: boolean; serverId: string | null
  publicUrlStatus: string; providerStatus: string; serverStatus: string; agentStatus: string
  providerVerifiedAt: string | null; lastHeartbeat: string | null; providerError: string | null; revision: string
}
const servers = useServerStore(), state = ref<SetupStatus | null>(null), busy = ref(false), error = ref('')
const form = reactive({ publicUrl: '', providerUrl: '', providerApiToken: '', serverId: '', revision: '' })
const serverName = ref(''), installCommand = ref('')
const auth = useAuthStore()
const pendingServer = ref<PendingServerCreation | null>(null)
function restorePendingServer() {
  try {
    const saved = readServerCreation(auth.user?.id)
    if (saved) {
      pendingServer.value = saved; serverName.value = saved.name
    }
  } catch { error.value = '无法读取创建重试标识，请先核对已有服务器。' }
}
async function createServer() {
  if (busy.value || !serverName.value.trim()) return
  busy.value = true; error.value = ''
  try {
    pendingServer.value = beginServerCreation(auth.user?.id, serverName.value)
    const created = await serverApi.create(pendingServer.value.name, pendingServer.value.requestId)
    servers.prepend(created.server); form.serverId = created.server.id
    installCommand.value = created.installCommand
    apply((await apiClient.put<ApiResponse<SetupStatus>>('/setup', { ...form, serverId: created.server.id })).data.data)
    completeServerCreation(auth.user?.id, pendingServer.value.requestId); pendingServer.value = null; serverName.value = ''
  } catch (cause) { error.value = apiErrorMessage(cause, '添加或保存选择失败，请先刷新服务器列表核对结果，勿重复创建') }
  finally { busy.value = false }
}
const labels: Record<string, string> = { NOT_CONFIGURED: '未配置', SAVED_UNVERIFIED: '已保存 · 尚未验证', VERIFIED: '验证通过', FAILED: '验证失败 / 连接异常', MANUAL_REQUIRED: '需要人工确认' }
function apply(data: SetupStatus) {
  state.value = data
  Object.assign(form, { publicUrl: data.publicUrl, providerUrl: data.providerUrl || '', providerApiToken: '', serverId: data.serverId || '', revision: data.revision })
}
async function load() {
  busy.value = true; error.value = ''
  try { await servers.load(); apply((await apiClient.get<ApiResponse<SetupStatus>>('/setup')).data.data) }
  catch (cause) { error.value = apiErrorMessage(cause, '无法读取初始化状态') }
  finally { busy.value = false }
}
async function save() {
  busy.value = true; error.value = ''
  try { apply((await apiClient.put<ApiResponse<SetupStatus>>('/setup', { ...form, serverId: form.serverId || null })).data.data) }
  catch (cause) { error.value = apiErrorMessage(cause, '保存失败，请检查配置或刷新后重试') }
  finally { busy.value = false }
}
async function verify() {
  busy.value = true; error.value = ''
  try { apply((await apiClient.post<ApiResponse<SetupStatus>>('/setup/verify-provider', {}, { timeout: 120000 })).data.data) }
  catch (cause) { error.value = apiErrorMessage(cause, '验证失败') }
  finally { busy.value = false }
}
onMounted(() => { restorePendingServer(); void load() })
</script>

<template>
  <main class="setup-page">
    <header><div><p class="eyebrow">GET STARTED</p><h1>初始化向导 Setup</h1><p>先连接平台与服务器，再接入项目。保存配置不会发布应用。</p></div><button :disabled="busy" @click="load">刷新状态</button></header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="!state && busy">正在读取初始化状态…</p>
    <form v-if="state" @submit.prevent="save">
      <section><h2>01 · 管理员</h2><span class="badge">已登录管理员</span><p>管理员已完成初始化。可在用户管理中配置其他账号和角色。</p><RouterLink to="/settings/users">管理账号 →</RouterLink></section>
      <section><h2>02 · 平台访问地址 <span class="badge">{{ labels[state.publicUrlStatus] }}</span></h2>
        <label>Public URL<input v-model="form.publicUrl" type="url" required placeholder="https://ops.example.com" /></label>
        <p>用于新生成的 Agent 安装命令。保存不会修改 DNS、TLS、监听端口或现有 Agent 配置；请从目标服务器和外部网络确认可达性。公网生产环境应使用 HTTPS。</p>
      </section>
      <section><h2>03 · Dokploy <span class="badge">{{ labels[state.providerStatus] }}</span></h2>
        <div class="fields"><label>平台地址<input v-model="form.providerUrl" type="url" placeholder="https://deploy.example.com" /></label><label>API Key<input v-model="form.providerApiToken" type="password" autocomplete="new-password" :placeholder="state.providerTokenConfigured ? '已加密保存；留空保留，变更地址需重填' : '填写具有必要权限的 Key'" /></label></div>
        <p>只读验证读取项目和服务器，不创建资源、不触发部署。验证结果 15 分钟后需要刷新；Key 配额和有效期仍需在 Dokploy 人工核对。</p>
        <button type="button" :disabled="busy || !state.providerTokenConfigured || form.providerUrl !== state.providerUrl || !!form.providerApiToken" @click="verify">验证已保存的连接</button>
        <p v-if="state.providerError" class="error">{{ state.providerError }}</p><small v-if="state.providerVerifiedAt">最近验证：{{ state.providerVerifiedAt }} UTC</small>
        <p v-if="state.providerStatus === 'MANUAL_REQUIRED'" role="status">验证时间明显超前，请核对 DevPilot 主机时间，再重新验证连接；当前不视为验证通过。</p>
      </section>
      <section><h2>04 · 目标服务器 <span class="badge">{{ labels[state.serverStatus] }}</span></h2>
        <label>选择服务器<select v-model="form.serverId"><option value="">尚未选择</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label>
        <p>DevPilot 服务器应与 Dokploy 实际部署目标一致，不根据 IP 猜测对应关系。</p><RouterLink to="/servers">添加服务器并获取 Agent 安装命令 →</RouterLink>
        <div class="fields"><label>或添加新服务器<input v-model="serverName" :readonly="!!pendingServer" maxlength="100" placeholder="personal-server" /></label><div><button type="button" :disabled="busy || serverName.trim().length < 2 || form.publicUrl !== state.publicUrl || form.providerUrl !== (state.providerUrl || '') || !!form.providerApiToken" @click="createServer">添加并保存服务器选择</button></div></div>
        <p v-if="pendingServer">有尚未确认完成的创建请求，点击添加会恢复同一次操作，不会新建第二台服务器。安装命令可在 24 小时内恢复；浏览器只保存请求标识和名称。</p>
        <ServerCreationRecovery v-if="pendingServer" :key="pendingServer.requestId" :pending="pendingServer" :disabled="busy" @finished="pendingServer = null; serverName = ''; error = ''" />
        <p>先保存正确的访问地址，再添加服务器。创建将生成一次性 Agent Token，刷新后不会再次显示。</p>
        <div v-if="installCommand"><label>在目标服务器执行（含密钥，请勿分享）<textarea :value="installCommand" readonly rows="4" /></label><button type="button" @click="installCommand = ''">隐藏安装命令</button></div>
      </section>
      <section><h2>05 · Agent 通信 <span class="badge">{{ labels[state.agentStatus] }}</span></h2><p>在目标 Linux 主机执行安装命令，再回到这里刷新。只有在线状态和有效期内的心跳同时满足，才显示验证通过；没有心跳不代表业务服务故障。</p><p v-if="state.agentStatus === 'MANUAL_REQUIRED'" role="status">心跳时间明显超前，请核对 DevPilot 与目标主机的时间并等待新心跳；当前不视为验证通过。</p><small>最近心跳：{{ state.lastHeartbeat ? `${state.lastHeartbeat} UTC` : '尚未收到' }}</small></section>
      <footer><button type="submit" :disabled="busy">{{ busy ? '处理中…' : '保存初始化配置' }}</button><RouterLink to="/cicd/onboarding">接入项目 →</RouterLink></footer>
    </form>
  </main>
</template>

<style scoped>
.setup-page header>button{flex-shrink:0;white-space:nowrap}section>small{display:block;margin-top:10px}.fields{align-items:end}section>a+.fields{margin-top:16px}textarea{box-sizing:border-box;width:100%;padding:12px;border:1px solid #cbd5e1;border-radius:8px;background:transparent;color:inherit;overflow-wrap:anywhere}@media(max-width:640px){.setup-page header{flex-direction:column;align-items:flex-start}.setup-page header>button{align-self:flex-end}}
.setup-page{max-width:1000px;margin:auto;padding:24px}.setup-page header{display:flex;justify-content:space-between;align-items:center;gap:16px}.eyebrow{font-size:12px;letter-spacing:.14em;color:#64748b}h1{font-size:28px;margin:8px 0}h2{font-size:18px;margin:0 0 18px}section{padding:24px;margin:18px 0;border:1px solid var(--border-color,#dbe2ea);border-radius:14px;background:var(--bg-card,#fff)}p,small{line-height:1.7;color:#64748b}.fields{display:grid;grid-template-columns:1fr 1fr;gap:18px}label{display:grid;gap:8px;font-size:14px}input,select{padding:11px;border:1px solid #cbd5e1;border-radius:8px;background:transparent;color:inherit;min-width:0}.badge{display:inline-block;font-size:12px;font-weight:500;padding:4px 9px;border-radius:20px;background:#eef2ff;color:#4338ca;margin-left:8px}button{padding:10px 16px;border:0;border-radius:8px;background:#2563eb;color:white;cursor:pointer}button:disabled{opacity:.5;cursor:default}footer{display:flex;gap:24px;align-items:center}.error{color:#b91c1c}a{color:#2563eb}@media(max-width:640px){.fields{grid-template-columns:1fr}.setup-page{padding:12px}section{padding:18px}}
</style>
