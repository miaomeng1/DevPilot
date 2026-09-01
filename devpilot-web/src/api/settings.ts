import { apiClient, type ApiResponse } from './client'

export interface PublicSettings {
  systemName: string
  logoUrl: string | null
  defaultTheme: 'DARK' | 'LIGHT' | 'SYSTEM'
  logDefaultLines: 100 | 500
}

export interface SystemSettings extends PublicSettings {
  accessTokenTtlMinutes: number
  refreshTokenTtlHours: number
  agentHeartbeatTimeoutSeconds: number
  metricIntervalSeconds: number
  webhookEnabled: boolean
  webhookConfigured: boolean
  webhookDestinationType: string
}

export interface UpdateSystemSettings {
  systemName: string
  logoUrl: string
  defaultTheme: 'DARK' | 'LIGHT' | 'SYSTEM'
  accessTokenTtlMinutes: number
  refreshTokenTtlHours: number
  agentHeartbeatTimeoutSeconds: number
  metricIntervalSeconds: number
  logDefaultLines: '100' | '500'
  webhookEnabled: boolean
  webhookUrl: string
}

export const settingsApi = {
  async publicSettings() {
    const response = await apiClient.get<ApiResponse<PublicSettings>>('/system/public-settings')
    return response.data.data
  },
  async get() {
    const response = await apiClient.get<ApiResponse<SystemSettings>>('/settings')
    return response.data.data
  },
  async update(payload: UpdateSystemSettings) {
    const response = await apiClient.put<ApiResponse<SystemSettings>>('/settings', payload)
    return response.data.data
  },
}
