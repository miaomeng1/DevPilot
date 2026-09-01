import { defineStore } from 'pinia'
import { ref } from 'vue'
import { settingsApi, type PublicSettings } from '@/api/settings'

export const useSystemStore = defineStore('system', () => {
  const settings = ref<PublicSettings>({ systemName: 'DevPilot', logoUrl: null, defaultTheme: 'DARK', logDefaultLines: 100 })
  const loaded = ref(false)
  let pending: Promise<PublicSettings> | null = null

  async function load() {
    if (loaded.value) return settings.value
    if (!pending) {
      pending = settingsApi.publicSettings().then((value) => { settings.value = value; loaded.value = true; return value })
        .catch(() => settings.value).finally(() => { pending = null })
    }
    return pending
  }

  async function refresh() { loaded.value = false; return load() }
  return { settings, loaded, load, refresh }
})
