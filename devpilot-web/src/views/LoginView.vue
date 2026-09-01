<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useSystemStore } from '@/stores/system'

type ScreenMode = 'loading' | 'login' | 'setup' | 'unavailable'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const system = useSystemStore()
const screenMode = ref<ScreenMode>('loading')
const errorMessage = ref('')
const passwordVisible = ref(false)

const loginForm = reactive({ username: '', password: '' })
const setupForm = reactive({
  username: 'admin',
  displayName: 'Platform Administrator',
  email: '',
  password: '',
  confirmPassword: '',
})

const isSetup = computed(() => screenMode.value === 'setup')
const title = computed(() => (isSetup.value ? 'Initialize your control plane' : 'Welcome back'))
const subtitle = computed(() =>
  isSetup.value
    ? 'Create the first administrator. No default password is ever generated.'
    : 'Sign in to see every server, service and signal in one place.',
)

async function detectSetupState() {
  screenMode.value = 'loading'
  errorMessage.value = ''
  try {
    const status = await authApi.setupStatus()
    screenMode.value = status.setupRequired ? 'setup' : 'login'
  } catch (error) {
    screenMode.value = 'unavailable'
    errorMessage.value = apiErrorMessage(error, 'Control plane is unavailable')
  }
}

async function submit() {
  errorMessage.value = ''
  try {
    if (isSetup.value) {
      if (setupForm.password !== setupForm.confirmPassword) {
        errorMessage.value = '两次输入的密码不一致'
        return
      }
      await auth.setup({ ...setupForm, email: setupForm.email || undefined })
    } else {
      await auth.login(loginForm)
    }
    const requested = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    const destination = requested.startsWith('/') && !requested.startsWith('//') ? requested : '/'
    await router.replace(destination)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, isSetup.value ? '初始化失败' : '登录失败')
  }
}

onMounted(detectSetupState)
</script>

<template>
  <main class="auth-page">
    <div class="auth-grid" aria-hidden="true" />
    <button class="auth-theme" type="button" @click="theme.toggle">
      <span>{{ theme.mode === 'dark' ? '☼' : '◐' }}</span>
      {{ theme.mode === 'dark' ? 'Light' : 'Dark' }}
    </button>

    <section class="auth-story">
      <div class="auth-brand">
        <img v-if="system.settings.logoUrl" class="custom-logo" :src="system.settings.logoUrl" alt="" />
        <div v-else class="brand-symbol"><i /><i /><i /></div>
        <div><strong>{{ system.settings.systemName }}</strong><small>Developer Cloud Console</small></div>
      </div>
      <div class="story-copy">
        <p class="eyebrow">CONTROL PLANE · ONLINE</p>
        <h1>Operate with<br /><em>quiet confidence.</em></h1>
        <p>Servers, containers, applications and alerts — composed into one deliberate console.</p>
      </div>
      <div class="story-status">
        <span class="pulse"><i /></span>
        <div><strong>Secure by construction</strong><small>Short-lived JWT · Rotating refresh session · Auditable actions</small></div>
      </div>
    </section>

    <section class="auth-panel">
      <div v-if="screenMode === 'loading'" class="auth-state">
        <span class="loading-ring" />
        <strong>Contacting control plane</strong>
        <small>Checking initialization state…</small>
      </div>

      <div v-else-if="screenMode === 'unavailable'" class="auth-state error-state">
        <span class="state-glyph">!</span>
        <strong>Control plane unavailable</strong>
        <small>{{ errorMessage }}</small>
        <button type="button" @click="detectSetupState">Try again</button>
      </div>

      <form v-else class="auth-form" @submit.prevent="submit">
        <div class="form-heading">
          <span class="step-label">{{ isSetup ? 'FIRST RUN · 01' : 'SECURE ACCESS' }}</span>
          <h2>{{ title }}</h2>
          <p>{{ subtitle }}</p>
        </div>

        <template v-if="isSetup">
          <label>
            <span>Display name</span>
            <input v-model.trim="setupForm.displayName" autocomplete="name" maxlength="100" required />
          </label>
          <div class="field-row">
            <label>
              <span>Username</span>
              <input v-model.trim="setupForm.username" autocomplete="username" minlength="3" maxlength="32" required />
            </label>
            <label>
              <span>Email <small>optional</small></span>
              <input v-model.trim="setupForm.email" autocomplete="email" type="email" maxlength="190" />
            </label>
          </div>
          <label>
            <span>Password</span>
            <div class="password-field">
              <input v-model="setupForm.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required />
              <button type="button" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? 'Hide' : 'Show' }}</button>
            </div>
            <small class="field-hint">At least 12 characters with letters and numbers.</small>
          </label>
          <label>
            <span>Confirm password</span>
            <input v-model="setupForm.confirmPassword" type="password" autocomplete="new-password" minlength="12" maxlength="128" required />
          </label>
        </template>

        <template v-else>
          <label>
            <span>Username</span>
            <input v-model.trim="loginForm.username" autocomplete="username" maxlength="64" autofocus required />
          </label>
          <label>
            <span>Password</span>
            <div class="password-field">
              <input v-model="loginForm.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" maxlength="128" required />
              <button type="button" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? 'Hide' : 'Show' }}</button>
            </div>
          </label>
        </template>

        <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
        <button class="primary-action" type="submit" :disabled="auth.authenticating">
          <span>{{ auth.authenticating ? 'Please wait…' : isSetup ? 'Create administrator' : 'Enter console' }}</span>
          <b aria-hidden="true">→</b>
        </button>
        <p class="session-note"><span>◆</span> Refresh credentials remain in an HttpOnly cookie and never enter browser storage.</p>
      </form>
    </section>
  </main>
</template>
