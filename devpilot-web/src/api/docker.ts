import { apiClient, type ApiResponse } from './client'

export interface DockerOverview {
  serverId: string | null
  available: boolean
  engineVersion: string | null
  errorMessage: string | null
  containers: number
  running: number
  stopped: number
  images: number
  volumes: number
  networks: number
  collectedAt: string | null
}

export interface DockerContainer {
  id: string
  serverId: string
  containerId: string
  shortId: string
  name: string
  image: string
  state: string
  status: string | null
  health: string | null
  cpuUsage: number
  memoryUsage: string
  memoryLimit: string
  networkRx: string
  networkTx: string
  ipAddress: string | null
  ports: string[]
  createdAt: string | null
  startedAt: string | null
  restartCount: number
  networkMode: string | null
  composeProject: string | null
  composeService: string | null
  volumes: string[]
  environment: string[]
  lastSeenAt: string
}

export type DockerAction = 'start' | 'stop' | 'restart'
export type CommandStatus = 'REQUESTED' | 'CLAIMED' | 'SUCCEEDED' | 'FAILED'

export interface DockerCommand {
  id: string
  serverId: string
  containerId: string
  action: string
  status: CommandStatus
  errorMessage: string | null
  requestedAt: string
  completedAt: string | null
}

export interface LogTicket {
  webSocketPath: string
  expiresAt: string
}

export const dockerApi = {
  async overview(serverId?: string) {
    const response = await apiClient.get<ApiResponse<DockerOverview>>('/docker/overview', {
      params: serverId ? { serverId } : undefined,
    })
    return response.data.data
  },

  async list(serverId?: string) {
    const response = await apiClient.get<ApiResponse<DockerContainer[]>>('/docker/containers', {
      params: serverId ? { serverId } : undefined,
    })
    return response.data.data
  },

  async get(id: string) {
    const response = await apiClient.get<ApiResponse<DockerContainer>>(`/docker/containers/${id}`)
    return response.data.data
  },

  async operate(id: string, action: DockerAction) {
    const response = await apiClient.post<ApiResponse<DockerCommand>>(`/docker/containers/${id}/${action}`)
    return response.data.data
  },

  async remove(id: string, confirmName: string) {
    const response = await apiClient.post<ApiResponse<DockerCommand>>(`/docker/containers/${id}/remove`, { confirmName })
    return response.data.data
  },

  async command(id: string) {
    const response = await apiClient.get<ApiResponse<DockerCommand>>(`/docker/commands/${id}`)
    return response.data.data
  },

  async logTicket(id: string, lines: 100 | 500, follow: boolean) {
    const response = await apiClient.post<ApiResponse<LogTicket>>(`/docker/containers/${id}/logs/ticket`, {
      lines, follow,
    })
    return response.data.data
  },
}
