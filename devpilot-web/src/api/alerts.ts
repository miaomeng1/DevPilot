import { apiClient, type ApiResponse } from './client'

export type AlertMetricType = 'SERVER_CPU' | 'SERVER_MEMORY' | 'SERVER_DISK' | 'AGENT_OFFLINE' | 'CONTAINER_STOPPED' | 'CONTAINER_RESTARTS' | 'APP_UNHEALTHY'
export type AlertOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NE'
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type AlertStatus = 'FIRING' | 'ACKNOWLEDGED' | 'RESOLVED'
export type NotificationStatus = 'NONE' | 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'MUTED' | 'PARTIAL'

export interface AlertRulePayload {
  name: string
  metricType: AlertMetricType
  operator: AlertOperator
  threshold: number | null
  durationSeconds: number
  severity: AlertSeverity
  serverId: string | null
  enabled: boolean
}

export interface AlertRule extends AlertRulePayload {
  id: string
  serverName: string
  createdAt: string
  updatedAt: string
}

export interface AlertEvent {
  id: string
  ruleId: string
  ruleName: string
  metricType: AlertMetricType
  serverId: string
  serverName: string
  resourceType: 'SERVER' | 'CONTAINER' | 'APPLICATION'
  resourceId: string
  resourceName: string
  severity: AlertSeverity
  message: string
  status: AlertStatus
  currentValue: number | null
  threshold: number | null
  operator: AlertOperator | null
  startedAt: string
  acknowledgedBy: string | null
  acknowledgedByName: string | null
  acknowledgedAt: string | null
  resolvedAt: string | null
  updatedAt: string
  notificationStatus: NotificationStatus
  deliveries: AlertDelivery[]
}

export interface AlertDelivery {
  id: string
  routeName: string
  transition: 'FIRING' | 'RESOLVED'
  status: Exclude<NotificationStatus, 'NONE' | 'PARTIAL'>
  attemptCount: number
  responseCode: number | null
  errorMessage: string | null
  sentAt: string | null
  updatedAt: string
}

export interface AlertSummary { active: number; critical: number }
export interface WebhookConfig { enabled: boolean; configured: boolean; destinationType: string }

export interface AlertRoutePayload {
  name: string
  serverId: string | null
  minimumSeverity: AlertSeverity
  webhookUrl?: string
  notifyResolved: boolean
  enabled: boolean
  quietEnabled: boolean
  quietStart: string | null
  quietEnd: string | null
  quietDays: string[]
  timezone: string
  criticalBypassMute: boolean
}

export interface AlertRoute extends Omit<AlertRoutePayload, 'webhookUrl'> {
  id: string
  serverName: string
  destinationType: string
  configured: boolean
  mutedNow: boolean
  createdAt: string
  updatedAt: string
}

export interface MaintenanceWindowPayload {
  name: string
  reason?: string
  serverId: string | null
  startsAt: string
  endsAt: string
}

export interface MaintenanceWindow {
  id: string
  name: string
  reason: string | null
  serverId: string | null
  serverName: string
  startsAt: string
  endsAt: string
  status: 'ACTIVE' | 'UPCOMING' | 'ENDED'
  createdAt: string
}

export const alertsApi = {
  async rules() {
    const response = await apiClient.get<ApiResponse<AlertRule[]>>('/alerts/rules')
    return response.data.data
  },
  async createRule(payload: AlertRulePayload) {
    const response = await apiClient.post<ApiResponse<AlertRule>>('/alerts/rules', payload)
    return response.data.data
  },
  async updateRule(id: string, payload: AlertRulePayload) {
    const response = await apiClient.put<ApiResponse<AlertRule>>(`/alerts/rules/${id}`, payload)
    return response.data.data
  },
  async deleteRule(id: string) {
    await apiClient.delete(`/alerts/rules/${id}`)
  },
  async events(params?: { status?: string; severity?: string; serverId?: string }) {
    const response = await apiClient.get<ApiResponse<AlertEvent[]>>('/alerts', { params })
    return response.data.data
  },
  async summary() {
    const response = await apiClient.get<ApiResponse<AlertSummary>>('/alerts/summary')
    return response.data.data
  },
  async acknowledge(id: string) {
    const response = await apiClient.post<ApiResponse<AlertEvent>>(`/alerts/${id}/acknowledge`)
    return response.data.data
  },
  async webhook() {
    const response = await apiClient.get<ApiResponse<WebhookConfig>>('/alerts/webhook')
    return response.data.data
  },
  async updateWebhook(enabled: boolean, url?: string) {
    const response = await apiClient.put<ApiResponse<WebhookConfig>>('/alerts/webhook', { enabled, url: url || null })
    return response.data.data
  },
  async routes() {
    const response = await apiClient.get<ApiResponse<AlertRoute[]>>('/alerts/routes')
    return response.data.data
  },
  async createRoute(payload: AlertRoutePayload) {
    const response = await apiClient.post<ApiResponse<AlertRoute>>('/alerts/routes', payload)
    return response.data.data
  },
  async updateRoute(id: string, payload: AlertRoutePayload) {
    const response = await apiClient.put<ApiResponse<AlertRoute>>(`/alerts/routes/${id}`, payload)
    return response.data.data
  },
  async deleteRoute(id: string) {
    await apiClient.delete(`/alerts/routes/${id}`)
  },
  async maintenanceWindows() {
    const response = await apiClient.get<ApiResponse<MaintenanceWindow[]>>('/alerts/maintenance-windows')
    return response.data.data
  },
  async createMaintenanceWindow(payload: MaintenanceWindowPayload) {
    const response = await apiClient.post<ApiResponse<MaintenanceWindow>>('/alerts/maintenance-windows', payload)
    return response.data.data
  },
  async deleteMaintenanceWindow(id: string) {
    await apiClient.delete(`/alerts/maintenance-windows/${id}`)
  },
}
