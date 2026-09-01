import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, type AuthTokens, type AuthUser, type LoginPayload, type SetupPayload, type UserRole } from '@/api/auth'
import { onAuthenticationLost, setAccessToken } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const initialized = ref(false)
  const authenticating = ref(false)
  let initialization: Promise<void> | null = null

  const isAuthenticated = computed(() => user.value !== null)

  function applySession(session: AuthTokens) {
    setAccessToken(session.accessToken)
    user.value = session.user
  }

  function clearSession() {
    setAccessToken(null)
    user.value = null
  }

  onAuthenticationLost(() => {
    clearSession()
    if (window.location.pathname !== '/login') {
      window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`)
    }
  })

  async function initialize() {
    if (initialized.value) return
    if (!initialization) {
      initialization = authApi
        .refresh()
        .then(applySession)
        .catch(clearSession)
        .finally(() => {
          initialized.value = true
          initialization = null
        })
    }
    await initialization
  }

  async function login(payload: LoginPayload) {
    authenticating.value = true
    try {
      applySession(await authApi.login(payload))
    } finally {
      authenticating.value = false
    }
  }

  async function setup(payload: SetupPayload) {
    authenticating.value = true
    try {
      applySession(await authApi.setup(payload))
    } finally {
      authenticating.value = false
    }
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      clearSession()
    }
  }

  async function changePassword(currentPassword: string, newPassword: string, confirmPassword: string) {
    await authApi.changePassword(currentPassword, newPassword, confirmPassword)
    clearSession()
  }

  function hasAnyRole(roles: UserRole[]) {
    return user.value?.roles.some((role) => roles.includes(role)) ?? false
  }

  return { user, initialized, authenticating, isAuthenticated, initialize, login, setup, logout, changePassword, hasAnyRole }
})
