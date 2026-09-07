import { apiClient, type ApiResponse } from './client'

export type AgentStatus = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'

export interface ServerNode {
  id: string
  name: string
  hostname: string | null
  ip: string | null
  os: string | null
  kernel: string | null
  architecture: string | null
  cpuModel: string | null
  cpuCores: number | null
  memoryTotal: string | null
  diskTotal: string | null
  agentVersion: string | null
  status: AgentStatus
  lastHeartbeat: string | null
  registeredAt: string | null
  createdAt: string
}

export interface CreateServerResult {
  server: ServerNode
  agentToken: string
  installCommand: string
}

export interface ServerCreationState {
  serverId: string
  name: string
  status: 'AVAILABLE' | 'EXPIRED' | 'UNAVAILABLE' | 'DELETED'
}

export const serverApi = {
  async creationState(requestId: string) {
    const response = await apiClient.get<ApiResponse<ServerCreationState>>(`/servers/creation-requests/${requestId}`)
    return response.data.data
  },
  async list() {
    const response = await apiClient.get<ApiResponse<ServerNode[]>>('/servers')
    return response.data.data
  },

  async get(id: string) {
    const response = await apiClient.get<ApiResponse<ServerNode>>(`/servers/${id}`)
    return response.data.data
  },

  async create(name: string, requestId?: string) {
    const response = await apiClient.post<ApiResponse<CreateServerResult>>('/servers', { name, requestId })
    return response.data.data
  },

  async delete(id: string) {
    await apiClient.delete(`/servers/${id}`)
  },

  async registration(id: string) {
    const response = await apiClient.get<ApiResponse<{ revision: string }>>(`/servers/${id}/registration`)
    return response.data.data
  },

  async renewToken(id: string, request: { requestId: string; expectedRevision: string; confirmed: boolean }) {
    const response = await apiClient.post<ApiResponse<CreateServerResult>>(`/servers/${id}/registration`, request)
    return response.data.data
  },
}
