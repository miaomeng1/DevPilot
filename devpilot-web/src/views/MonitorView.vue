<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import BaseChart from '@/components/BaseChart.vue'
import { monitorApi, type MonitorData, type MonitorServer } from '@/api/monitor'
import type { MetricRange } from '@/api/metrics'
import { apiErrorMessage } from '@/api/client'

const range = ref<MetricRange>('1h')
const data = ref<MonitorData>()
const loading = ref(false)
const errorMessage = ref('')
const copiedCommand = ref('')
let pollTimer: number | undefined
let copyTimer: number | undefined

const summary = computed(() => data.value?.summary ?? {
  serverTotal: 0, serverOnline: 0, reportingServers: 0, averageCpuUsage: 0,
  averageMemoryUsage: 0, averageDiskUsage: 0, networkUploadRate: 0, networkDownloadRate: 0,
})

const gibibyte = 1024 ** 3
const storagePriority = { critical: 2, warning: 1, healthy: 0 } as const
const storageNodes = computed(() => (data.value?.servers || [])
  .filter((server) => server.current)
  .map((server) => {
    const usage = server.current?.diskUsage ?? 0
    const total = Number(server.current?.diskTotal || server.diskTotal || 0)
    const free = Number(server.current?.diskFree || 0)
    const lowFree = total >= 10 * gibibyte && free < 5 * gibibyte
    const warningFree = total >= 10 * gibibyte && free < 10 * gibibyte
    const state: keyof typeof storagePriority = usage >= 90 || lowFree
      ? 'critical' : usage >= 80 || warningFree ? 'warning' : 'healthy'
    return { server, usage, free, state }
  })
  .sort((left, right) => storagePriority[right.state] - storagePriority[left.state]
    || right.usage - left.usage))
const riskyStorageNodes = computed(() => storageNodes.value.filter((item) => item.state !== 'healthy').length)

const cleanupChecks = [
  { id: 'docker-df', title: '定位 Docker 占用', command: 'docker system df -v' },
  { id: 'host-du', title: '定位主机大目录', command: 'sudo du -xhd1 /var/lib/docker /var/log 2>/dev/null | sort -h' },
  { id: 'image-prune', title: '仅清理悬空镜像', command: 'docker image prune' },
  { id: 'journal', title: '保留 7 天系统日志', command: 'sudo journalctl --vacuum-time=7d' },
]

