import { apiClient, type ApiResponse } from './client'

export type MetricRange = '1h' | '6h' | '24h' | '7d'

export interface MetricPoint {
  timestamp: string
  cpuUsage: number
  loadOne: number
  loadFive: number
  loadFifteen: number
  memoryTotal: string
  memoryUsed: string
  memoryAvailable: string
  memoryUsage: number
  diskTotal: string
  diskUsed: string
  diskFree: string
  diskUsage: number
  networkBytesSent: string
  networkBytesReceived: string
  networkUploadRate: number
  networkDownloadRate: number
}

export interface MetricHistory {
  serverId: string
  range: MetricRange
  resolutionSeconds: number
  current: MetricPoint | null
  points: MetricPoint[]
}

export const metricApi = {
  async history(serverId: string, range: MetricRange) {
    const response = await apiClient.get<ApiResponse<MetricHistory>>(`/servers/${serverId}/metrics`, {
      params: { range },
    })
    return response.data.data
  },
}
