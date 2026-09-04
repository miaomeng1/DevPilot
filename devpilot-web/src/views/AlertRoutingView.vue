<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { alertsApi, type AlertRoute, type AlertRoutePayload, type AlertSeverity, type MaintenanceWindow } from '@/api/alerts'
import { apiErrorMessage } from '@/api/client'
import { useServerStore } from '@/stores/servers'

const servers = useServerStore()
const routes = ref<AlertRoute[]>([])
const windows = ref<MaintenanceWindow[]>([])
const legacy = ref({ enabled: false, configured: false, destinationType: 'NONE' })
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const routeDialog = ref(false)
const maintenanceDialog = ref(false)
const editingRouteId = ref<string | null>(null)
const webhookUrl = ref('')
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'

const dayOptions = [
  { value: 'MONDAY', label: '一' }, { value: 'TUESDAY', label: '二' },
  { value: 'WEDNESDAY', label: '三' }, { value: 'THURSDAY', label: '四' },
  { value: 'FRIDAY', label: '五' }, { value: 'SATURDAY', label: '六' },
  { value: 'SUNDAY', label: '日' },
]

const routeForm = reactive<AlertRoutePayload>({
  name: '', serverId: null, minimumSeverity: 'WARNING', notifyResolved: true, enabled: true,
  quietEnabled: false, quietStart: '23:00', quietEnd: '08:00', quietDays: dayOptions.map((day) => day.value),
  timezone, criticalBypassMute: true,
})
const maintenanceForm = reactive({ name: '', reason: '', serverId: null as string | null, startsAt: '', endsAt: '' })

const summary = computed(() => ({
  enabledRoutes: routes.value.filter((route) => route.enabled).length,
  mutedRoutes: routes.value.filter((route) => route.enabled && route.mutedNow).length,
  activeWindows: windows.value.filter((window) => window.status === 'ACTIVE').length,
  upcomingWindows: windows.value.filter((window) => window.status === 'UPCOMING').length,
}))

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [routeList, windowList, webhook] = await Promise.all([
      alertsApi.routes(), alertsApi.maintenanceWindows(), alertsApi.webhook(),
    ])
    routes.value = routeList
    windows.value = windowList
    legacy.value = webhook
  } catch (error) { errorMessage.value = apiErrorMessage(error, '通知配置加载失败') }
  finally { loading.value = false }
}

function resetRoute() {
  Object.assign(routeForm, {
    name: '', serverId: null, minimumSeverity: 'WARNING', notifyResolved: true, enabled: true,
    quietEnabled: false, quietStart: '23:00', quietEnd: '08:00', quietDays: dayOptions.map((day) => day.value),
    timezone, criticalBypassMute: true,
  })
  webhookUrl.value = ''
  editingRouteId.value = null
}

function openCreateRoute() {
  resetRoute()
  routeDialog.value = true
}

function openEditRoute(route: AlertRoute) {
  editingRouteId.value = route.id
  Object.assign(routeForm, {
    name: route.name, serverId: route.serverId, minimumSeverity: route.minimumSeverity,
    notifyResolved: route.notifyResolved, enabled: route.enabled, quietEnabled: route.quietEnabled,
    quietStart: route.quietStart || '23:00', quietEnd: route.quietEnd || '08:00',
    quietDays: [...route.quietDays], timezone: route.timezone, criticalBypassMute: route.criticalBypassMute,
  })
  webhookUrl.value = ''
  routeDialog.value = true
}

function routePayload(enabled = routeForm.enabled): AlertRoutePayload {
  return {
    ...routeForm, enabled, quietDays: [...routeForm.quietDays],
    quietStart: routeForm.quietEnabled ? routeForm.quietStart : null,
    quietEnd: routeForm.quietEnabled ? routeForm.quietEnd : null,
    webhookUrl: webhookUrl.value.trim() || undefined,
  }
}

function quietChanged() {
  if (routeForm.quietEnabled && !routeForm.quietDays.length) {
    routeForm.quietDays = dayOptions.map((day) => day.value)
  }
}

