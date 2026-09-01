import { apiClient, type ApiResponse } from './client'
import type { MetricPoint, MetricRange } from './metrics'

export interface MonitorServer {
  id: string
  name: string
  hostname: string | null
  ip: string | null
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  cpuCores: number | null
  memoryTotal: string | null
  diskTotal: string | null
  lastHeartbeat: string | null
  current: MetricPoint | null
}

export interface MonitorSummary {
  serverTotal: number
  serverOnline: number
  reportingServers: number
  averageCpuUsage: number
  averageMemoryUsage: number
  averageDiskUsage: number
  networkUploadRate: number
  networkDownloadRate: number
}

export interface MonitorData {
  summary: MonitorSummary
  range: MetricRange
  trend: MetricPoint[]
  servers: MonitorServer[]
}

export const monitorApi = {
  async get(range: MetricRange) {
    const response = await apiClient.get<ApiResponse<MonitorData>>('/monitor', { params: { range } })
    return response.data.data
  },
}
