<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { apiClient, type ApiResponse } from '@/api/client'
import { useThemeStore } from '@/stores/theme'

interface HealthData {
  service: string
  status: string
  timestamp: string
}

const theme = useThemeStore()
const loading = ref(true)
const connected = ref(false)
const checkedAt = ref('')

const statusLabel = computed(() => (connected.value ? 'Control plane online' : 'Waiting for server'))

async function checkHealth() {
  loading.value = true
  try {
    const response = await apiClient.get<ApiResponse<HealthData>>('/health')
    connected.value = response.data.code === 0 && response.data.data.status === 'UP'
    checkedAt.value = new Date(response.data.data.timestamp).toLocaleString()
  } catch {
    connected.value = false
    checkedAt.value = new Date().toLocaleString()
  } finally {
    loading.value = false
  }
}

onMounted(checkHealth)
</script>

<template>
  <main class="bootstrap-shell">
    <button class="theme-button" type="button" aria-label="切换主题" @click="theme.toggle">
      {{ theme.mode === 'dark' ? 'Light' : 'Dark' }}
    </button>
    <section class="bootstrap-card">
      <div class="brand-mark" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <p class="eyebrow">DEVPILOT DEVELOPER CLOUD CONSOLE</p>
      <h1>One calm view<br />for every service.</h1>
      <p class="lede">
        项目骨架已就绪。控制平面、Web Console、Agent、MySQL 与 Redis 将在这里汇合。
      </p>

      <div class="connection-card" :class="{ online: connected }">
        <span class="status-dot" />
        <div>
          <strong>{{ loading ? 'Checking control plane…' : statusLabel }}</strong>
          <small v-if="checkedAt">Last check · {{ checkedAt }}</small>
        </div>
        <button type="button" :disabled="loading" @click="checkHealth">Refresh</button>
      </div>

      <div class="service-grid">
        <article><span>01</span><strong>Server</strong><small>Spring Boot 3 · Java 21</small></article>
        <article><span>02</span><strong>Console</strong><small>Vue 3 · TypeScript</small></article>
        <article><span>03</span><strong>Agent</strong><small>Go · Single binary</small></article>
      </div>
    </section>
  </main>
</template>