async function saveRoute() {
  if (!routeForm.name.trim() || (!editingRouteId.value && !webhookUrl.value.trim())) return
  saving.value = true
  errorMessage.value = ''
  try {
    if (editingRouteId.value) await alertsApi.updateRoute(editingRouteId.value, routePayload())
    else await alertsApi.createRoute(routePayload())
    routeDialog.value = false
    await load()
  } catch (error) { errorMessage.value = apiErrorMessage(error, '通知路由保存失败') }
  finally { saving.value = false }
}

async function toggleRoute(route: AlertRoute) {
  try {
    await alertsApi.updateRoute(route.id, {
      name: route.name, serverId: route.serverId, minimumSeverity: route.minimumSeverity,
      notifyResolved: route.notifyResolved, enabled: !route.enabled, quietEnabled: route.quietEnabled,
      quietStart: route.quietStart, quietEnd: route.quietEnd, quietDays: [...route.quietDays],
      timezone: route.timezone, criticalBypassMute: route.criticalBypassMute,
    })
    await load()
  } catch (error) { errorMessage.value = apiErrorMessage(error, '无法更新路由状态') }
}

async function removeRoute(route: AlertRoute) {
  if (!window.confirm(`删除通知路由“${route.name}”？未发送的该路由通知会被跳过。`)) return
  try { await alertsApi.deleteRoute(route.id); await load() }
  catch (error) { errorMessage.value = apiErrorMessage(error, '无法删除通知路由') }
}

function toLocalInput(date: Date) {
  const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return shifted.toISOString().slice(0, 16)
}

function openMaintenance() {
  const start = new Date(Date.now() + 5 * 60_000)
  const end = new Date(start.getTime() + 60 * 60_000)
  Object.assign(maintenanceForm, {
    name: '计划维护', reason: '', serverId: null,
    startsAt: toLocalInput(start), endsAt: toLocalInput(end),
  })
  maintenanceDialog.value = true
}

async function saveMaintenance() {
  if (!maintenanceForm.name.trim() || !maintenanceForm.startsAt || !maintenanceForm.endsAt) return
  saving.value = true
  errorMessage.value = ''
  try {
    await alertsApi.createMaintenanceWindow({
      name: maintenanceForm.name.trim(), reason: maintenanceForm.reason.trim() || undefined,
      serverId: maintenanceForm.serverId, startsAt: new Date(maintenanceForm.startsAt).toISOString(),
      endsAt: new Date(maintenanceForm.endsAt).toISOString(),
    })
    maintenanceDialog.value = false
    await load()
  } catch (error) { errorMessage.value = apiErrorMessage(error, '维护窗口保存失败') }
  finally { saving.value = false }
}

