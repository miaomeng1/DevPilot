<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { alertsApi } from '@/api/alerts'
import { useSystemStore } from '@/stores/system'
import { apiErrorMessage } from '@/api/client'

const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const system = useSystemStore()
const activeAlerts = ref(0)
const commandInput = ref<HTMLInputElement | null>(null)
const commandOpen = ref(false)
const commandQuery = ref('')
const mobileNavOpen = ref(false)
const passwordOpen = ref(false)
const passwordSaving = ref(false)
const passwordError = ref('')
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
let alertTimer: number | undefined

const initials = computed(() => {
  const source = auth.user?.displayName || auth.user?.username || 'DP'
  return source.split(/\s+/).map((word) => word[0]).join('').slice(0, 2).toUpperCase()
})

const navigation = computed(() => [
  { label: '概览 Dashboard', code: 'DB', to: '/' },
  { label: '服务器 Servers', code: 'SV', to: '/servers' },
  { label: 'Docker', code: 'DK', to: '/docker' },
  { label: '应用 Applications', code: 'AP', to: '/applications' },
  { label: '模板 Templates', code: 'TM', to: '/templates' },
  { label: '发布 CI/CD', code: 'CI', to: '/cicd' },
  { label: 'Nginx', code: 'NX', to: '/nginx' },
  { label: '监控 Monitor', code: 'MN', to: '/monitor' },
  { label: '告警 Alerts', code: 'AL', to: '/alerts' },
  { label: '维护 Maintenance', code: 'MT', to: '/maintenance', adminOnly: true },
  { label: '审计 Audit', code: 'AU', to: '/audit', adminOnly: true },
  { label: '设置 Settings', code: 'ST', to: '/settings', adminOnly: true },
].filter((item) => !item.adminOnly || auth.hasAnyRole(['ADMIN'])))

const commandItems = computed(() => [
  ...navigation.value.map(({ label, to }) => ({ label, to, detail: '工作台 Workspace' })),
  { label: '容量建议 Capacity planner', to: '/capacity', detail: '部署前资源评分' },
  { label: '告警规则 Alert rules', to: '/alerts/rules', detail: '策略与 Webhooks' },
  ...(auth.hasAnyRole(['ADMIN']) ? [{ label: '通知路由 Alert routing', to: '/alerts/routing', detail: '静默与维护窗口' }] : []),
  ...(auth.hasAnyRole(['ADMIN']) ? [{ label: '备份与维护 Maintenance', to: '/maintenance', detail: '备份证据与恢复' }] : []),
  ...(auth.hasAnyRole(['ADMIN']) ? [{ label: '用户管理 Users', to: '/settings/users', detail: '账号与角色' }] : []),
])

const matchingCommands = computed(() => {
  const query = commandQuery.value.trim().toLowerCase()
  if (!query) return commandItems.value
  return commandItems.value.filter((item) => `${item.label} ${item.detail}`.toLowerCase().includes(query))
})

async function signOut() {
  await auth.logout()
  await router.replace('/login')
}

function openPasswordDialog() {
  passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
  passwordError.value = ''
  passwordOpen.value = true
}

async function changePassword() {
  passwordError.value = ''
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    passwordError.value = 'The new passwords do not match.'
    return
  }
  passwordSaving.value = true
  try {
    await auth.changePassword(passwordForm.value.currentPassword, passwordForm.value.newPassword,
      passwordForm.value.confirmPassword)
    passwordOpen.value = false
    await router.replace('/login?passwordChanged=true')
  } catch (error) {
    passwordError.value = apiErrorMessage(error, 'Password could not be changed')
  } finally {
    passwordSaving.value = false
  }
}

async function loadAlertCount() {
  try { activeAlerts.value = (await alertsApi.summary()).active } catch { activeAlerts.value = 0 }
}

async function openCommandSearch() {
  commandOpen.value = true
  await nextTick()
  commandInput.value?.focus()
}

async function runCommand(to?: string) {
  const destination = to || matchingCommands.value[0]?.to
  if (!destination) return
  commandOpen.value = false
  commandQuery.value = ''
  await router.push(destination)
}

function handleGlobalShortcut(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    void openCommandSearch()
  }
}

onMounted(() => {
  void loadAlertCount()
  alertTimer = window.setInterval(() => void loadAlertCount(), 30_000)
  window.addEventListener('keydown', handleGlobalShortcut)
})
onBeforeUnmount(() => {
  window.clearInterval(alertTimer)
  window.removeEventListener('keydown', handleGlobalShortcut)
})
</script>

