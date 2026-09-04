<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { apiErrorMessage } from '@/api/client'
import {
  serviceTemplateApi,
  type ServiceInstallation,
  type ServiceTemplate,
} from '@/api/serviceTemplates'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const auth = useAuthStore()
const servers = useServerStore()
const templates = ref<ServiceTemplate[]>([])
const installations = ref<ServiceInstallation[]>([])
const loading = ref(true)
const saving = ref(false)
const query = ref('')
const category = ref('全部 All')
const selected = ref<ServiceTemplate | null>(null)
const errorMessage = ref('')
const successMessage = ref('')
let pollTimer: number | undefined

const form = reactive({
  serverId: '', displayName: '', instanceName: '', environment: 'PRODUCTION', hostPort: 3001,
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', confirmation: false,
})

const canInstall = computed(() => auth.hasAnyRole(['ADMIN']))
const onlineServers = computed(() => servers.servers.filter((server) => server.status === 'ONLINE'))
const categories = computed(() => ['全部 All', ...new Set(templates.value.map((template) => template.category))])
const filteredTemplates = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return templates.value.filter((template) => {
    const categoryMatches = category.value === '全部 All' || template.category === category.value
    const text = `${template.name} ${template.category} ${template.description} ${template.image}`.toLowerCase()
    return categoryMatches && (!needle || text.includes(needle))
  })
})
const recentInstallations = computed(() => installations.value.slice(0, 6))
const selectedServer = computed(() => onlineServers.value.find((server) => server.id === form.serverId))
const installReady = computed(() => selected.value && selectedServer.value && form.displayName.trim().length >= 2
  && /^[a-z][a-z0-9-]{1,62}[a-z0-9]$/.test(form.instanceName)
  && form.hostPort >= 1024 && form.hostPort <= 65535 && form.confirmation)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [catalog, history] = await Promise.all([serviceTemplateApi.catalog(), serviceTemplateApi.installations()])
    templates.value = catalog
    installations.value = history
    errorMessage.value = ''
  } catch (error) {
    if (!silent) errorMessage.value = apiErrorMessage(error, '无法加载服务模板 Template catalog')
  } finally {
    loading.value = false
  }
}

function slug(value: string) {
  const normalized = value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  return normalized.length >= 3 && /^[a-z]/.test(normalized) ? normalized.slice(0, 64) : `app-${normalized || 'service'}`
}

function openInstall(template: ServiceTemplate) {
  selected.value = template
  Object.assign(form, {
    serverId: onlineServers.value[0]?.id || '', displayName: template.name,
    instanceName: slug(template.id), environment: 'PRODUCTION', hostPort: template.recommendedPort,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', confirmation: false,
  })
  errorMessage.value = ''
}

async function install() {
  if (!selected.value || !installReady.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    const created = await serviceTemplateApi.install(selected.value.id, {
      serverId: form.serverId, displayName: form.displayName.trim(), instanceName: form.instanceName.trim(),
      environment: form.environment, hostPort: form.hostPort, timezone: form.timezone,
    })
    installations.value = [created, ...installations.value]
    successMessage.value = `${created.displayName} 已进入安装队列，Agent 将自动拉取镜像并纳管。`
    selected.value = null
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '服务安装请求失败')
  } finally {
    saving.value = false
  }
}

function formatBytes(value: number) {
  return `${Math.round(value / 1024 / 1024)} MB`
}

