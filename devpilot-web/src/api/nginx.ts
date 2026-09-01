import { apiClient, type ApiResponse } from './client'

export interface NginxHost {
  serverId: string
  serverName: string
  enabled: boolean
  available: boolean
  nginxVersion: string | null
  configPath: string | null
  errorMessage: string | null
  configCount: number
  collectedAt: string | null
}

export interface NginxConfigSummary {
  id: string
  serverId: string
  serverName: string
  filename: string
  contentHash: string
  contentBytes: number
  lastSeenAt: string
  updatedAt: string
}

export interface NginxConfig extends NginxConfigSummary {
  content: string
}

export type NginxCommandStatus = 'REQUESTED' | 'CLAIMED' | 'SUCCEEDED' | 'FAILED'

export interface NginxCommand {
  id: string
  serverId: string
  configId: string
  filename: string
  action: 'UPDATE' | 'ROLLBACK'
  status: NginxCommandStatus
  validationOutput: string | null
  errorMessage: string | null
  requestedAt: string
  completedAt: string | null
}

export interface NginxHistory {
  id: string
  configId: string
  filename: string
  oldContent: string
  newContent: string
  action: 'UPDATE' | 'ROLLBACK'
  operatorId: string
  operatorName: string
  commandId: string
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED'
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
}

export const nginxApi = {
  async hosts() {
    const response = await apiClient.get<ApiResponse<NginxHost[]>>('/nginx/hosts')
    return response.data.data
  },

  async configs(serverId?: string) {
    const response = await apiClient.get<ApiResponse<NginxConfigSummary[]>>('/nginx/configs', {
      params: serverId ? { serverId } : undefined,
    })
    return response.data.data
  },

  async get(id: string) {
    const response = await apiClient.get<ApiResponse<NginxConfig>>(`/nginx/configs/${id}`)
    return response.data.data
  },

  async update(id: string, content: string) {
    const response = await apiClient.put<ApiResponse<NginxCommand>>(`/nginx/configs/${id}`, { content })
    return response.data.data
  },

  async history(id: string) {
    const response = await apiClient.get<ApiResponse<NginxHistory[]>>(`/nginx/configs/${id}/history`)
    return response.data.data
  },

  async rollback(id: string, historyId: string) {
    const response = await apiClient.post<ApiResponse<NginxCommand>>(`/nginx/configs/${id}/history/${historyId}/rollback`)
    return response.data.data
  },

  async command(id: string) {
    const response = await apiClient.get<ApiResponse<NginxCommand>>(`/nginx/commands/${id}`)
    return response.data.data
  },
}
