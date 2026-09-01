<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { EChartsCoreOption } from 'echarts/core'
import BaseChart from '@/components/BaseChart.vue'
import { serverApi, type ServerNode } from '@/api/servers'
import { metricApi, type MetricHistory, type MetricRange } from '@/api/metrics'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useServerStore } from '@/stores/servers'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const serverStore = useServerStore()
const serverId = computed(() => String(route.params.id))
const server = ref<ServerNode>()
const metrics = ref<MetricHistory>()
const range = ref<MetricRange>('1h')
const loading = ref(false)
const errorMessage = ref('')
const deleteOpen = ref(false)
const deleteName = ref('')
const deleting = ref(false)
let pollTimer: number | undefined

const current = computed(() => metrics.value?.current)

function chartTime(value: string) {
  const parsed = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`)
  return parsed.toLocaleString([], range.value === '7d'
    ? { month: 'short', day: 'numeric', hour: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' })
}

function baseOption(series: EChartsCoreOption['series'], max?: number): EChartsCoreOption {
  return {
    animationDuration: 350,
    color: ['#3b82f6', '#8b5cf6', '#06b6d4'],
    tooltip: { trigger: 'axis', backgroundColor: '#101828', borderColor: 'rgba(148,163,184,.22)', textStyle: { color: '#e5edf9', fontSize: 10 } },
    legend: { right: 5, top: 0, textStyle: { color: '#718096', fontSize: 9 }, itemWidth: 9, itemHeight: 5 },
    grid: { left: 43, right: 16, top: 39, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: metrics.value?.points.map((point) => chartTime(point.timestamp)) ?? [], axisLine: { lineStyle: { color: 'rgba(148,163,184,.13)' } }, axisLabel: { color: '#617087', fontSize: 8 }, axisTick: { show: false } },
    yAxis: { type: 'value', min: 0, max, axisLabel: { color: '#617087', fontSize: 8 }, splitLine: { lineStyle: { color: 'rgba(148,163,184,.09)' } } },
    series,
  }
}

const utilizationOption = computed(() => baseOption([
  { name: 'CPU %', type: 'line', smooth: true, showSymbol: (metrics.value?.points.length ?? 0) < 2, symbolSize: 6, data: metrics.value?.points.map((point) => point.cpuUsage) ?? [], lineStyle: { width: 2 }, areaStyle: { opacity: .06 } },
  { name: 'Memory %', type: 'line', smooth: true, showSymbol: (metrics.value?.points.length ?? 0) < 2, symbolSize: 6, data: metrics.value?.points.map((point) => point.memoryUsage) ?? [], lineStyle: { width: 2 } },
  { name: 'Disk %', type: 'line', smooth: true, showSymbol: (metrics.value?.points.length ?? 0) < 2, symbolSize: 6, data: metrics.value?.points.map((point) => point.diskUsage) ?? [], lineStyle: { width: 1.5 } },
], 100))

const networkOption = computed(() => baseOption([
  { name: 'Upload B/s', type: 'line', smooth: true, showSymbol: (metrics.value?.points.length ?? 0) < 2, symbolSize: 6, data: metrics.value?.points.map((point) => point.networkUploadRate) ?? [], lineStyle: { width: 2 }, areaStyle: { opacity: .05 } },
  { name: 'Download B/s', type: 'line', smooth: true, showSymbol: (metrics.value?.points.length ?? 0) < 2, symbolSize: 6, data: metrics.value?.points.map((point) => point.networkDownloadRate) ?? [], lineStyle: { width: 2 } },
]))

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const [node, history] = await Promise.all([
      serverApi.get(serverId.value), metricApi.history(serverId.value, range.value),
    ])
    server.value = node
    metrics.value = history
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '服务器详情加载失败')
  } finally {
    loading.value = false
  }
}

function changeRange(value: MetricRange) {
  range.value = value
  void load()
}

function bytes(value: string | number | null | undefined, rate = false) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let normalized = amount
  let index = 0
  while (normalized >= 1024 && index < units.length - 1) {
    normalized /= 1024
    index += 1
  }
  return `${normalized.toFixed(index ? 1 : 0)} ${units[index]}${rate ? '/s' : ''}`
}

function time(value: string | null | undefined) {
  if (!value) return 'Never'
  return new Date(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`).toLocaleString()
}

