import { apiClient, type ApiResponse } from './client'

export interface ServiceTemplate {
  id: string
  name: string
  shortName: string
  category: string
  description: string
  image: string
  version: string
  containerPort: number
  recommendedPort: number
  memoryLimitBytes: number
  persistentData: string[]
  documentationUrl: string
  sourceUrl: string
  setupHint: string
  accent: 'mint' | 'blue' | 'violet'
}
export type InstallationStatus = 'REQUESTED' | 'CLAIMED' | 'DISCOVERING' | 'READY' | 'FAILED'

export interface ServiceInstallation {
  id: string
  templateId: string
  templateName: string
  image: string
  displayName: string
  instanceName: string
  environment: string
  serverId: string
  serverName: string
  requestedPort: number
  hostPort: number | null
  timezone: string
  containerId: string | null
  applicationId: string | null
  status: InstallationStatus
  errorMessage: string | null
  requestedAt: string
  completedAt: string | null
  updatedAt: string
}

export interface InstallServicePayload {
  serverId: string
  displayName: string
  instanceName: string
  environment: string
  hostPort: number
  timezone: string
}

export const serviceTemplateApi = {
  async catalog() {
    const response = await apiClient.get<ApiResponse<ServiceTemplate[]>>('/service-templates')
    return response.data.data
  },

  async installations() {
    const response = await apiClient.get<ApiResponse<ServiceInstallation[]>>('/service-templates/installations')
    return response.data.data
  },

  async install(templateId: string, payload: InstallServicePayload) {
    const response = await apiClient.post<ApiResponse<ServiceInstallation>>(
      `/service-templates/${templateId}/installations`, payload,
    )
    return response.data.data
  },
}
