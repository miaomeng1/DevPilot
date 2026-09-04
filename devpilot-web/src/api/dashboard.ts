import { apiClient, type ApiResponse } from './client'
import type { MetricPoint } from './metrics'
import type { AlertEvent } from './alerts'
import type { Application, DeploymentRecord } from './applications'
import type { MonitorServer } from './monitor'

export type DashboardRange = '1h' | '6h' | '24h'

export interface DashboardSummary {
  serverTotal: number
  serverOnline: number
  containerTotal: number
  containerRunning: number
  applicationTotal: number
  applicationUnhealthy: number
  currentAlerts: number
  todayDeployments: number
  storageWarnings: number
  storageCritical: number
}

export interface DashboardData {
  summary: DashboardSummary
  range: DashboardRange
  trend: MetricPoint[]
  serverResources: MonitorServer[]
  serviceStatuses: Application[]
  recentDeployments: DeploymentRecord[]
  alerts: AlertEvent[]
}

export const dashboardApi = {
  async get(range: DashboardRange) {
    const response = await apiClient.get<ApiResponse<DashboardData>>('/dashboard', { params: { range } })
    return response.data.data
  },
}
