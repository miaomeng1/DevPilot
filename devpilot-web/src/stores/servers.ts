import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { serverApi, type ServerNode } from '@/api/servers'

export const useServerStore = defineStore('servers', () => {
  const servers = ref<ServerNode[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const lastUpdated = ref<Date | null>(null)
  let request: Promise<void> | null = null

  const onlineCount = computed(() => servers.value.filter((server) => server.status === 'ONLINE').length)

  async function load(force = false) {
    if (request && !force) return request
    loading.value = true
    request = serverApi
      .list()
      .then((data) => {
        servers.value = data
        loaded.value = true
        lastUpdated.value = new Date()
      })
      .finally(() => {
        loading.value = false
        request = null
      })
    return request
  }

  function prepend(server: ServerNode) {
    servers.value = [server, ...servers.value.filter((item) => item.id !== server.id)]
  }

  function remove(id: string) {
    servers.value = servers.value.filter((server) => server.id !== id)
  }

  return { servers, loading, loaded, lastUpdated, onlineCount, load, prepend, remove }
})
