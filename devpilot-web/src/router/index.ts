import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSystemStore } from '@/stores/system'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: 'Dashboard' },
        },
        {
          path: 'servers',
          name: 'servers',
          component: () => import('@/views/ServersView.vue'),
          meta: { title: 'Servers' },
        },
        {
          path: 'servers/:id',
          name: 'server-detail',
          component: () => import('@/views/ServerDetailView.vue'),
          meta: { title: 'Server telemetry' },
        },
        {
          path: 'capacity',
          name: 'capacity-planner',
          component: () => import('@/views/CapacityPlannerView.vue'),
          meta: { title: '容量与部署建议' },
        },
        {
          path: 'docker',
          name: 'docker',
          component: () => import('@/views/DockerView.vue'),
          meta: { title: 'Docker' },
        },
        {
          path: 'docker/containers/:id',
          name: 'docker-container',
          component: () => import('@/views/DockerContainerDetailView.vue'),
          meta: { title: 'Container detail' },
        },
        {
          path: 'applications',
          name: 'applications',
          component: () => import('@/views/ApplicationsView.vue'),
          meta: { title: 'Applications' },
        },
        {
          path: 'applications/:id',
          name: 'application-detail',
          component: () => import('@/views/ApplicationDetailView.vue'),
          meta: { title: 'Application detail' },
        },
        {
          path: 'templates',
          name: 'service-templates',
          component: () => import('@/views/ServiceTemplatesView.vue'),
          meta: { title: '个人服务模板' },
        },
        {
          path: 'cicd',
          name: 'cicd',
          component: () => import('@/views/CicdView.vue'),
          meta: { title: '发布中心 CI/CD' },
        },
        {
          path: 'nginx',
          name: 'nginx',
          component: () => import('@/views/NginxView.vue'),
          meta: { title: 'Nginx' },
        },
        {
          path: 'nginx/configs/:id',
          name: 'nginx-config',
          component: () => import('@/views/NginxConfigView.vue'),
          meta: { title: 'Nginx configuration' },
        },
        {
          path: 'monitor',
          name: 'monitor',
          component: () => import('@/views/MonitorView.vue'),
          meta: { title: 'Monitor' },
        },
        {
          path: 'alerts',
          name: 'alerts',
          component: () => import('@/views/AlertsView.vue'),
          meta: { title: 'Alert events' },
        },
        {
          path: 'alerts/rules',
          name: 'alert-rules',
          component: () => import('@/views/AlertRulesView.vue'),
          meta: { title: 'Alert rules' },
        },
        {
          path: 'alerts/routing',
          name: 'alert-routing',
          component: () => import('@/views/AlertRoutingView.vue'),
          meta: { title: '通知路由与维护窗口', roles: ['ADMIN'] },
        },
        {
          path: 'maintenance',
          name: 'maintenance',
          component: () => import('@/views/MaintenanceView.vue'),
          meta: { title: '备份与维护', roles: ['ADMIN'] },
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/AuditView.vue'),
          meta: { title: 'Audit log', roles: ['ADMIN'] },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: 'System settings', roles: ['ADMIN'] },
        },
        {
          path: 'settings/users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { title: 'User management', roles: ['ADMIN'] },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.initialize()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) return { name: 'dashboard' }
    return true
  }
  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !auth.hasAnyRole(to.meta.roles)) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const system = useSystemStore()
  document.title = to.meta.title ? `${to.meta.title} · ${system.settings.systemName}` : `${system.settings.systemName} Developer Cloud Console`
})

export default router
