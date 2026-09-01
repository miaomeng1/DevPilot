import { apiClient, type ApiResponse } from './client'

export interface AuditLog {
  id: string
  userId: string | null
  username: string | null
  action: string
  resourceType: string
  resourceId: string | null
  resourceName: string | null
  serverId: string | null
  serverName: string | null
  ipAddress: string | null
  requestParams: string | null
  result: 'SUCCESS' | 'FAILED'
  errorMessage: string | null
  occurredAt: string
}

export interface AuditPage { items: AuditLog[]; total: number; page: number; size: number }

export const auditApi = {
  async list(params: { action?: string; result?: string; query?: string; page: number; size: number }) {
    const response = await apiClient.get<ApiResponse<AuditPage>>('/audit', { params })
    return response.data.data
  },
  async actions() {
    const response = await apiClient.get<ApiResponse<string[]>>('/audit/actions')
    return response.data.data
  },
}
