<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { capacityApi, type CapacityPlan } from '@/api/capacity'
import { apiErrorMessage } from '@/api/client'

const GIB = 1024 ** 3
const memoryGiB = ref(1)
const diskGiB = ref(5)
const plan = ref<CapacityPlan | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const profiles = [
  { id: 'light', name: '轻量 Web', detail: '小型网站、机器人、监控', memory: .5, disk: 2 },
  { id: 'standard', name: '标准服务', detail: 'API、面板、个人应用', memory: 1, disk: 5 },
  { id: 'data', name: '数据服务', detail: 'Git、数据库、媒体服务', memory: 2, disk: 10 },
]

const verdict = computed(() => {
  const state = plan.value?.verdict
  if (state === 'SAFE') return { title: '可以部署', detail: '至少一台服务器保有充足安全余量。', tone: 'safe' }
  if (state === 'CAUTION') return { title: '可以，但需关注', detail: '有可用节点，但余量或当前负载不够宽松。', tone: 'caution' }
  if (state === 'TIGHT') return { title: '资源偏紧', detail: '建议先释放资源或降低工作负载预估。', tone: 'tight' }
  return { title: '暂不建议部署', detail: '没有节点通过最低安全门槛。', tone: 'blocked' }
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try { plan.value = await capacityApi.plan(Math.round(memoryGiB.value * GIB), Math.round(diskGiB.value * GIB)) }
  catch (error) { errorMessage.value = apiErrorMessage(error, '容量建议计算失败') }
  finally { loading.value = false }
}

function chooseProfile(memory: number, disk: number) {
  memoryGiB.value = memory
  diskGiB.value = disk
  void load()
}

function bytes(value: string | null) {
  if (value === null) return '—'
  const amount = Math.max(0, Number(value))
  if (!Number.isFinite(amount)) return '—'
  return amount >= GIB ? `${(amount / GIB).toFixed(1)} GiB` : `${Math.round(amount / 1024 ** 2)} MiB`
}

function pct(value: number | null) {
  return value === null ? '—' : `${value.toFixed(1)}%`
}

function metricAge(value: string | null) {
  if (!value) return '无指标'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(`${value}Z`).getTime()) / 1000))
  return seconds < 60 ? `${seconds}s 前` : `${Math.floor(seconds / 60)}m 前`
}

onMounted(load)
</script>

