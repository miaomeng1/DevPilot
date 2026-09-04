import { apiClient, type ApiResponse } from './client'

export interface CapacityServer {
  serverId: string
  serverName: string
  hostname: string | null
  architecture: string | null
  eligible: boolean
  recommended: boolean
  score: number
  grade: 'EXCELLENT' | 'GOOD' | 'TIGHT' | 'RISKY' | 'BLOCKED'
  cpuUsage: number | null
  loadPerCore: number | null
  memoryUsage: number | null
  projectedMemoryUsage: number | null
  memoryAvailableAfter: string | null
  diskUsage: number | null
  projectedDiskUsage: number | null
  diskFreeAfter: string | null
  runningContainers: number
  activeAlerts: number
  criticalAlerts: number
  metricAt: string | null
  blockers: string[]
  observations: string[]
}

export interface CapacityPlan {
  requiredMemoryBytes: string
  requiredDiskBytes: string
  recommendedServerId: string | null
  verdict: 'SAFE' | 'CAUTION' | 'TIGHT' | 'BLOCKED'
  summary: string
  servers: CapacityServer[]
}

export const capacityApi = {
  async plan(memoryBytes: number, diskBytes: number) {
    const response = await apiClient.get<ApiResponse<CapacityPlan>>('/capacity/plan', {
      params: { memoryBytes, diskBytes },
    })
    return response.data.data
  },
}
