import { apiClient, type ApiResponse } from './client'

export type ApplicationEnvironment = 'DEV' | 'TEST' | 'STAGING' | 'PRODUCTION'
export type ApplicationStatus = 'RUNNING' | 'WARNING' | 'ERROR' | 'OFFLINE' | 'UNKNOWN'
export type HealthStatus = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'

export interface Application {
  id: string
  name: string
  code: string
  description: string | null
  environment: ApplicationEnvironment
  serverId: string
  serverName: string
  deployType: 'DOCKER'
  containerSnapshotId: string | null
  containerId: string | null
  containerName: string | null
  dockerImage: string | null
  containerIpAddress: string | null
  ports: string[]
  currentVersion: string | null
  accessUrl: string | null
  healthCheckUrl: string | null
  status: ApplicationStatus
  healthStatus: HealthStatus
  healthMessage: string | null
  healthCheckedAt: string | null
  cpuUsage: number | null
  memoryUsage: string | null
  memoryLimit: string | null
  lastDeployedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ApplicationPayload {
  name: string
  code?: string
  description: string
  environment: ApplicationEnvironment
  serverId: string
  containerSnapshotId: string
  currentVersion: string
  healthCheckUrl: string
  accessUrl: string
}

export interface DeploymentRecord {
  id: string
  applicationId: string
  applicationName: string
  version: string
  serverId: string
  serverName: string
  dockerImage: string
  operatorId: string
  operatorName: string
  deployedAt: string
  result: 'SUCCESS' | 'FAILED'
  logs: string | null
}

export const applicationApi = {
  async list() {
    const response = await apiClient.get<ApiResponse<Application[]>>('/applications')
    return response.data.data
  },

  async get(id: string) {
    const response = await apiClient.get<ApiResponse<Application>>(`/applications/${id}`)
    return response.data.data
  },

  async create(payload: ApplicationPayload) {
    const response = await apiClient.post<ApiResponse<Application>>('/applications', payload)
    return response.data.data
  },

  async update(id: string, payload: ApplicationPayload) {
    const { code: _code, ...body } = payload
    const response = await apiClient.put<ApiResponse<Application>>(`/applications/${id}`, body)
    return response.data.data
  },

  async remove(id: string) {
    await apiClient.delete(`/applications/${id}`)
  },

  async deployments(id: string) {
    const response = await apiClient.get<ApiResponse<DeploymentRecord[]>>(`/applications/${id}/deployments`)
    return response.data.data
  },

  async recordDeployment(id: string, payload: { version: string, dockerImage: string, result: 'SUCCESS' | 'FAILED', logs: string }) {
    const response = await apiClient.post<ApiResponse<DeploymentRecord>>(`/applications/${id}/deployments`, payload)
    return response.data.data
  },
}
