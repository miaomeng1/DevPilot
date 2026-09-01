<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { dockerApi } from '@/api/docker'
import { apiErrorMessage } from '@/api/client'
import { useThemeStore } from '@/stores/theme'
import { useSystemStore } from '@/stores/system'

const props = defineProps<{ containerId: string; containerName: string }>()
const theme = useThemeStore()
const system = useSystemStore()
const terminalElement = ref<HTMLDivElement>()
const tailLines = ref<100 | 500>(system.settings.logDefaultLines)
const follow = ref(true)
const paused = ref(false)
const level = ref<'ALL' | 'INFO' | 'WARN' | 'ERROR'>('ALL')
const keyword = ref('')
const state = ref<'connecting' | 'live' | 'closed' | 'error'>('closed')
const errorMessage = ref('')
const captured = ref<string[]>([])
let terminal: Terminal | undefined
let fit: FitAddon | undefined
let socket: WebSocket | undefined
let observer: ResizeObserver | undefined

const stateLabel = computed(() => ({ connecting: 'Connecting', live: 'Live follow', closed: 'Stream closed', error: 'Connection error' })[state.value])

function isVisible(line: string) {
  const upper = line.toUpperCase()
  const matchesLevel = level.value === 'ALL' || upper.includes(level.value)
  const matchesKeyword = !keyword.value.trim() || line.toLowerCase().includes(keyword.value.trim().toLowerCase())
  return matchesLevel && matchesKeyword
}

function renderCaptured() {
  if (!terminal) return
  terminal.clear()
  terminal.write('\x1b[2J\x1b[H')
  for (const line of captured.value) {
    if (isVisible(line)) terminal.writeln(line)
  }
}

async function connect() {
  socket?.close()
  state.value = 'connecting'
  errorMessage.value = ''
  captured.value = []
  renderCaptured()
  try {
    const ticket = await dockerApi.logTicket(props.containerId, tailLines.value, follow.value)
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    socket = new WebSocket(`${protocol}//${window.location.host}${ticket.webSocketPath}`)
    socket.onopen = () => { state.value = 'live' }
    socket.onmessage = (event) => {
      const line = String(event.data)
      captured.value.push(line)
      if (captured.value.length > 10_000) captured.value.shift()
      if (!paused.value && isVisible(line)) terminal?.writeln(line)
    }
    socket.onerror = () => { state.value = 'error'; errorMessage.value = 'WebSocket log relay failed' }
    socket.onclose = () => { if (state.value !== 'error') state.value = 'closed' }
  } catch (error) {
    state.value = 'error'
    errorMessage.value = apiErrorMessage(error, '无法创建日志会话')
  }
}

function togglePause() {
  paused.value = !paused.value
  if (!paused.value) renderCaptured()
}

function clearScreen() {
  captured.value = []
  terminal?.clear()
  terminal?.write('\x1b[2J\x1b[H')
}

function download() {
  const blob = new Blob([captured.value.join('\n')], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${props.containerName}-logs.txt`
  anchor.click()
  URL.revokeObjectURL(url)
}

watch([level, keyword], renderCaptured)
watch(() => theme.mode, () => {
  if (!terminal) return
  terminal.options.theme = theme.mode === 'dark'
    ? { background: '#080d18', foreground: '#cbd5e1', cursor: '#60a5fa', selectionBackground: '#1e3a8a88' }
    : { background: '#f8fafc', foreground: '#334155', cursor: '#2563eb', selectionBackground: '#bfdbfe' }
})

onMounted(async () => {
  terminal = new Terminal({
    convertEol: true, disableStdin: true, cursorBlink: false, fontSize: 11, lineHeight: 1.35,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    scrollback: 10_000,
    theme: { background: theme.mode === 'dark' ? '#080d18' : '#f8fafc', foreground: theme.mode === 'dark' ? '#cbd5e1' : '#334155', cursor: '#60a5fa' },
  })
  fit = new FitAddon()
  terminal.loadAddon(fit)
  if (terminalElement.value) terminal.open(terminalElement.value)
  await nextTick()
  fit.fit()
  observer = new ResizeObserver(() => fit?.fit())
  if (terminalElement.value) observer.observe(terminalElement.value)
  void connect()
})

onBeforeUnmount(() => {
  socket?.close()
  observer?.disconnect()
  terminal?.dispose()
})
</script>

<template>
  <article class="container-logs-panel">
    <header><div><strong>Container logs</strong><small>Docker Engine stream relayed through the authenticated Agent</small></div><span class="log-state" :class="state"><i />{{ stateLabel }}</span></header>
    <div class="log-toolbar">
      <label>Tail <select v-model="tailLines" @change="connect"><option :value="100">100 lines</option><option :value="500">500 lines</option></select></label>
      <label class="follow-toggle"><input v-model="follow" type="checkbox" @change="connect" />Follow</label>
      <div class="log-levels"><button v-for="item in (['ALL','INFO','WARN','ERROR'] as const)" :key="item" :class="{ active: level === item }" @click="level = item">{{ item }}</button></div>
      <div class="log-search"><span>⌕</span><input v-model="keyword" placeholder="Search captured output" /></div>
      <button @click="togglePause">{{ paused ? 'Resume' : 'Pause' }}</button><button @click="clearScreen">Clear</button><button @click="download">Download</button><button @click="connect">Reconnect</button>
    </div>
    <p v-if="errorMessage" class="log-error">{{ errorMessage }}</p>
    <div ref="terminalElement" class="log-terminal" />
    <footer><span>{{ captured.length }} lines captured</span><span>One-time browser ticket · Agent token header</span></footer>
  </article>
</template>
