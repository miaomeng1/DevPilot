<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import BaseChart from '@/components/BaseChart.vue'
import { dashboardApi, type DashboardData, type DashboardRange } from '@/api/dashboard'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const range = ref<DashboardRange>('1h')
const data = ref<DashboardData>()
const loading = ref(false)
const errorMessage = ref('')
let pollTimer: number | undefined

const summary = computed(() => data.value?.summary ?? {
  serverTotal: 0, serverOnline: 0, containerTotal: 0, containerRunning: 0,
  applicationTotal: 0, applicationUnhealthy: 0, currentAlerts: 0, todayDeployments: 0,
})
const cards = computed(() => [
  { label: 'Servers', value: summary.value.serverTotal, detail: `${summary.value.serverOnline} online`, tone: 'blue' },
  { label: 'Docker', value: summary.value.containerTotal, detail: `${summary.value.containerRunning} running`, tone: 'violet' },
  { label: 'Applications', value: summary.value.applicationTotal, detail: `${summary.value.applicationUnhealthy} abnormal`, tone: 'cyan' },
  { label: 'Open alerts', value: summary.value.currentAlerts, detail: `${summary.value.todayDeployments} deployments today`, tone: 'green' },
])

function timestamp(value: string) {
  const parsed = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`)
  return parsed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const chartOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 450,
  color: ['#3b82f6', '#8b5cf6'],
  tooltip: { trigger: 'axis', backgroundColor: '#101828', borderColor: 'rgba(148,163,184,.22)', textStyle: { color: '#e5edf9', fontSize: 10 } },
  legend: { right: 8, top: 0, textStyle: { color: '#718096', fontSize: 9 }, itemWidth: 9, itemHeight: 5 },
  grid: { left: 42, right: 18, top: 42, bottom: 27 },
  xAxis: { type: 'category', boundaryGap: false, data: data.value?.trend.map((point) => timestamp(point.timestamp)) ?? [], axisLine: { lineStyle: { color: 'rgba(148,163,184,.13)' } }, axisLabel: { color: '#617087', fontSize: 8 }, axisTick: { show: false } },
  yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#617087', fontSize: 8, formatter: '{value}%' }, splitLine: { lineStyle: { color: 'rgba(148,163,184,.09)' } } },
  series: [
    { name: 'CPU', type: 'line', smooth: true, showSymbol: (data.value?.trend.length ?? 0) < 2, symbolSize: 6, data: data.value?.trend.map((point) => point.cpuUsage) ?? [], lineStyle: { width: 2 }, areaStyle: { opacity: .07 } },
    { name: 'Memory', type: 'line', smooth: true, showSymbol: (data.value?.trend.length ?? 0) < 2, symbolSize: 6, data: data.value?.trend.map((point) => point.memoryUsage) ?? [], lineStyle: { width: 2 }, areaStyle: { opacity: .045 } },
  ],
}))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    data.value = await dashboardApi.get(range.value)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, 'Dashboard 数据加载失败')
  } finally {
    loading.value = false
  }
}

function changeRange(value: DashboardRange) {
  range.value = value
  void load()
}

function formatTime(value: string | null | undefined) {
  if (!value) return 'Never'
  return new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`).toLocaleString()
}

