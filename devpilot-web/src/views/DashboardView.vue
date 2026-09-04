<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import BaseChart from '@/components/BaseChart.vue'
import { dashboardApi, type DashboardData, type DashboardRange } from '@/api/dashboard'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const auth = useAuthStore()
const theme = useThemeStore()
const range = ref<DashboardRange>('1h')
const data = ref<DashboardData>()
const loading = ref(false)
const errorMessage = ref('')
let pollTimer: number | undefined

const summary = computed(() => data.value?.summary ?? {
  serverTotal: 0, serverOnline: 0, containerTotal: 0, containerRunning: 0,
  applicationTotal: 0, applicationUnhealthy: 0, currentAlerts: 0, todayDeployments: 0,
  storageWarnings: 0, storageCritical: 0,
})
const cards = computed(() => [
  { label: '服务器 Servers', value: summary.value.serverTotal, detail: `${summary.value.serverOnline} 台在线`, tone: 'blue' },
  { label: '容器 Docker', value: summary.value.containerTotal, detail: `${summary.value.containerRunning} 个运行中`, tone: 'violet' },
  { label: '应用 Applications', value: summary.value.applicationTotal, detail: `${summary.value.applicationUnhealthy} 个异常`, tone: 'cyan' },
  { label: '待处理 Alerts', value: summary.value.currentAlerts, detail: `今日 ${summary.value.todayDeployments} 次发布`, tone: 'green' },
])
const actionItems = computed(() => {
  const offlineServers = Math.max(0, summary.value.serverTotal - summary.value.serverOnline)
  const stoppedContainers = Math.max(0, summary.value.containerTotal - summary.value.containerRunning)
  return [
    summary.value.serverTotal === 0
      ? { tone: 'setup', mark: '1', label: '连接第一台服务器', detail: '安装 Agent 后自动回传资源与容器', action: '开始连接', to: '/servers' }
      : offlineServers > 0
        ? { tone: 'attention', mark: '!', label: `${offlineServers} 台服务器离线`, detail: '检查 Agent、网络和心跳状态', action: '立即检查', to: '/servers' }
        : { tone: 'done', mark: '✓', label: '服务器连接正常', detail: `${summary.value.serverOnline} 台 Agent 正在回传数据`, action: '查看', to: '/servers' },
    summary.value.containerTotal === 0
      ? { tone: 'setup', mark: '2', label: '等待发现 Docker 容器', detail: 'Agent 会自动建立运行清单', action: '查看清单', to: '/docker' }
      : stoppedContainers > 0
        ? { tone: 'attention', mark: '!', label: `${stoppedContainers} 个容器未运行`, detail: '确认是否为预期停止状态', action: '检查容器', to: '/docker' }
        : { tone: 'done', mark: '✓', label: 'Docker 负载运行正常', detail: `${summary.value.containerRunning} 个容器正在运行`, action: '查看', to: '/docker' },
    summary.value.applicationTotal === 0
      ? { tone: 'setup', mark: '3', label: '纳管你的第一个应用', detail: '从已发现容器一键导入', action: '自动发现', to: '/applications' }
      : summary.value.applicationUnhealthy > 0
        ? { tone: 'attention', mark: '!', label: `${summary.value.applicationUnhealthy} 个应用需要处理`, detail: '运行状态或健康检查异常', action: '定位问题', to: '/applications' }
        : { tone: 'done', mark: '✓', label: '应用健康检查正常', detail: `${summary.value.applicationTotal} 个服务已纳管`, action: '查看', to: '/applications' },
    summary.value.currentAlerts > 0
      ? { tone: 'attention', mark: '!', label: `${summary.value.currentAlerts} 条活动告警`, detail: '请确认影响并处理或确认告警', action: '进入告警', to: '/alerts' }
      : { tone: 'done', mark: '✓', label: '当前没有活动告警', detail: '所有已配置指标均在阈值内', action: '告警策略', to: '/alerts/rules' },
    summary.value.storageCritical > 0
      ? { tone: 'attention', mark: '!', label: `${summary.value.storageCritical} 台服务器磁盘高危`, detail: '磁盘达到 90%；95% 后新发布会自动排队', action: '安全清理', to: '/monitor' }
      : summary.value.storageWarnings > 0
        ? { tone: 'attention', mark: '!', label: `${summary.value.storageWarnings} 台服务器磁盘预警`, detail: '磁盘达到 80%，建议现在检查增长来源', action: '查看建议', to: '/monitor' }
        : { tone: 'done', mark: '✓', label: '磁盘容量处于安全水位', detail: '80% 预警 · 95% 暂停新发布', action: '容量详情', to: '/monitor' },
  ]
})

function timestamp(value: string) {
  const parsed = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`)
  return parsed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const chartOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 450,
  color: ['#3b82f6', '#8b5cf6'],
  tooltip: { trigger: 'axis', backgroundColor: theme.mode === 'light' ? '#ffffff' : '#101828', borderColor: 'rgba(148,163,184,.22)', textStyle: { color: theme.mode === 'light' ? '#18243a' : '#e5edf9', fontSize: 10 } },
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
      <div><p class="eyebrow">运维总览 · WORKSPACE OVERVIEW</p><h1>今天需要关注什么？</h1><span>服务器、应用、发布与告警，一屏掌握。</span></div>
      <RouterLink class="add-server-preview" to="/servers"><span>{{ auth.hasAnyRole(['ADMIN']) ? '＋' : '↗' }}</span>{{ auth.hasAnyRole(['ADMIN']) ? '连接服务器' : '查看服务器' }} <small>{{ auth.hasAnyRole(['ADMIN']) ? 'Connect Agent' : 'Infrastructure' }}</small></RouterLink>
    </header>

    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <div class="summary-grid" :class="{ 'is-loading': loading && !data }">
      <article v-for="item in cards" :key="item.label" :class="`tone-${item.tone}`">
        <div><span>{{ item.label }}</span><i /></div><strong>{{ item.value }}</strong><small>{{ item.detail }}</small>
      </article>
    </div>

    <div class="dashboard-grid">
      <article class="empty-panel metrics-preview live-metrics-panel">
        <header><div><strong>基础设施脉搏 Infrastructure pulse</strong><small>所有在线服务器的平均资源使用率</small></div><div class="range-switch"><button v-for="item in (['1h','6h','24h'] as DashboardRange[])" :key="item" :class="{ active: range === item }" @click="changeRange(item)">{{ item }}</button></div></header>
        <div v-if="data?.trend.length" class="dashboard-chart"><BaseChart :option="chartOption" height="250px" /></div>
        <div v-else class="metric-empty"><span>⌁</span><strong>等待监控数据 Waiting for telemetry</strong><small>Agent 上传第一批指标后，这里会自动出现趋势图。</small></div>
        <footer><span><i class="blue" />CPU</span><span><i class="violet" />Memory</span><small>{{ data?.trend.length || 0 }} aggregate points</small></footer>
      </article>

      <article class="empty-panel readiness-panel">
        <header><div><strong>行动中心 Action center</strong><small>按优先级给出下一步操作</small></div><span class="ready-pill">LIVE</span></header>
        <ol>
          <li v-for="item in actionItems" :key="item.label" :class="item.tone"><b>{{ item.mark }}</b><div><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></div><RouterLink :to="item.to">{{ item.action }} →</RouterLink></li>
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
