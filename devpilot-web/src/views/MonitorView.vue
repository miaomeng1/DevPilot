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
let pollTimer: number | undefined

const summary = computed(() => data.value?.summary ?? {
  serverTotal: 0, serverOnline: 0, reportingServers: 0, averageCpuUsage: 0,
  averageMemoryUsage: 0, averageDiskUsage: 0, networkUploadRate: 0, networkDownloadRate: 0,
})

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

onMounted(() => { void load(); pollTimer = window.setInterval(() => void load(true), 15_000) })
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="monitor-view">
    <header class="page-heading monitor-heading"><div><p class="eyebrow">OBSERVABILITY</p><h1>Monitor center</h1><span>Fleet-wide utilization, throughput, and Agent freshness in one operational view.</span></div><button class="primary-compact" :disabled="loading" @click="load()">{{ loading ? 'Refreshing…' : '↻ Refresh' }}</button></header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="monitor-summary">
      <article><span>Fleet availability</span><strong>{{ summary.serverOnline }} / {{ summary.serverTotal }}</strong><small>{{ summary.reportingServers }} reporting telemetry</small></article>
      <article><span>Average CPU</span><strong>{{ summary.averageCpuUsage.toFixed(1) }}%</strong><small>Across reporting servers</small></article>
      <article><span>Average memory</span><strong>{{ summary.averageMemoryUsage.toFixed(1) }}%</strong><small>Across reporting servers</small></article>
      <article><span>Average disk</span><strong>{{ summary.averageDiskUsage.toFixed(1) }}%</strong><small>Root filesystems</small></article>
      <article><span>Network throughput</span><strong>{{ bytes(summary.networkDownloadRate, true) }}</strong><small>↑ {{ bytes(summary.networkUploadRate, true) }} aggregate</small></article>
    </div>

    <article class="detail-panel monitor-trend">
      <header><div><strong>Fleet utilization trend</strong><small>Average CPU, memory, and disk across the control plane</small></div><div class="range-switch"><button v-for="item in (['1h','6h','24h','7d'] as MetricRange[])" :key="item" :class="{ active: range === item }" @click="changeRange(item)">{{ item }}</button></div></header>
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