<template>
  <div class="console-shell">
    <button v-if="mobileNavOpen" class="mobile-nav-backdrop" aria-label="Close navigation" @click="mobileNavOpen = false" />
    <aside class="console-sidebar" :class="{ 'mobile-open': mobileNavOpen }">
      <RouterLink class="console-brand" to="/">
        <img v-if="system.settings.logoUrl" class="custom-logo compact-logo" :src="system.settings.logoUrl" alt="" />
        <div v-else class="brand-symbol compact"><i /><i /><i /></div>
        <div><strong>{{ system.settings.systemName }}</strong><small>云控制台 Cloud Console</small></div>
      </RouterLink>
      <button class="mobile-sidebar-close" type="button" aria-label="Close navigation" @click="mobileNavOpen = false">×</button>

      <nav class="console-nav" aria-label="Primary navigation">
        <p>工作台 Workspace</p>
        <RouterLink
          v-for="item in navigation"
          :key="item.label"
          :to="item.to"
          class="nav-entry"
          @click="mobileNavOpen = false"
        >
          <b>{{ item.code }}</b><span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <span class="control-health"><i />控制面 Control plane</span>
        <small>v1.0.0 · 控制面</small>
      </div>
    </aside>

    <section class="console-main">
      <header class="console-topbar">
        <div class="command-search-wrap" @focusout="commandOpen = false">
          <div class="command-search"><span>⌕</span><input ref="commandInput" v-model="commandQuery" role="combobox" aria-label="跳转页面" aria-controls="command-results" :aria-expanded="commandOpen" placeholder="搜索或跳转 Search…" @focus="commandOpen = true" @keydown.enter.prevent="runCommand()" @keydown.escape="commandOpen = false; commandInput?.blur()" /><kbd>⌘ K</kbd></div>
          <div v-if="commandOpen" id="command-results" class="command-results" role="listbox">
            <button v-for="item in matchingCommands" :key="item.to" type="button" role="option" @mousedown.prevent @click="runCommand(item.to)"><span>{{ item.label }}</span><small>{{ item.detail }}</small></button>
            <p v-if="!matchingCommands.length">没有匹配页面 No results</p>
          </div>
        </div>
        <div class="top-actions">
          <button class="mobile-nav-toggle" type="button" aria-label="Open navigation" :aria-expanded="mobileNavOpen" @click="mobileNavOpen = true">☰</button>
          <button type="button" aria-label="切换主题" @click="theme.toggle">{{ theme.mode === 'dark' ? '☼' : '◐' }}</button>
          <RouterLink class="notification-button" to="/alerts" aria-label="Open alerts"><span v-if="activeAlerts">{{ activeAlerts > 99 ? '99+' : activeAlerts }}</span>♢</RouterLink>
          <div class="user-menu">
            <span class="avatar">{{ initials }}</span>
            <div><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.roles[0] }}</small></div>
            <button type="button" title="Change password" aria-label="Change password" @click="openPasswordDialog">⚿</button>
            <button type="button" title="Sign out" aria-label="Sign out" @click="signOut">↗</button>
          </div>
        </div>
      </header>
      <main class="console-content"><RouterView /></main>
    </section>
    <div v-if="passwordOpen" class="modal-backdrop" @click.self="passwordOpen = false">
      <section class="server-dialog" role="dialog" aria-modal="true" aria-labelledby="change-password-title">
        <header><div><span>ACCOUNT SECURITY</span><h2 id="change-password-title">Change password</h2></div><button aria-label="Close" @click="passwordOpen = false">×</button></header>
        <div class="dialog-body application-form">
          <p>Changing your password revokes every existing session. You will need to sign in again.</p>
          <label><span>Current password</span><input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" maxlength="128" /></label>
          <label><span>New password</span><input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="12" maxlength="128" /></label>
          <label><span>Confirm new password</span><input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="12" maxlength="128" @keyup.enter="changePassword" /></label>
          <p v-if="passwordError" class="form-error"><span>!</span>{{ passwordError }}</p>
        </div>
        <footer><button @click="passwordOpen = false">Cancel</button><button class="dialog-primary" :disabled="passwordSaving || !passwordForm.currentPassword || !passwordForm.newPassword || !passwordForm.confirmPassword" @click="changePassword">{{ passwordSaving ? 'Changing…' : 'Change password' }}</button></footer>
      </section>
    </div>
  </div>
</template>
