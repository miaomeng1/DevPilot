import { defineStore } from 'pinia'
import { ref } from 'vue'

export type ThemeMode = 'dark' | 'light'

export const useThemeStore = defineStore('theme', () => {
  const savedMode = localStorage.getItem('devpilot-theme')
  const mode = ref<ThemeMode>(savedMode === 'dark' || savedMode === 'light' ? savedMode : 'light')

  function apply() {
    document.documentElement.dataset.theme = mode.value
    document.documentElement.classList.toggle('dark', mode.value === 'dark')
  }

  function applyDefault(preference: 'DARK' | 'LIGHT' | 'SYSTEM') {
    if (localStorage.getItem('devpilot-theme')) return
    mode.value = preference === 'SYSTEM'
      ? (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark')
      : preference.toLowerCase() as ThemeMode
    apply()
  }

  function toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem('devpilot-theme', mode.value)
    apply()
  }

  return { mode, apply, applyDefault, toggle }
})
