<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { usersApi, type CreateUserPayload, type ManagedUser } from '@/api/users'
import { apiErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const users = ref<ManagedUser[]>([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref<'create' | 'edit' | 'password' | null>(null)
const selected = ref<ManagedUser | null>(null)
const query = ref('')
const errorMessage = ref('')
const createForm = reactive<CreateUserPayload>({ username: '', displayName: '', email: '', role: 'DEVELOPER', password: '', confirmPassword: '' })
const editForm = reactive({ displayName: '', email: '', role: 'DEVELOPER' as ManagedUser['role'], status: 'ACTIVE' as ManagedUser['status'] })
const passwordForm = reactive({ password: '', confirmPassword: '' })

const visible = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return users.value.filter((user) => !needle || [user.username, user.displayName, user.email, user.role]
    .some((value) => value?.toLowerCase().includes(needle)))
})
const counts = computed(() => ({
  total: users.value.length, active: users.value.filter((user) => user.status === 'ACTIVE').length,
  admins: users.value.filter((user) => user.role === 'ADMIN' && user.status === 'ACTIVE').length,
  disabled: users.value.filter((user) => user.status === 'DISABLED').length,
}))

async function load() {
  loading.value = true; errorMessage.value = ''
  try { users.value = await usersApi.list() }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'Users could not be loaded') }
  finally { loading.value = false }
}

function openCreate() {
  Object.assign(createForm, { username: '', displayName: '', email: '', role: 'DEVELOPER', password: '', confirmPassword: '' })
  dialog.value = 'create'; selected.value = null; errorMessage.value = ''
}
function openEdit(user: ManagedUser) {
  selected.value = user
  Object.assign(editForm, { displayName: user.displayName, email: user.email || '', role: user.role, status: user.status })
  dialog.value = 'edit'; errorMessage.value = ''
}
function openPassword(user: ManagedUser) {
  selected.value = user; Object.assign(passwordForm, { password: '', confirmPassword: '' })
  dialog.value = 'password'; errorMessage.value = ''
}

async function save() {
  saving.value = true; errorMessage.value = ''
  try {
    if (dialog.value === 'create') await usersApi.create({ ...createForm })
    else if (dialog.value === 'edit' && selected.value) await usersApi.update(selected.value.id, { ...editForm, email: editForm.email || null })
    else if (dialog.value === 'password' && selected.value) await usersApi.resetPassword(selected.value.id, passwordForm.password, passwordForm.confirmPassword)
    dialog.value = null; await load()
  } catch (error) { errorMessage.value = apiErrorMessage(error, 'User change could not be saved') }
  finally { saving.value = false }
}

async function remove(user: ManagedUser) {
  if (!window.confirm(`Delete user “${user.username}”? Their refresh sessions will be revoked.`)) return
  try { await usersApi.delete(user.id); await load() }
  catch (error) { errorMessage.value = apiErrorMessage(error, 'User could not be deleted') }
}