async function deleteServer() {
  if (!server.value || deleteName.value !== server.value.name) return
  deleting.value = true
  errorMessage.value = ''
  try {
    await serverApi.delete(server.value.id)
    serverStore.remove(server.value.id)
    await router.push('/servers')
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '服务器删除失败')
    deleteOpen.value = false
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(true), 10_000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="server-detail-view">
    <RouterLink class="back-link" to="/servers">← All servers</RouterLink>
    <header class="page-heading server-detail-heading">
      <div><p class="eyebrow">SERVER · {{ server?.hostname || 'CONNECTING' }}</p><h1>{{ server?.name || 'Loading server…' }}</h1><span>{{ server?.ip || 'IP pending' }} · {{ server?.os || 'Platform not reported' }}</span></div>
      <div v-if="server" class="server-detail-actions">
        <span class="status-badge detail-status" :class="server.status.toLowerCase()"><i />{{ server.status }}</span>
        <button v-if="auth.hasAnyRole(['ADMIN'])" class="danger-action" type="button" @click="deleteOpen = true; deleteName = ''">Delete server</button>
      </div>
    </header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <div class="metric-kpis" :class="{ 'is-loading': loading && !metrics }">
      <article><span>CPU utilization</span><strong>{{ current ? `${current.cpuUsage.toFixed(1)}%` : '—' }}</strong><small>{{ server?.cpuCores || '—' }} cores · load {{ current?.loadOne.toFixed(2) ?? '—' }}</small></article>
      <article><span>Memory</span><strong>{{ current ? `${current.memoryUsage.toFixed(1)}%` : '—' }}</strong><small>{{ bytes(current?.memoryUsed) }} of {{ bytes(current?.memoryTotal || server?.memoryTotal) }}</small></article>
      <article><span>Root disk</span><strong>{{ current ? `${current.diskUsage.toFixed(1)}%` : '—' }}</strong><small>{{ bytes(current?.diskFree) }} free of {{ bytes(current?.diskTotal || server?.diskTotal) }}</small></article>
      <article><span>Network I/O</span><strong>{{ bytes(current?.networkDownloadRate, true) }}</strong><small>↑ {{ bytes(current?.networkUploadRate, true) }} upload</small></article>
    </div>

    <div class="server-detail-grid">
      <article class="detail-panel utilization-panel">
        <header><div><strong>Resource utilization</strong><small>CPU, memory, and root filesystem</small></div><div class="range-switch"><button v-for="item in (['1h','6h','24h','7d'] as MetricRange[])" :key="item" :class="{ active: range === item }" @click="changeRange(item)">{{ item }}</button></div></header>
        <BaseChart v-if="metrics?.points.length" :option="utilizationOption" height="285px" />
        <div v-else class="metric-empty compact"><span>⌁</span><strong>No metric samples</strong><small>Keep the Agent running to populate this timeline.</small></div>
      </article>
      <article class="detail-panel node-facts">
        <header><div><strong>Node profile</strong><small>Last reported Agent inventory</small></div></header>
        <dl>
          <div><dt>Hostname</dt><dd>{{ server?.hostname || '—' }}</dd></div><div><dt>Architecture</dt><dd>{{ server?.architecture || '—' }}</dd></div>
          <div><dt>Kernel</dt><dd>{{ server?.kernel || '—' }}</dd></div><div><dt>Agent</dt><dd>{{ server?.agentVersion || '—' }}</dd></div>
          <div class="wide"><dt>Processor</dt><dd>{{ server?.cpuModel || '—' }}</dd></div><div class="wide"><dt>Last heartbeat</dt><dd>{{ time(server?.lastHeartbeat) }}</dd></div>
        </dl>
      </article>
      <article class="detail-panel network-panel">
        <header><div><strong>Network throughput</strong><small>Aggregate host upload and download rate</small></div><span>{{ metrics?.resolutionSeconds || '—' }}s resolution</span></header>
        <BaseChart v-if="metrics?.points.length" :option="networkOption" height="255px" />
        <div v-else class="metric-empty compact"><span>↕</span><strong>No network samples</strong></div>
      </article>
    </div>

    <div v-if="deleteOpen && server" class="modal-backdrop" @click.self="deleteOpen = false">
      <section class="server-dialog remove-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-server-title">
        <header><div><span>DESTRUCTIVE ACTION</span><h2 id="delete-server-title">Delete {{ server.name }}</h2></div><button aria-label="Close" @click="deleteOpen = false">×</button></header>
        <div class="dialog-body"><p>This removes the server from DevPilot and revokes every Agent credential issued for it. The host itself is not modified.</p><label><span>Type the server name to confirm</span><input v-model="deleteName" :placeholder="server.name" autofocus @keyup.enter="deleteServer" /></label></div>
        <footer><button @click="deleteOpen = false">Cancel</button><button class="danger-confirm" :disabled="deleting || deleteName !== server.name" @click="deleteServer">{{ deleting ? 'Deleting…' : 'Delete server' }}</button></footer>
      </section>
    </div>
  </section>
</template>