function formatTime(value: string | null) {
  if (!value) return '等待中'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function statusLabel(status: ServiceInstallation['status']) {
  return ({
    REQUESTED: '排队 Queued', CLAIMED: '安装中 Installing', DISCOVERING: '正在纳管 Importing',
    READY: '已就绪 Ready', FAILED: '失败 Failed',
  } as const)[status]
}

function stage(status: ServiceInstallation['status']) {
  return ({ REQUESTED: 1, CLAIMED: 2, DISCOVERING: 3, READY: 4, FAILED: 2 } as const)[status]
}

onMounted(async () => {
  await Promise.all([servers.load(), load()])
  pollTimer = window.setInterval(() => void load(true), 4_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="templates-view">
    <header class="page-heading templates-heading">
      <div><p class="eyebrow">一键服务 · ONE-CLICK SERVICES</p><h1>个人服务模板</h1><span>选模板、选服务器，DevPilot 安全创建容器、持久卷和应用记录。</span></div>
      <RouterLink class="secondary-compact" to="/applications">查看应用 Applications</RouterLink>
    </header>

    <p v-if="successMessage" class="template-success"><span>✓</span>{{ successMessage }}<button @click="successMessage = ''">×</button></p>
    <p v-if="errorMessage && !selected" class="inline-error">{{ errorMessage }}</p>

    <section class="safety-banner">
      <div class="safety-mark">⌂</div>
      <div><span>SECURE BY DEFAULT</span><strong>默认不直接暴露到公网</strong><p>Web 端口只绑定 <code>127.0.0.1</code>；数据库与 SSH 端口不发布。确认服务可用后，再通过 Nginx 配置域名和 HTTPS。</p></div>
      <ul><li><i />固定版本镜像</li><li><i />命名持久卷</li><li><i />内存与日志上限</li></ul>
    </section>

    <section v-if="recentInstallations.length" class="installation-panel">
      <header><div><span>DEPLOYMENT ACTIVITY</span><strong>最近安装</strong></div><small>Agent 每 2 秒领取一次任务</small></header>
      <div class="installation-list">
        <article v-for="item in recentInstallations" :key="item.id" :class="item.status.toLowerCase()">
          <div class="install-symbol">{{ item.templateName.slice(0, 2).toUpperCase() }}</div>
          <div class="install-main"><strong>{{ item.displayName }}</strong><small>{{ item.serverName }} · 127.0.0.1:{{ item.hostPort || item.requestedPort }}</small><div class="install-progress"><i v-for="number in 4" :key="number" :class="{ done: stage(item.status) >= number }" /></div></div>
          <div class="install-status"><span>{{ statusLabel(item.status) }}</span><small>{{ formatTime(item.completedAt || item.updatedAt) }}</small></div>
          <RouterLink v-if="item.applicationId" :to="`/applications/${item.applicationId}`">打开应用 →</RouterLink>
          <p v-if="item.errorMessage" class="install-error">{{ item.errorMessage }}</p>
        </article>
      </div>
    </section>

    <div class="template-toolbar">
      <label><span>⌕</span><input v-model="query" placeholder="搜索模板、用途或镜像 Search templates" /></label>
      <div><button v-for="item in categories" :key="item" :class="{ active: category === item }" @click="category = item">{{ item }}</button></div>
    </div>

    <div v-if="loading && !templates.length" class="template-loading"><span class="loading-ring" /><strong>正在加载模板目录</strong></div>
    <div v-else class="template-grid">
      <article v-for="template in filteredTemplates" :key="template.id" class="template-card" :class="template.accent">
        <header><div class="template-logo">{{ template.shortName }}</div><div><span>{{ template.category }}</span><h2>{{ template.name }}</h2></div><b>v{{ template.version }}</b></header>
        <p>{{ template.description }}</p>
        <dl><div><dt>Web 端口</dt><dd>127.0.0.1:{{ template.recommendedPort }}</dd></div><div><dt>内存上限</dt><dd>{{ formatBytes(template.memoryLimitBytes) }}</dd></div></dl>
        <section><span>持久数据 Persistent data</span><ul><li v-for="item in template.persistentData" :key="item"><i />{{ item }}</li></ul></section>
        <code>{{ template.image }}</code>
        <footer><div><a :href="template.documentationUrl" target="_blank" rel="noreferrer">文档 Docs ↗</a><a :href="template.sourceUrl" target="_blank" rel="noreferrer">源码 Source ↗</a></div><button v-if="canInstall" :disabled="!onlineServers.length" @click="openInstall(template)">{{ onlineServers.length ? '安装 Install' : '无在线服务器' }} <b>→</b></button><span v-else>仅管理员可安装</span></footer>
      </article>
    </div>
    <div v-if="!loading && !filteredTemplates.length" class="template-loading"><strong>没有匹配的模板</strong><small>尝试其他关键字或分类。</small></div>

    <section class="catalog-note"><span>为什么先做精选目录？</span><p>模板会直接运行第三方代码。DevPilot 先维护少量可审计、固定版本、跨架构的模板，再逐步扩充；不会未经确认执行用户提供的 Compose 或 Shell。</p></section>

    <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
      <section class="server-dialog install-dialog" role="dialog" aria-modal="true" aria-labelledby="install-title">
        <header><div><span>ONE-CLICK INSTALL</span><h2 id="install-title">安装 {{ selected.name }}</h2></div><button aria-label="关闭" @click="selected = null">×</button></header>
        <div class="dialog-body install-form">
          <div class="install-preview"><span class="template-logo" :class="selected.accent">{{ selected.shortName }}</span><div><strong>{{ selected.image }}</strong><small>{{ selected.setupHint }}</small></div></div>
          <div class="form-grid"><label><span>显示名称 Name</span><input v-model.trim="form.displayName" maxlength="120" /></label><label><span>实例名 Instance</span><input v-model.trim="form.instanceName" maxlength="64" pattern="[a-z][a-z0-9-]+" /><small>同时作为容器名和应用编码的一部分。</small></label></div>
          <div class="form-grid"><label><span>服务器 Server</span><select v-model="form.serverId"><option disabled value="">选择在线服务器</option><option v-for="server in onlineServers" :key="server.id" :value="server.id">{{ server.name }} · {{ server.ip || server.hostname }}</option></select></label><label><span>环境 Environment</span><select v-model="form.environment"><option v-for="item in ['DEV','TEST','STAGING','PRODUCTION']" :key="item">{{ item }}</option></select></label></div>
          <div class="form-grid"><label><span>本机端口 Loopback port</span><input v-model.number="form.hostPort" type="number" min="1024" max="65535" /><small>只绑定 127.0.0.1，不开放公网。</small></label><label><span>时区 Timezone</span><input v-model.trim="form.timezone" maxlength="64" /></label></div>
          <aside class="install-resource"><div><span>镜像</span><strong>{{ selected.image }}</strong></div><div><span>持久卷</span><strong>{{ selected.persistentData.length }} 个</strong></div><div><span>内存上限</span><strong>{{ formatBytes(selected.memoryLimitBytes) }}</strong></div></aside>
          <label class="install-confirm"><input v-model="form.confirmation" type="checkbox" /><span>我了解：安装会拉取第三方镜像并创建持久卷；对外访问仍需单独配置 Nginx 与 HTTPS。</span></label>
          <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
        </div>
        <footer><button @click="selected = null">取消</button><button class="dialog-primary" :disabled="saving || !installReady" @click="install">{{ saving ? '正在提交…' : '确认安装' }} <b>→</b></button></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.templates-view{max-width:1480px;margin:0 auto}.templates-heading{align-items:center}.template-success{display:flex;align-items:center;gap:10px;margin:0 0 15px;border:1px solid rgba(16,185,129,.22);border-radius:11px;padding:12px 14px;color:#147b5b;background:#ecfdf5;font-size:11px}.template-success>span{display:grid;width:22px;height:22px;place-items:center;border-radius:50%;color:white;background:#10b981}.template-success button{margin-left:auto;border:0;color:#5f7d73;background:transparent;font-size:16px}.safety-banner{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:16px;border:1px solid #cfe8df;border-radius:16px;padding:20px 22px;background:linear-gradient(120deg,#f0fdf9,#f7fbff);box-shadow:0 9px 30px rgba(59,130,108,.06)}.safety-mark{display:grid;width:48px;height:48px;place-items:center;border:1px solid #bce3d5;border-radius:14px;color:#148164;background:#dcf7ed;font-size:22px}.safety-banner span{color:#16836a;font-size:9px;font-weight:850;letter-spacing:.13em}.safety-banner strong{display:block;margin-top:6px;font-size:15px}.safety-banner p{margin:5px 0 0;color:#66798a;font-size:10px;line-height:1.55}.safety-banner code{color:#16836a}.safety-banner ul{display:flex;gap:8px;margin:0;padding:0;list-style:none}.safety-banner li{display:flex;align-items:center;gap:6px;border:1px solid rgba(20,129,100,.13);border-radius:20px;padding:7px 10px;color:#477064;background:rgba(255,255,255,.7);font-size:9px;white-space:nowrap}.safety-banner li i{width:5px;height:5px;border-radius:50%;background:#20b589}.installation-panel{overflow:hidden;margin-top:16px;border:1px solid var(--line);border-radius:15px;background:var(--panel);box-shadow:0 8px 26px rgba(38,57,84,.04)}.installation-panel>header{display:flex;align-items:center;justify-content:space-between;padding:15px 18px;border-bottom:1px solid var(--line)}.installation-panel>header span,.installation-panel>header strong{display:block}.installation-panel>header span{color:#668fcf;font-size:8px;font-weight:850;letter-spacing:.12em}.installation-panel>header strong{margin-top:5px;font-size:13px}.installation-panel>header small{color:var(--muted);font-size:9px}.installation-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;background:var(--line)}.installation-list article{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:12px;padding:14px 16px;background:var(--panel-solid)}.install-symbol{display:grid;width:36px;height:36px;place-items:center;border-radius:10px;color:#2670c6;background:#eaf3ff;font-size:10px;font-weight:900}.install-main{min-width:0}.install-main strong,.install-main small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.install-main strong{font-size:11px}.install-main small{margin-top:4px;color:var(--muted);font-size:9px}.install-progress{display:grid;grid-template-columns:repeat(4,1fr);gap:3px;margin-top:9px}.install-progress i{height:3px;border-radius:4px;background:#e3e9f1}.install-progress i.done{background:#60a5fa}.ready .install-progress i.done{background:#34c796}.install-status{text-align:right}.install-status span,.install-status small{display:block}.install-status span{color:#3974bd;font-size:9px;font-weight:800}.ready .install-status span{color:#168064}.install-status small{margin-top:5px;color:var(--muted);font-size:8px}.installation-list article>a{grid-column:3;color:#3974bd;font-size:9px;text-decoration:none}.template-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:22px 0 14px}.template-toolbar>label{display:flex;width:min(430px,40vw);height:40px;align-items:center;gap:9px;border:1px solid var(--line);border-radius:10px;padding:0 12px;background:var(--panel)}.template-toolbar input{width:100%;border:0;outline:0;color:var(--text);background:transparent;font-size:11px}.template-toolbar>div{display:flex;gap:6px}.template-toolbar button{height:34px;border:1px solid var(--line);border-radius:9px;padding:0 11px;color:#6b7d91;background:var(--panel);font-size:9px}.template-toolbar button.active{border-color:#9fbdec;color:#2866b3;background:#edf5ff}.template-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:15px}.template-card{overflow:hidden;border:1px solid var(--line);border-radius:16px;padding:19px;background:var(--panel);box-shadow:0 8px 28px rgba(38,57,84,.045);transition:transform .15s,border-color .15s}.template-card:hover{transform:translateY(-2px);border-color:#b8cce8}.template-card>header{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:12px}.template-logo{display:grid;width:44px;height:44px;place-items:center;border-radius:13px;color:#147c62;background:#e3f8f0;font-size:12px;font-weight:900}.template-card.blue .template-logo,.template-logo.blue{color:#286ac2;background:#eaf3ff}.template-card.violet .template-logo,.template-logo.violet{color:#7755bd;background:#f1edff}.template-card header span{color:#7a899b;font-size:8px;font-weight:750}.template-card h2{margin:5px 0 0;font-size:15px}.template-card header>b{border:1px solid var(--line);border-radius:15px;padding:5px 8px;color:#718197;font-size:8px}.template-card>p{min-height:48px;margin:17px 0;color:#66788d;font-size:10px;line-height:1.65}.template-card dl{display:grid;grid-template-columns:1fr 1fr;margin:0;border:1px solid var(--line);border-radius:10px;background:#f8fafc}.template-card dl>div{padding:10px 11px}.template-card dl>div+div{border-left:1px solid var(--line)}.template-card dt{color:#8896a7;font-size:8px}.template-card dd{margin:5px 0 0;color:#43566e;font:9px ui-monospace,monospace}.template-card>section{margin-top:14px}.template-card>section>span{color:#7b8a9c;font-size:8px;font-weight:800}.template-card ul{display:grid;gap:5px;margin:8px 0 0;padding:0;list-style:none}.template-card li{display:flex;align-items:center;gap:6px;color:#617287;font-size:9px}.template-card li i{width:5px;height:5px;border-radius:50%;background:#78a8e8}.template-card>code{display:block;overflow:hidden;margin-top:15px;border-radius:7px;padding:8px 9px;color:#4e6684;background:#f3f6fa;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.template-card>footer{display:flex;align-items:center;justify-content:space-between;margin-top:16px;padding-top:14px;border-top:1px solid var(--line)}.template-card footer>div{display:flex;gap:10px}.template-card a{color:#5f7da5;font-size:8px;text-decoration:none}.template-card footer>button{height:34px;border:0;border-radius:9px;padding:0 12px;color:white;background:#3478cf;font-size:9px;font-weight:800}.template-card.mint footer>button{background:#16886a}.template-card.violet footer>button{background:#7755bd}.template-card footer>button:disabled{cursor:not-allowed;opacity:.45}.template-card footer>span{color:var(--muted);font-size:8px}.template-loading{display:grid;min-height:220px;place-items:center;align-content:center;gap:10px;border:1px dashed var(--line);border-radius:15px;color:var(--muted);background:var(--panel)}.template-loading small{font-size:9px}.catalog-note{display:flex;gap:16px;margin-top:16px;border:1px dashed #cad8e8;border-radius:12px;padding:14px 16px;background:#f8fbff}.catalog-note span{flex:0 0 auto;color:#376ea9;font-size:10px;font-weight:800}.catalog-note p{margin:0;color:#708095;font-size:9px;line-height:1.55}.install-dialog{width:min(760px,calc(100vw - 30px))}.install-form{display:grid;gap:16px}.install-preview{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:12px;border:1px solid var(--line);border-radius:11px;padding:12px;background:#f8fafc}.install-preview strong,.install-preview small{display:block}.install-preview strong{font:10px ui-monospace,monospace}.install-preview small{margin-top:5px;color:var(--muted);font-size:9px}.install-resource{display:grid;grid-template-columns:2fr 1fr 1fr;border:1px solid var(--line);border-radius:10px;background:#f8fafc}.install-resource>div{padding:11px}.install-resource>div+div{border-left:1px solid var(--line)}.install-resource span,.install-resource strong{display:block}.install-resource span{color:var(--muted);font-size:8px}.install-resource strong{overflow:hidden;margin-top:5px;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.install-confirm{display:flex!important;grid-column:1/-1!important;grid-template-columns:none!important;align-items:flex-start;gap:9px!important;border:1px solid #f1d9a9;border-radius:10px;padding:11px;color:#805e22!important;background:#fffaf0}.install-confirm input{width:15px!important;height:15px!important;flex:0 0 auto;accent-color:#3478cf}.install-confirm span{font-size:9px;line-height:1.5}@media(max-width:1100px){.template-grid{grid-template-columns:1fr 1fr}.safety-banner ul{display:none}}@media(max-width:720px){.template-grid,.installation-list{grid-template-columns:1fr}.safety-banner{grid-template-columns:auto 1fr}.template-toolbar{align-items:stretch;flex-direction:column}.template-toolbar>label{width:100%}.template-toolbar>div{overflow:auto}.install-resource{grid-template-columns:1fr}.install-resource>div+div{border-top:1px solid var(--line);border-left:0}}
.failed .install-status span{color:#c84a4a}.install-error{grid-column:2/4;margin:0;border-radius:7px;padding:7px 9px;color:#a63f3f;background:#fff1f1;font-size:8px;line-height:1.45}
:global(.dark) .safety-banner{border-color:#25443e;background:linear-gradient(120deg,#102620,#121d2b)}:global(.dark) .template-success{color:#7ee0bd;background:#10261f}:global(.dark) .template-card dl,:global(.dark) .template-card>code,:global(.dark) .install-preview,:global(.dark) .install-resource,:global(.dark) .catalog-note{background:rgba(148,163,184,.045)}:global(.dark) .install-error{color:#f5a0a0;background:rgba(239,68,68,.09)}
</style>