function formatTime(value: string | null) {
  if (!value) return 'Never'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

onMounted(load)
</script>

<template>
  <section class="users-view">
    <header class="page-heading settings-heading"><div><p class="eyebrow">ACCESS CONTROL</p><h1>Users & roles</h1><span>ADMIN, DEVELOPER, and VIEWER access with immediate session revocation.</span></div><button class="primary-compact" @click="openCreate"><b>＋</b>Create user</button></header>
    <nav class="settings-tabs"><RouterLink to="/settings">General</RouterLink><RouterLink class="active" to="/settings/users">Users & roles</RouterLink></nav>
    <p v-if="errorMessage && !dialog" class="inline-error">{{ errorMessage }}</p>
    <div class="alert-summary user-summary"><article><span>Users</span><strong>{{ counts.total }}</strong><small>Non-deleted accounts</small></article><article class="available"><span>Active</span><strong>{{ counts.active }}</strong><small>Can authenticate</small></article><article><span>Administrators</span><strong>{{ counts.admins }}</strong><small>Active ADMIN role</small></article><article class="issues"><span>Disabled</span><strong>{{ counts.disabled }}</strong><small>Sessions rejected</small></article></div>
    <article class="user-table-panel"><header><div class="table-search"><span>⌕</span><input v-model="query" placeholder="Filter username, name, email, or role" /></div><button class="refresh-button" @click="load">{{ loading ? 'Refreshing…' : 'Refresh' }}</button></header>
      <div v-if="loading && !users.length" class="table-empty"><span class="loading-ring" /><strong>Loading users</strong></div>
      <div v-else class="server-table-wrap"><table class="server-table user-table"><thead><tr><th>User</th><th>Role</th><th>Status</th><th>Last login</th><th>Created</th><th>Actions</th></tr></thead><tbody><tr v-for="user in visible" :key="user.id">
        <td><div class="managed-user"><span>{{ user.displayName.split(/\s+/).map((part) => part[0]).join('').slice(0,2).toUpperCase() }}</span><div><strong>{{ user.displayName }} <i v-if="user.id === auth.user?.id">YOU</i></strong><small>@{{ user.username }} · {{ user.email || 'No email' }}</small></div></div></td>
        <td><span class="role-pill" :class="user.role.toLowerCase()">{{ user.role }}</span></td><td><span class="status-badge" :class="user.status === 'ACTIVE' ? 'online' : 'offline'"><i />{{ user.status }}</span></td>
        <td><strong class="cell-primary">{{ formatTime(user.lastLoginAt) }}</strong><small class="cell-secondary">Successful sign-in</small></td><td><strong class="cell-primary">{{ formatTime(user.createdAt) }}</strong><small class="cell-secondary">Account created</small></td>
        <td><div class="row-actions"><button @click="openEdit(user)">Edit</button><button @click="openPassword(user)">Password</button><button v-if="user.id !== auth.user?.id" class="danger" @click="remove(user)">Delete</button></div></td>
      </tr></tbody></table></div>
    </article>

    <div v-if="dialog" class="modal-backdrop" @click.self="dialog = null"><section class="server-dialog user-dialog" role="dialog" aria-modal="true"><header><div><span>IDENTITY & ACCESS</span><h2>{{ dialog === 'create' ? 'Create user' : dialog === 'edit' ? `Edit ${selected?.username}` : `Reset ${selected?.username} password` }}</h2></div><button aria-label="Close" @click="dialog = null">×</button></header><div class="dialog-body application-form">
      <template v-if="dialog === 'create'"><div class="form-grid"><label><span>Username</span><input v-model.trim="createForm.username" maxlength="32" /></label><label><span>Display name</span><input v-model.trim="createForm.displayName" maxlength="100" /></label></div><div class="form-grid"><label><span>Email</span><input v-model.trim="createForm.email" type="email" maxlength="190" /></label><label><span>Role</span><select v-model="createForm.role"><option>ADMIN</option><option>DEVELOPER</option><option>VIEWER</option></select></label></div><div class="form-grid"><label><span>Password</span><input v-model="createForm.password" type="password" minlength="12" maxlength="128" /></label><label><span>Confirm password</span><input v-model="createForm.confirmPassword" type="password" minlength="12" maxlength="128" /></label></div></template>
      <template v-else-if="dialog === 'edit'"><label><span>Display name</span><input v-model.trim="editForm.displayName" maxlength="100" /></label><label><span>Email</span><input v-model.trim="editForm.email" type="email" maxlength="190" /></label><div class="form-grid"><label><span>Role</span><select v-model="editForm.role"><option>ADMIN</option><option>DEVELOPER</option><option>VIEWER</option></select></label><label><span>Status</span><select v-model="editForm.status"><option>ACTIVE</option><option>DISABLED</option></select></label></div><small>Role changes and disabling revoke all refresh sessions immediately. You cannot remove your own ADMIN access.</small></template>
      <template v-else><label><span>New password</span><input v-model="passwordForm.password" type="password" minlength="12" maxlength="128" /></label><label><span>Confirm password</span><input v-model="passwordForm.confirmPassword" type="password" minlength="12" maxlength="128" /></label><small>At least 12 characters containing letters and numbers. Existing refresh sessions are revoked.</small></template>
      <p v-if="errorMessage" class="form-error"><span>!</span>{{ errorMessage }}</p>
    </div><footer><button @click="dialog = null">Cancel</button><button class="dialog-primary" :disabled="saving" @click="save">{{ saving ? 'Saving…' : 'Save user' }} <b>→</b></button></footer></section></div>
  </section>
</template>