<template>
  <section class="capacity-view">
    <header class="page-heading capacity-heading"><div><p class="eyebrow">PLACEMENT ADVISOR</p><h1>容量与部署建议</h1><span>部署前先回答：哪台服务器合适，以及部署后还剩多少安全余量。</span></div><RouterLink class="secondary-compact" to="/servers">返回服务器 Servers</RouterLink></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <section class="capacity-composer">
      <div class="composer-copy"><span>01 · WORKLOAD SIZE</span><h2>估算新服务需要的资源</h2><p>不用追求绝对精确，填入稳定运行时的内存和预计 30 天磁盘增长。评分不会自动执行部署。</p></div>
      <div class="profile-list"><button v-for="profile in profiles" :key="profile.id" :class="{ active: memoryGiB === profile.memory && diskGiB === profile.disk }" @click="chooseProfile(profile.memory, profile.disk)"><strong>{{ profile.name }}</strong><small>{{ profile.detail }}</small><b>{{ profile.memory }} GiB RAM · {{ profile.disk }} GiB Disk</b></button></div>
      <div class="capacity-inputs"><label><span>预计内存 Memory</span><div><input v-model.number="memoryGiB" type="number" min="0.125" max="64" step="0.125" /><b>GiB</b></div></label><label><span>预计磁盘 Disk</span><div><input v-model.number="diskGiB" type="number" min="0.25" max="2048" step="0.25" /><b>GiB</b></div></label><button :disabled="loading" @click="load">{{ loading ? '计算中…' : '重新计算 Analyze' }}</button></div>
    </section>

    <section v-if="plan" :class="['placement-verdict', verdict.tone]"><div class="verdict-icon"><span>{{ plan.verdict === 'SAFE' ? '✓' : '!' }}</span></div><div><p>{{ plan.verdict }} · PLACEMENT RESULT</p><h2>{{ verdict.title }}</h2><span>{{ plan.summary }} {{ verdict.detail }}</span></div><dl><div><dt>RAM 请求</dt><dd>{{ memoryGiB }} GiB</dd></div><div><dt>Disk 请求</dt><dd>{{ diskGiB }} GiB</dd></div><div><dt>候选节点</dt><dd>{{ plan.servers.filter((server) => server.eligible).length }} / {{ plan.servers.length }}</dd></div></dl></section>

    <section v-if="plan" class="capacity-results">
      <header><div><p class="eyebrow">02 · RANKED NODES</p><h2>服务器评分 Server ranking</h2><span>硬门槛不通过的节点不会因为其他指标优秀而被推荐。</span></div></header>
      <div v-if="!plan.servers.length" class="capacity-empty"><strong>还没有服务器</strong><span>先添加服务器并等待 Agent 上报指标与 Docker 状态。</span><RouterLink to="/servers">添加服务器 →</RouterLink></div>
      <div v-else class="capacity-grid"><article v-for="server in plan.servers" :key="server.serverId" :class="['capacity-card', server.grade.toLowerCase(), { recommended: server.recommended }]">
        <header><div><span v-if="server.recommended" class="recommended-tag">RECOMMENDED</span><strong>{{ server.serverName }}</strong><small>{{ server.hostname || 'Agent identity pending' }} · {{ server.architecture || 'arch unknown' }} · 指标 {{ metricAge(server.metricAt) }}</small></div><div class="score-ring" :style="{ '--score': `${server.score * 3.6}deg` }"><strong>{{ server.score }}</strong><small>/100</small></div></header>
        <div class="grade-line"><span>{{ server.grade }}</span><p>{{ server.eligible ? '通过最低安全门槛' : '当前不满足部署条件' }}</p></div>
        <dl class="resource-projection"><div><dt>内存</dt><dd><span>{{ pct(server.memoryUsage) }}</span><b>→</b><strong>{{ pct(server.projectedMemoryUsage) }}</strong><small>余 {{ bytes(server.memoryAvailableAfter) }}</small></dd></div><div><dt>磁盘</dt><dd><span>{{ pct(server.diskUsage) }}</span><b>→</b><strong>{{ pct(server.projectedDiskUsage) }}</strong><small>余 {{ bytes(server.diskFreeAfter) }}</small></dd></div><div><dt>CPU / Load</dt><dd><strong>{{ pct(server.cpuUsage) }}</strong><small>{{ server.loadPerCore?.toFixed(2) ?? '—' }} / core</small></dd></div><div><dt>运行密度</dt><dd><strong>{{ server.runningContainers }} containers</strong><small>{{ server.activeAlerts }} alerts · {{ server.criticalAlerts }} critical</small></dd></div></dl>
        <div v-if="server.blockers.length" class="capacity-findings blockers"><strong>阻断原因</strong><ul><li v-for="item in server.blockers" :key="item">{{ item }}</li></ul></div>
        <div v-if="server.observations.length" class="capacity-findings"><strong>需要关注</strong><ul><li v-for="item in server.observations" :key="item">{{ item }}</li></ul></div>
        <footer><RouterLink :to="`/servers/${server.serverId}`">查看服务器详情 <b>→</b></RouterLink></footer>
      </article></div>
    </section>

    <section class="scoring-notes"><div><p class="eyebrow">HOW IT WORKS</p><h2>可解释评分，不是黑盒调度</h2></div><ol><li><b>硬门槛</b><span>Agent 在线、Docker 可用、指标不超过 2 分钟；部署后至少保留 256 MiB 内存和 2 GiB 磁盘，磁盘不能达到 95%。</span></li><li><b>加权评分</b><span>内存余量 35%、磁盘余量 35%、CPU 15%、负载 10%、容器密度 5%，再扣除活动告警风险。</span></li><li><b>只做建议</b><span>DevPilot 不会自动迁移容器或改变部署目标；最终选择仍由你确认。</span></li></ol></section>
  </section>