async function removeMaintenance(item: MaintenanceWindow) {
  if (!window.confirm(`取消维护窗口“${item.name}”？`)) return
  try { await alertsApi.deleteMaintenanceWindow(item.id); await load() }
  catch (error) { errorMessage.value = apiErrorMessage(error, '无法取消维护窗口') }
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function severityText(value: AlertSeverity) {
  return value === 'CRITICAL' ? '仅严重 Critical' : value === 'WARNING' ? '警告及以上 Warning+' : '全部级别 All'
}

function quietText(route: AlertRoute) {
  if (!route.quietEnabled) return '无周期静默'
  const days = route.quietDays.map((value) => dayOptions.find((day) => day.value === value)?.label).join('、')
  return `周${days} · ${route.quietStart}–${route.quietEnd} · ${route.timezone}`
}

onMounted(async () => { await servers.load(); await load() })
</script>

<template>
  <section class="routing-view">
    <header class="page-heading routing-heading"><div><p class="eyebrow">NOTIFICATION CONTROL</p><h1>通知路由与静默</h1><span>让重要告警到达正确的位置；维护期间保留事件，但不制造通知噪音。</span></div><button class="refresh-button" @click="load">{{ loading ? '刷新中…' : '刷新 Refresh' }}</button></header>
    <nav class="alert-tabs"><RouterLink to="/alerts">事件 Events</RouterLink><RouterLink to="/alerts/rules">规则 Rules</RouterLink><RouterLink class="active" to="/alerts/routing">通知与静默 Routing</RouterLink></nav>
    <p v-if="errorMessage && !routeDialog && !maintenanceDialog" class="inline-error">{{ errorMessage }}</p>

    <div class="routing-summary">
      <article><span>ACTIVE ROUTES</span><strong>{{ summary.enabledRoutes }}</strong><small>启用的通知路径</small></article>
      <article :class="{ quiet: summary.mutedRoutes }"><span>QUIET NOW</span><strong>{{ summary.mutedRoutes }}</strong><small>当前处于周期静默</small></article>
      <article :class="{ maintenance: summary.activeWindows }"><span>MAINTENANCE</span><strong>{{ summary.activeWindows }}</strong><small>{{ summary.upcomingWindows }} 个即将开始</small></article>
    </div>

    <div class="routing-principle"><span>i</span><div><strong>静默通知，不停止检测</strong><p>告警仍会进入事件时间线并正常恢复。严重告警可选择绕过夜间静默和维护窗口，避免真正的故障被隐藏。</p></div></div>

    <section class="routing-panel">
      <header><div><p class="eyebrow">ROUTES</p><h2>通知路由 Notification routes</h2><span>按最低严重级别和服务器匹配，可同时发送到多个接收端。</span></div><button class="primary-compact" @click="openCreateRoute">＋ 新建路由</button></header>
      <div v-if="!routes.length" class="route-empty"><span>↗</span><strong>尚未配置通知路由</strong><p v-if="legacy.configured">旧版 {{ legacy.destinationType }} Webhook {{ legacy.enabled ? '仍在工作' : '当前停用' }}；创建第一条路由后，将由新路由接管匹配。</p><p v-else>创建一条路由，把 Critical 或 Warning 告警发送到飞书、企业微信、Discord 或自定义 Webhook。</p><button @click="openCreateRoute">创建第一条路由</button></div>
      <div v-else class="route-grid"><article v-for="route in routes" :key="route.id" :class="['route-card', { disabled: !route.enabled, muted: route.mutedNow }]">
        <header><div class="route-destination"><span>{{ route.destinationType.slice(0, 2) }}</span><div><strong>{{ route.name }}</strong><small>{{ route.destinationType }} · {{ route.serverName }}</small></div></div><span class="route-state"><i />{{ !route.enabled ? 'OFF' : route.mutedNow ? 'QUIET' : 'ACTIVE' }}</span></header>
        <dl><div><dt>匹配范围</dt><dd>{{ severityText(route.minimumSeverity) }}</dd></div><div><dt>恢复通知</dt><dd>{{ route.notifyResolved ? '发送 Resolved' : '不发送' }}</dd></div><div><dt>周期静默</dt><dd>{{ quietText(route) }}</dd></div><div><dt>严重绕过</dt><dd>{{ route.criticalBypassMute ? 'Critical 始终通知' : '同样静默' }}</dd></div></dl>
        <footer><button @click="openEditRoute(route)">编辑</button><button @click="toggleRoute(route)">{{ route.enabled ? '停用' : '启用' }}</button><button class="danger" @click="removeRoute(route)">删除</button></footer>
      </article></div>
    </section>

    <section class="routing-panel maintenance-panel">
      <header><div><p class="eyebrow">ONE-TIME SILENCES</p><h2>维护窗口 Maintenance windows</h2><span>适合升级、迁移和计划重启；到期后自动恢复通知。</span></div><button class="secondary-compact" @click="openMaintenance">＋ 安排维护</button></header>
      <div v-if="!windows.length" class="maintenance-empty"><span>没有进行中或近期维护</span><button @click="openMaintenance">安排一次</button></div>
      <div v-else class="window-list"><article v-for="item in windows" :key="item.id" :class="item.status.toLowerCase()"><span class="window-status">{{ item.status }}</span><div><strong>{{ item.name }}</strong><p>{{ item.serverName }}<template v-if="item.reason"> · {{ item.reason }}</template></p></div><time>{{ formatTime(item.startsAt) }}<b>→</b>{{ formatTime(item.endsAt) }}</time><button @click="removeMaintenance(item)">取消</button></article></div>
    </section>

    <div v-if="routeDialog" class="modal-backdrop" @click.self="routeDialog = false"><section class="server-dialog routing-dialog" role="dialog" aria-modal="true"><header><div><span>DELIVERY ROUTE</span><h2>{{ editingRouteId ? '编辑通知路由' : '新建通知路由' }}</h2></div><button @click="routeDialog = false">×</button></header><div class="dialog-body application-form">
      <div class="form-grid"><label><span>路由名称 Name</span><input v-model.trim="routeForm.name" maxlength="120" placeholder="严重告警到飞书" /></label><label><span>服务器范围 Scope</span><select v-model="routeForm.serverId"><option :value="null">全部服务器 All</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label></div>
      <div class="form-grid"><label><span>最低严重级别</span><select v-model="routeForm.minimumSeverity"><option value="INFO">全部级别 Info+</option><option value="WARNING">警告及以上 Warning+</option><option value="CRITICAL">仅严重 Critical</option></select></label><label><span>时区 Timezone</span><input v-model.trim="routeForm.timezone" maxlength="64" placeholder="Asia/Shanghai" /></label></div>
      <label><span>Webhook URL</span><input v-model="webhookUrl" type="password" autocomplete="new-password" :placeholder="editingRouteId ? '已加密保存 · 留空保持不变' : 'https://hooks.example.com/...'" /><small>只在保存时提交，服务端使用 Master Key 加密，之后不会回显。</small></label>
      <div class="route-toggles"><label><input v-model="routeForm.enabled" type="checkbox" /><span>立即启用路由</span></label><label><input v-model="routeForm.notifyResolved" type="checkbox" /><span>恢复时也发送通知</span></label><label><input v-model="routeForm.criticalBypassMute" type="checkbox" /><span>Critical 绕过所有静默</span></label></div>
      <section class="quiet-config"><label class="quiet-switch"><input v-model="routeForm.quietEnabled" type="checkbox" @change="quietChanged" /><span><strong>周期静默 Quiet hours</strong><small>例如每天 23:00 到次日 08:00</small></span></label><template v-if="routeForm.quietEnabled"><div class="form-grid"><label><span>开始</span><input v-model="routeForm.quietStart" type="time" /></label><label><span>结束</span><input v-model="routeForm.quietEnd" type="time" /></label></div><div class="day-picker"><span>生效星期</span><label v-for="day in dayOptions" :key="day.value"><input v-model="routeForm.quietDays" type="checkbox" :value="day.value" /><b>{{ day.label }}</b></label></div></template></section>
      <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    </div><footer><button @click="routeDialog = false">取消</button><button class="dialog-primary" :disabled="saving || !routeForm.name || (!editingRouteId && !webhookUrl)" @click="saveRoute">{{ saving ? '保存中…' : '保存路由' }} <b>→</b></button></footer></section></div>

    <div v-if="maintenanceDialog" class="modal-backdrop" @click.self="maintenanceDialog = false"><section class="server-dialog routing-dialog maintenance-dialog" role="dialog" aria-modal="true"><header><div><span>PLANNED MAINTENANCE</span><h2>安排维护窗口</h2></div><button @click="maintenanceDialog = false">×</button></header><div class="dialog-body application-form">
      <div class="form-grid"><label><span>维护名称 Name</span><input v-model.trim="maintenanceForm.name" maxlength="120" /></label><label><span>服务器范围 Scope</span><select v-model="maintenanceForm.serverId"><option :value="null">全部服务器 All</option><option v-for="server in servers.servers" :key="server.id" :value="server.id">{{ server.name }}</option></select></label></div>
      <label><span>原因 Reason</span><textarea v-model.trim="maintenanceForm.reason" maxlength="500" rows="3" placeholder="系统升级、迁移、计划重启…" /></label>
      <div class="form-grid"><label><span>开始时间</span><input v-model="maintenanceForm.startsAt" type="datetime-local" /></label><label><span>结束时间</span><input v-model="maintenanceForm.endsAt" type="datetime-local" /></label></div>
      <p class="maintenance-note">时间按浏览器本地时区录入。窗口内告警仍会记录；勾选“Critical 绕过”的路由仍会发送严重告警。</p><p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    </div><footer><button @click="maintenanceDialog = false">取消</button><button class="dialog-primary" :disabled="saving || !maintenanceForm.name || !maintenanceForm.startsAt || !maintenanceForm.endsAt" @click="saveMaintenance">{{ saving ? '保存中…' : '安排维护' }} <b>→</b></button></footer></section></div>
  </section>
</template>

<style scoped>
.routing-view{display:grid;gap:20px;padding-bottom:40px}.routing-heading{align-items:flex-end}.routing-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.routing-summary article{position:relative;overflow:hidden;padding:20px 22px;border:1px solid #dce8e3;border-radius:18px;background:linear-gradient(145deg,#fff,#f5fbf8);box-shadow:0 8px 24px rgba(53,91,75,.05)}.routing-summary article:after{content:"";position:absolute;width:92px;height:92px;border-radius:50%;right:-30px;top:-32px;background:#dff5ea}.routing-summary article.quiet:after{background:#fff0cb}.routing-summary article.maintenance:after{background:#ffe0d9}.routing-summary span{display:block;color:#718078;font-size:11px;font-weight:800;letter-spacing:.11em}.routing-summary strong{display:block;margin:7px 0 1px;color:#20372d;font-size:31px;line-height:1}.routing-summary small{color:#76847d}.routing-principle{display:flex;gap:13px;align-items:flex-start;padding:16px 18px;border:1px solid #d5e8df;border-radius:16px;background:#f4fbf7;color:#466257}.routing-principle>span{display:grid;place-items:center;flex:0 0 25px;height:25px;border-radius:50%;background:#caecdc;color:#19633f;font-weight:900}.routing-principle strong{color:#23483a}.routing-principle p{margin:4px 0 0;font-size:13px;line-height:1.6}.routing-panel{padding:22px;border:1px solid #dfe8e4;border-radius:20px;background:#fff;box-shadow:0 10px 32px rgba(55,79,68,.055)}.routing-panel>header{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:18px}.routing-panel h2{margin:3px 0 4px;color:#1f332a;font-size:20px}.routing-panel header span{color:#79867f;font-size:13px}.route-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.route-card{padding:18px;border:1px solid #dce7e2;border-radius:17px;background:linear-gradient(155deg,#fff,#f8fbfa);transition:.2s ease}.route-card:hover{border-color:#bad8ca;transform:translateY(-1px)}.route-card.disabled{opacity:.66;background:#f7f8f8}.route-card.muted{border-color:#ead9ad;background:linear-gradient(155deg,#fffdf8,#fff9e9)}.route-card>header{display:flex;justify-content:space-between;gap:12px}.route-destination{display:flex;gap:11px;align-items:center}.route-destination>span{display:grid;place-items:center;width:38px;height:38px;border-radius:12px;background:#def3e9;color:#216245;font-size:12px;font-weight:900}.route-destination strong,.route-destination small{display:block}.route-destination strong{color:#24382f}.route-destination small{margin-top:3px;color:#7c8983;font-size:12px}.route-state{display:flex;align-items:center;gap:6px;height:25px;padding:0 9px;border-radius:999px;background:#e9f7ef;color:#28714e;font-size:10px;font-weight:900;letter-spacing:.08em}.route-state i{width:6px;height:6px;border-radius:50%;background:currentColor}.muted .route-state{background:#fff0c9;color:#8a6415}.disabled .route-state{background:#ecefef;color:#74807a}.route-card dl{display:grid;gap:0;margin:17px 0 13px;border-top:1px solid #edf1ef}.route-card dl div{display:flex;justify-content:space-between;gap:14px;padding:9px 0;border-bottom:1px solid #edf1ef;font-size:12px}.route-card dt{color:#7a8781}.route-card dd{margin:0;color:#344d42;text-align:right;font-weight:650}.route-card footer{display:flex;justify-content:flex-end;gap:7px}.route-card footer button,.window-list button{padding:7px 10px;border:1px solid #d9e2de;border-radius:9px;background:#fff;color:#52635b;font-size:12px}.route-card footer .danger{color:#a45252}.route-empty{display:grid;justify-items:center;padding:38px 20px;border:1px dashed #cfddd7;border-radius:16px;background:#f9fcfb;text-align:center}.route-empty>span{font-size:28px;color:#69a488}.route-empty strong{margin-top:7px;color:#2b4539}.route-empty p{max-width:650px;margin:6px 0 14px;color:#75837c;font-size:13px}.route-empty button,.maintenance-empty button{border:0;border-radius:10px;padding:9px 13px;background:#e2f3ea;color:#286448;font-weight:750}.maintenance-panel{background:linear-gradient(180deg,#fff,#fbfdfc)}.maintenance-empty{display:flex;align-items:center;justify-content:space-between;padding:17px;border:1px dashed #d6e1dc;border-radius:14px;color:#748179}.window-list{display:grid;gap:9px}.window-list article{display:grid;grid-template-columns:78px minmax(160px,1fr) minmax(320px,auto) auto;gap:14px;align-items:center;padding:13px 14px;border:1px solid #e0e7e4;border-radius:13px}.window-list article.active{border-color:#e7cfa0;background:#fffaf0}.window-status{justify-self:start;padding:5px 8px;border-radius:999px;background:#eef2f0;color:#66756e;font-size:10px;font-weight:900}.active .window-status{background:#ffedbe;color:#805d12}.upcoming .window-status{background:#e5f3ec;color:#2f6a50}.window-list strong{display:block;color:#30443b}.window-list p{margin:3px 0 0;color:#7a8781;font-size:12px}.window-list time{display:flex;align-items:center;gap:9px;color:#5d6d65;font-size:12px}.window-list time b{color:#9aa7a1}.routing-dialog{width:min(700px,calc(100vw - 32px))}.route-toggles{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.route-toggles label{display:flex;gap:8px;align-items:center;padding:10px;border:1px solid #e0e8e4;border-radius:11px;background:#f9fbfa;font-size:12px}.route-toggles input,.quiet-switch input{width:auto}.quiet-config{padding:14px;border:1px solid #dce7e2;border-radius:14px;background:#f8fbf9}.quiet-switch{display:flex!important;flex-direction:row!important;align-items:center;gap:10px}.quiet-switch span,.quiet-switch strong,.quiet-switch small{display:block}.quiet-switch small{margin-top:3px;color:#7b8882}.quiet-config .form-grid{margin-top:13px}.day-picker{display:flex;align-items:center;gap:7px;margin-top:12px}.day-picker>span{margin-right:5px;color:#6f7f77;font-size:12px}.day-picker label{display:block}.day-picker input{position:absolute;opacity:0;pointer-events:none}.day-picker b{display:grid;place-items:center;width:31px;height:31px;border:1px solid #d9e3de;border-radius:9px;background:#fff;color:#65766d;font-size:12px}.day-picker input:checked+b{border-color:#75b997;background:#dff3e9;color:#1f6544}.maintenance-note{margin:0;padding:11px 12px;border-radius:11px;background:#f2f7f4;color:#66766e;font-size:12px;line-height:1.55}.application-form textarea{resize:vertical}@media(max-width:900px){.route-grid{grid-template-columns:1fr}.window-list article{grid-template-columns:75px 1fr}.window-list time{grid-column:2}.window-list button{grid-column:2;justify-self:end}.route-toggles{grid-template-columns:1fr}}@media(max-width:640px){.routing-summary{grid-template-columns:1fr}.routing-panel{padding:17px}.routing-panel>header{align-items:flex-start;flex-direction:column}.route-card dl div{align-items:flex-start;flex-direction:column;gap:3px}.route-card dd{text-align:left}.window-list article{grid-template-columns:1fr}.window-list time,.window-list button{grid-column:1}.window-list time{align-items:flex-start;flex-direction:column;gap:2px}.day-picker{flex-wrap:wrap}}
</style>
