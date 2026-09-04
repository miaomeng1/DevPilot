import { apiClient, type ApiResponse } from './client'

export type AutomationEventType = 'ALERT_FIRING' | 'ALERT_RESOLVED' | 'DEPLOYMENT_HEALTHY' | 'DEPLOYMENT_FAILED'
export interface AutomationWebhook { id: string; name: string; endpointHost: string; eventTypes: AutomationEventType[]; enabled: boolean; createdAt: string; updatedAt: string }
export interface AutomationDelivery { id: string; eventId: string; subscriptionName: string; eventType: AutomationEventType; subject: string; status: string; attemptCount: number; responseCode: number | null; errorMessage: string | null; sentAt: string | null; createdAt: string; updatedAt: string }

export const automationApi = {
  async webhooks() { return (await apiClient.get<ApiResponse<AutomationWebhook[]>>('/automation/webhooks')).data.data },
  async deliveries() { return (await apiClient.get<ApiResponse<AutomationDelivery[]>>('/automation/webhooks/deliveries')).data.data },
  async create(name: string, endpointUrl: string, eventTypes: AutomationEventType[]) {
    return (await apiClient.post<ApiResponse<{ subscription: AutomationWebhook; oneTimeSecret: string }>>('/automation/webhooks', { name, endpointUrl, eventTypes })).data.data
  },
  async enabled(id: string, enabled: boolean) { return (await apiClient.put<ApiResponse<AutomationWebhook>>(`/automation/webhooks/${id}/enabled`, { enabled })).data.data },
  async remove(id: string) { await apiClient.delete(`/automation/webhooks/${id}`) },
  async retry(id: string) { await apiClient.post(`/automation/webhooks/deliveries/${id}/retry`) },
}