</template>

<style scoped>
.capacity-view{display:grid;gap:20px;max-width:1480px;margin:0 auto;padding-bottom:42px}.capacity-heading{align-items:flex-end}.capacity-composer{display:grid;grid-template-columns:minmax(240px,.75fr) minmax(420px,1.35fr);gap:20px;padding:24px;border:1px solid #dfe9e4;border-radius:21px;background:linear-gradient(135deg,#fff,#f3fbf7);box-shadow:0 12px 32px rgba(50,88,71,.06)}.composer-copy>span{color:#6d9c85;font-size:10px;font-weight:850;letter-spacing:.12em}.composer-copy h2{margin:8px 0;color:#233b30;font-size:21px}.composer-copy p{margin:0;color:#6f8077;font-size:13px;line-height:1.65}.profile-list{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}.profile-list button{display:grid;gap:5px;text-align:left;padding:14px;border:1px solid #dce7e1;border-radius:13px;background:#fff;color:#314a3f}.profile-list button:hover,.profile-list button.active{border-color:#78b99b;background:#ebf8f1}.profile-list strong{font-size:13px}.profile-list small{min-height:30px;color:#78877f;font-size:11px;line-height:1.4}.profile-list b{color:#3d7d5d;font-size:10px}.capacity-inputs{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr auto;gap:12px;align-items:end;padding-top:18px;border-top:1px solid #e4ece8}.capacity-inputs label{display:grid;gap:7px;color:#667970;font-size:11px;font-weight:750}.capacity-inputs label>div{display:flex;align-items:center;border:1px solid #d9e4df;border-radius:11px;background:#fff}.capacity-inputs input{min-width:0;width:100%;height:42px;border:0;outline:0;padding:0 12px;background:transparent;color:#253b31}.capacity-inputs label b{padding-right:12px;color:#85918b;font-size:11px}.capacity-inputs>button{height:44px;border:0;border-radius:11px;padding:0 22px;background:#2b7b56;color:#fff;font-size:12px;font-weight:800}.placement-verdict{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:16px;align-items:center;padding:19px 22px;border:1px solid #cfe6da;border-radius:18px;background:#f2fbf6}.placement-verdict.caution,.placement-verdict.tight{border-color:#ead9ad;background:#fff9ea}.placement-verdict.blocked{border-color:#edcece;background:#fff4f4}.verdict-icon span{display:grid;place-items:center;width:43px;height:43px;border-radius:14px;background:#cfeedd;color:#206944;font-size:20px;font-weight:900}.caution .verdict-icon span,.tight .verdict-icon span{background:#ffedbe;color:#896619}.blocked .verdict-icon span{background:#f7dada;color:#a84b4b}.placement-verdict p{margin:0;color:#5e9278;font-size:9px;font-weight:850;letter-spacing:.1em}.placement-verdict h2{margin:4px 0 2px;color:#223b30;font-size:19px}.placement-verdict>div>span{color:#6c7e74;font-size:12px}.placement-verdict dl{display:flex;gap:20px;margin:0}.placement-verdict dl div{display:grid;gap:3px}.placement-verdict dt{color:#87928d;font-size:9px}.placement-verdict dd{margin:0;color:#354d42;font-size:12px;font-weight:800}.capacity-results{display:grid;gap:14px}.capacity-results>header h2,.scoring-notes h2{margin:5px 0;color:#243a30;font-size:20px}.capacity-results>header span{color:#77877f;font-size:12px}.capacity-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.capacity-card{padding:20px;border:1px solid #dfe7e3;border-radius:19px;background:#fff;box-shadow:0 9px 27px rgba(55,81,69,.05)}.capacity-card.recommended{border-color:#82bfa2;box-shadow:0 12px 32px rgba(43,123,86,.12)}.capacity-card.blocked{background:#fbfcfc}.capacity-card>header{display:flex;justify-content:space-between;gap:12px}.capacity-card>header>div:first-child{display:grid;align-content:start;gap:4px}.capacity-card>header strong{color:#243c31;font-size:16px}.capacity-card>header small{color:#7c8a83;font-size:10px}.recommended-tag{width:max-content;margin-bottom:3px;padding:4px 7px;border-radius:999px;background:#dff3e9;color:#24704d;font-size:8px;font-weight:900;letter-spacing:.09em}.score-ring{--score:0deg;display:grid;place-content:center;width:62px;height:62px;flex:0 0 62px;border-radius:50%;background:radial-gradient(circle at center,#fff 56%,transparent 58%),conic-gradient(#58a77f var(--score),#e7eeeb 0);text-align:center}.score-ring strong{font-size:17px!important;line-height:1}.score-ring small{font-size:8px!important}.grade-line{display:flex;align-items:center;gap:8px;margin:14px 0;padding:8px 10px;border-radius:10px;background:#f3f8f5}.grade-line span{color:#387858;font-size:9px;font-weight:900;letter-spacing:.08em}.grade-line p{margin:0;color:#6f8177;font-size:10px}.blocked .grade-line span{color:#a35555}.resource-projection{display:grid;grid-template-columns:repeat(2,1fr);margin:0;border:1px solid #e5ebe8;border-radius:13px;overflow:hidden}.resource-projection>div{padding:11px;border-right:1px solid #e5ebe8;border-bottom:1px solid #e5ebe8}.resource-projection>div:nth-child(2n){border-right:0}.resource-projection>div:nth-last-child(-n+2){border-bottom:0}.resource-projection dt{margin-bottom:7px;color:#849089;font-size:9px}.resource-projection dd{display:flex;align-items:center;gap:6px;margin:0;color:#6a7972;font-size:10px}.resource-projection dd strong{font-size:11px}.resource-projection dd b{color:#a2aca7}.resource-projection dd small{margin-left:auto;color:#7d8b84;font-size:9px}.capacity-findings{margin-top:11px;padding:11px 12px;border-radius:11px;background:#fff8e9}.capacity-findings.blockers{background:#fff0f0}.capacity-findings>strong{color:#7e651f;font-size:10px}.capacity-findings.blockers>strong{color:#9a4b4b}.capacity-findings ul{display:grid;gap:4px;margin:7px 0 0;padding-left:16px;color:#6d766e;font-size:10px}.capacity-card footer{display:flex;justify-content:flex-end;margin-top:13px}.capacity-card footer a{color:#347757;font-size:11px;font-weight:750;text-decoration:none}.capacity-empty{display:grid;justify-items:center;gap:7px;padding:38px;border:1px dashed #d4e0da;border-radius:17px;color:#728178}.capacity-empty strong{color:#344d41}.capacity-empty a{color:#287550;text-decoration:none}.scoring-notes{display:grid;grid-template-columns:240px 1fr;gap:24px;padding:22px;border:1px solid #e2e9e5;border-radius:18px;background:#f9fbfa}.scoring-notes ol{display:grid;gap:9px;margin:0;padding:0;list-style:none}.scoring-notes li{display:grid;grid-template-columns:90px 1fr;gap:12px;padding-bottom:9px;border-bottom:1px solid #e5ece8}.scoring-notes li:last-child{border:0}.scoring-notes li b{color:#365d49;font-size:11px}.scoring-notes li span{color:#708078;font-size:11px;line-height:1.55}@media(max-width:950px){.capacity-composer{grid-template-columns:1fr}.profile-list{grid-template-columns:1fr}.capacity-inputs{grid-column:auto}.capacity-grid{grid-template-columns:1fr}.placement-verdict{grid-template-columns:auto 1fr}.placement-verdict dl{grid-column:1/-1}.scoring-notes{grid-template-columns:1fr}}@media(max-width:640px){.capacity-inputs{grid-template-columns:1fr}.placement-verdict{grid-template-columns:1fr}.placement-verdict dl{flex-wrap:wrap}.resource-projection{grid-template-columns:1fr}.resource-projection>div{border-right:0}.resource-projection>div:nth-last-child(-n+2){border-bottom:1px solid #e5ebe8}.resource-projection>div:last-child{border-bottom:0}.scoring-notes li{grid-template-columns:1fr}}
</style>
