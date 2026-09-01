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

export const serverApi = {
  async list() {
    const response = await apiClient.get<ApiResponse<ServerNode[]>>('/servers')
    return response.data.data
  },

  async get(id: string) {
    const response = await apiClient.get<ApiResponse<ServerNode>>(`/servers/${id}`)
    return response.data.data
  },

  async create(name: string) {
    const response = await apiClient.post<ApiResponse<CreateServerResult>>('/servers', { name })
    return response.data.data
  },

  async delete(id: string) {
    await apiClient.delete(`/servers/${id}`)
  },
}