function chartTime(value: string) {
  const date = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`)
  return date.toLocaleString([], range.value === '7d'
    ? { month: 'short', day: 'numeric', hour: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' })
}

const chartOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 400,
  color: ['#3b82f6', '#8b5cf6', '#06b6d4'],
  tooltip: { trigger: 'axis', backgroundColor: '#101828', borderColor: 'rgba(148,163,184,.22)', textStyle: { color: '#e5edf9', fontSize: 10 } },
  legend: { right: 8, top: 0, textStyle: { color: '#718096', fontSize: 9 }, itemWidth: 9, itemHeight: 5 },
  grid: { left: 42, right: 18, top: 42, bottom: 27 },
  xAxis: { type: 'category', boundaryGap: false, data: data.value?.trend.map((point) => chartTime(point.timestamp)) ?? [], axisLine: { lineStyle: { color: 'rgba(148,163,184,.13)' } }, axisLabel: { color: '#617087', fontSize: 8 }, axisTick: { show: false } },
  yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#617087', fontSize: 8, formatter: '{value}%' }, splitLine: { lineStyle: { color: 'rgba(148,163,184,.09)' } } },
  series: [
    { name: 'CPU', type: 'line', smooth: true, showSymbol: (data.value?.trend.length ?? 0) < 2, symbolSize: 6, data: data.value?.trend.map((point) => point.cpuUsage) ?? [], lineStyle: { width: 2 }, areaStyle: { opacity: .06 } },
    { name: 'Memory', type: 'line', smooth: true, showSymbol: (data.value?.trend.length ?? 0) < 2, symbolSize: 6, data: data.value?.trend.map((point) => point.memoryUsage) ?? [], lineStyle: { width: 2 } },
    { name: 'Disk', type: 'line', smooth: true, showSymbol: (data.value?.trend.length ?? 0) < 2, symbolSize: 6, data: data.value?.trend.map((point) => point.diskUsage) ?? [], lineStyle: { width: 1.5 } },
  ],
}))

function bytes(value: number | string | null | undefined, rate = false) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let normalized = amount
  let index = 0
  while (normalized >= 1024 && index < units.length - 1) { normalized /= 1024; index += 1 }
  return `${normalized.toFixed(index ? 1 : 0)} ${units[index]}${rate ? '/s' : ''}`
}

function utilization(server: MonitorServer, key: 'cpuUsage' | 'memoryUsage' | 'diskUsage') {
  return server.current?.[key] ?? 0
}

function relativeTime(value: string | null) {
  if (!value) return 'Never'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(`${value}Z`).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  return `${Math.floor(seconds / 3600)}h ago`
}

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try { data.value = await monitorApi.get(range.value) }
  catch (error) { errorMessage.value = apiErrorMessage(error, '监控数据加载失败') }
  finally { loading.value = false }
}

function changeRange(value: MetricRange) { range.value = value; void load() }

async function copyCommand(id: string, command: string) {
  await navigator.clipboard.writeText(command)
  copiedCommand.value = id
  window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => { copiedCommand.value = '' }, 1800)
}

onMounted(() => { void load(); pollTimer = window.setInterval(() => void load(true), 15_000) })
onBeforeUnmount(() => { window.clearInterval(pollTimer); window.clearTimeout(copyTimer) })
</script>

<template>
  <section class="monitor-view">
    <header class="page-heading monitor-heading"><div><p class="eyebrow">可观测性 · OBSERVABILITY</p><h1>监控与容量中心</h1><span>统一查看资源利用率、吞吐、Agent 新鲜度与磁盘风险。</span></div><button class="primary-compact" :disabled="loading" @click="load()">{{ loading ? '刷新中…' : '↻ 刷新 Refresh' }}</button></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="monitor-summary">
      <article><span>服务器可用 Fleet</span><strong>{{ summary.serverOnline }} / {{ summary.serverTotal }}</strong><small>{{ summary.reportingServers }} 台正在上报指标</small></article>
      <article><span>平均 CPU</span><strong>{{ summary.averageCpuUsage.toFixed(1) }}%</strong><small>所有上报服务器</small></article>
      <article><span>平均内存 Memory</span><strong>{{ summary.averageMemoryUsage.toFixed(1) }}%</strong><small>所有上报服务器</small></article>
      <article><span>平均磁盘 Disk</span><strong>{{ summary.averageDiskUsage.toFixed(1) }}%</strong><small>根文件系统</small></article>
      <article><span>网络吞吐 Network</span><strong>{{ bytes(summary.networkDownloadRate, true) }}</strong><small>↑ {{ bytes(summary.networkUploadRate, true) }} 汇总</small></article>
    </div>

    <article class="storage-guard" :class="{ attention: riskyStorageNodes > 0 }">
      <header><div><span>STORAGE GUARD</span><strong>{{ riskyStorageNodes ? `${riskyStorageNodes} 台服务器需要释放空间` : '磁盘水位安全' }}</strong><small>80% 预警 · 90% 高危 · 95% 或低于 2 GiB 时新发布自动排队</small></div><b>{{ riskyStorageNodes ? 'ACTION NEEDED' : 'PROTECTED' }}</b></header>
      <div class="storage-guard-grid">
        <section class="storage-node-list">
          <RouterLink v-for="item in storageNodes" :key="item.server.id" :to="`/servers/${item.server.id}`" :class="item.state">
            <div><i /><span><strong>{{ item.server.name }}</strong><small>{{ item.server.hostname || item.server.ip || '等待主机信息' }}</small></span></div>
            <div class="storage-meter"><span><i :style="{ width: `${Math.min(item.usage, 100)}%` }" /></span><small>{{ item.usage.toFixed(1) }}% · {{ bytes(item.free) }} 可用</small></div>
          </RouterLink>
          <div v-if="!storageNodes.length" class="storage-no-data"><span>⌁</span><div><strong>等待磁盘指标</strong><small>Agent 上报后自动评估容量风险。</small></div></div>
        </section>
        <aside class="cleanup-playbook"><div class="cleanup-title"><strong>安全清理建议 Cleanup</strong><small>先定位再清理；DevPilot 不会自动删除数据。</small></div><ol><li v-for="check in cleanupChecks" :key="check.id"><div><strong>{{ check.title }}</strong><code>{{ check.command }}</code></div><button type="button" @click="copyCommand(check.id, check.command)">{{ copiedCommand === check.id ? '已复制' : '复制' }}</button></li></ol><p>不会推荐 <code>docker system prune -a --volumes</code>；该命令可能删除仍需保留的镜像和卷。</p></aside>
      </div>
    </article>

    <article class="detail-panel monitor-trend">
      <header><div><strong>资源趋势 Utilization trend</strong><small>控制面所有服务器 CPU、内存与磁盘平均值</small></div><div class="range-switch"><button v-for="item in (['1h','6h','24h','7d'] as MetricRange[])" :key="item" :class="{ active: range === item }" @click="changeRange(item)">{{ item }}</button></div></header>
      <BaseChart v-if="data?.trend.length" :option="chartOption" height="285px" />
      <div v-else class="metric-empty compact"><span>⌁</span><strong>Waiting for fleet telemetry</strong><small>Connect an Agent to populate the utilization timeline.</small></div>
    </article>

    <article class="monitor-table-panel">
      <header><div><strong>Server resources</strong><small>Latest raw sample with a MySQL aggregate fallback</small></div><span>{{ data?.servers.length || 0 }} nodes · refreshes every 15s</span></header>
      <div v-if="!data?.servers.length" class="table-empty"><span class="server-empty-glyph">⌁</span><strong>No monitored servers</strong><small>Add a server and install its Agent to begin collecting signals.</small></div>
      <div v-else class="server-table-wrap"><table class="server-table monitor-table"><thead><tr><th>Node</th><th>Status</th><th>CPU</th><th>Memory</th><th>Disk</th><th>Network</th><th>Heartbeat</th></tr></thead><tbody>
        <tr v-for="server in data.servers" :key="server.id">
          <td><RouterLink class="node-cell node-link" :to="`/servers/${server.id}`"><span>{{ server.name.slice(0,2).toUpperCase() }}</span><div><strong>{{ server.name }}</strong><small>{{ server.hostname || server.ip || 'Inventory pending' }}</small></div></RouterLink></td>
          <td><span class="status-badge" :class="server.status.toLowerCase()"><i />{{ server.status }}</span></td>
          <td><div class="util-cell"><strong>{{ server.current ? `${utilization(server,'cpuUsage').toFixed(1)}%` : '—' }}</strong><span><i :style="{ width: `${utilization(server,'cpuUsage')}%` }" /></span><small>{{ server.cpuCores || '—' }} cores</small></div></td>
          <td><div class="util-cell"><strong>{{ server.current ? `${utilization(server,'memoryUsage').toFixed(1)}%` : '—' }}</strong><span><i :style="{ width: `${utilization(server,'memoryUsage')}%` }" /></span><small>{{ bytes(server.current?.memoryUsed) }} / {{ bytes(server.memoryTotal) }}</small></div></td>
          <td><div class="util-cell"><strong>{{ server.current ? `${utilization(server,'diskUsage').toFixed(1)}%` : '—' }}</strong><span><i :style="{ width: `${utilization(server,'diskUsage')}%` }" /></span><small>{{ bytes(server.current?.diskFree) }} free</small></div></td>
          <td><strong class="cell-primary">↓ {{ bytes(server.current?.networkDownloadRate, true) }}</strong><small class="cell-secondary">↑ {{ bytes(server.current?.networkUploadRate, true) }}</small></td>
          <td><strong class="cell-primary">{{ relativeTime(server.lastHeartbeat) }}</strong><small class="cell-secondary">Agent freshness</small></td>
        </tr>
      </tbody></table></div>
    </article>
  </section>
</template>