function applicationTone(status: string, health: string) {
  if (status === 'ERROR' || health === 'UNHEALTHY') return 'error'
  if (status === 'WARNING' || health === 'UNKNOWN') return 'warning'
  if (status === 'RUNNING') return 'running'
  return 'offline'
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(true), 30_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="dashboard-view">
    <header class="page-heading">
      <div><p class="eyebrow">WORKSPACE OVERVIEW</p><h1>Infrastructure at a glance.</h1><span>Live control-plane health and resource telemetry.</span></div>
      <RouterLink class="add-server-preview" to="/servers"><span>{{ auth.hasAnyRole(['ADMIN']) ? '＋' : '↗' }}</span>{{ auth.hasAnyRole(['ADMIN']) ? 'Add server' : 'View servers' }} <small>{{ auth.hasAnyRole(['ADMIN']) ? 'Connect Agent' : 'Infrastructure' }}</small></RouterLink>
    </header>

    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <div class="summary-grid" :class="{ 'is-loading': loading && !data }">
      <article v-for="item in cards" :key="item.label" :class="`tone-${item.tone}`">
        <div><span>{{ item.label }}</span><i /></div><strong>{{ item.value }}</strong><small>{{ item.detail }}</small>
      </article>
    </div>

    <div class="dashboard-grid">
      <article class="empty-panel metrics-preview live-metrics-panel">
        <header><div><strong>Infrastructure pulse</strong><small>Average usage across connected servers</small></div><div class="range-switch"><button v-for="item in (['1h','6h','24h'] as DashboardRange[])" :key="item" :class="{ active: range === item }" @click="changeRange(item)">{{ item }}</button></div></header>
        <div v-if="data?.trend.length" class="dashboard-chart"><BaseChart :option="chartOption" height="250px" /></div>
        <div v-else class="metric-empty"><span>⌁</span><strong>Waiting for telemetry</strong><small>Charts appear after a connected Agent uploads its first metric sample.</small></div>
        <footer><span><i class="blue" />CPU</span><span><i class="violet" />Memory</span><small>{{ data?.trend.length || 0 }} aggregate points</small></footer>
      </article>

      <article class="empty-panel readiness-panel">
        <header><div><strong>Platform signals</strong><small>Current operational inventory</small></div><span class="ready-pill">LIVE</span></header>
        <ol>
          <li :class="{ done: summary.serverOnline > 0 }"><b>{{ summary.serverOnline > 0 ? '✓' : '1' }}</b><div><strong>Connected infrastructure</strong><small>{{ summary.serverOnline }} of {{ summary.serverTotal }} servers online</small></div></li>
          <li :class="{ done: summary.containerRunning > 0 }"><b>{{ summary.containerRunning > 0 ? '✓' : '2' }}</b><div><strong>Docker workloads</strong><small>{{ summary.containerRunning }} of {{ summary.containerTotal }} containers running</small></div></li>
          <li :class="{ done: summary.applicationTotal > 0 }"><b>{{ summary.applicationTotal > 0 ? '✓' : '3' }}</b><div><strong>Managed applications</strong><small>{{ summary.applicationTotal }} applications, {{ summary.applicationUnhealthy }} abnormal</small></div></li>
          <li :class="{ done: summary.currentAlerts === 0 }"><b>{{ summary.currentAlerts === 0 ? '✓' : '4' }}</b><div><strong>Active alert state</strong><small>{{ summary.currentAlerts ? `${summary.currentAlerts} alerts need attention` : 'No active alerts' }}</small></div></li>
        </ol>
      </article>
    </div>

    <div class="dashboard-detail-grid">
      <article class="dashboard-resource-panel">
        <header><div><strong>Server resources</strong><small>Current utilization by node</small></div><RouterLink to="/monitor">Open monitor →</RouterLink></header>
        <div v-if="data?.serverResources.length" class="dashboard-resource-list">
          <RouterLink v-for="server in data.serverResources" :key="server.id" :to="`/servers/${server.id}`">
            <div class="resource-node"><span>{{ server.name.slice(0,2).toUpperCase() }}</span><div><strong>{{ server.name }}</strong><small>{{ server.status }} · {{ server.hostname || 'Inventory pending' }}</small></div></div>
            <div class="resource-values"><span><small>CPU</small><b>{{ server.current ? `${server.current.cpuUsage.toFixed(1)}%` : '—' }}</b></span><span><small>MEM</small><b>{{ server.current ? `${server.current.memoryUsage.toFixed(1)}%` : '—' }}</b></span><span><small>DISK</small><b>{{ server.current ? `${server.current.diskUsage.toFixed(1)}%` : '—' }}</b></span></div>
          </RouterLink>
        </div>
        <div v-else class="dashboard-small-empty"><span>⌁</span><div><strong>No server telemetry</strong><small>Connected Agents appear here automatically.</small></div></div>
      </article>

      <article class="dashboard-service-panel">
        <header><div><strong>Service status</strong><small>Application runtime and health</small></div><RouterLink to="/applications">Open catalog →</RouterLink></header>
        <div v-if="data?.serviceStatuses.length" class="dashboard-service-list">
          <RouterLink v-for="application in data.serviceStatuses" :key="application.id" :to="`/applications/${application.id}`">
            <i :class="applicationTone(application.status, application.healthStatus)" /><div><strong>{{ application.name }}</strong><small>{{ application.serverName }} · {{ application.environment }}</small></div><span :class="applicationTone(application.status, application.healthStatus)">{{ application.healthStatus === 'UNKNOWN' ? application.status : application.healthStatus }}</span>
          </RouterLink>
        </div>
        <div v-else class="dashboard-small-empty"><span>◇</span><div><strong>No managed applications</strong><small>Bind an application to a discovered Docker container.</small></div></div>
      </article>
    </div>

    <article class="dashboard-deployments">
      <header><div><strong>Recent deployments</strong><small>Latest release records across every application</small></div><RouterLink to="/applications">View applications →</RouterLink></header>
      <div v-if="data?.recentDeployments.length" class="server-table-wrap"><table class="server-table deployment-table"><thead><tr><th>Application</th><th>Version</th><th>Server</th><th>Image</th><th>Operator</th><th>Deployed</th><th>Result</th></tr></thead><tbody><tr v-for="deployment in data.recentDeployments" :key="deployment.id"><td><RouterLink class="deployment-app-link" :to="`/applications/${deployment.applicationId}`">{{ deployment.applicationName }}</RouterLink></td><td><code>{{ deployment.version }}</code></td><td>{{ deployment.serverName }}</td><td><small>{{ deployment.dockerImage || '—' }}</small></td><td>{{ deployment.operatorName }}</td><td>{{ formatTime(deployment.deployedAt) }}</td><td><span class="deployment-result" :class="deployment.result.toLowerCase()">{{ deployment.result }}</span></td></tr></tbody></table></div>
      <div v-else class="dashboard-small-empty"><span>↥</span><div><strong>No deployment records</strong><small>Release history will provide version, image, operator, server, and result.</small></div></div>
    </article>

    <article class="dashboard-alerts">
      <header><div><strong>Current alerts</strong><small>Highest-severity active incidents</small></div><RouterLink to="/alerts">Open alert center →</RouterLink></header>
      <div v-if="data?.alerts.length" class="dashboard-alert-list"><RouterLink v-for="alert in data.alerts" :key="alert.id" to="/alerts"><span :class="alert.severity.toLowerCase()">!</span><div><strong>{{ alert.ruleName }}</strong><small>{{ alert.message }}</small></div><aside><b>{{ alert.severity }}</b><small>{{ alert.serverName }}</small></aside></RouterLink></div>
      <div v-else class="dashboard-alert-clear"><span>✓</span><div><strong>No active alerts</strong><small>All configured conditions are currently within policy.</small></div></div>
    </article>
  </section>
</template>
