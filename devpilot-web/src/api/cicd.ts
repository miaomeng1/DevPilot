import { apiClient, type ApiResponse } from './client'

export type RepositoryProvider = 'GITHUB' | 'GITLAB' | 'WOODPECKER'
export type DeploymentProvider = 'COOLIFY' | 'DOKPLOY'
export type DeploymentMode = 'WEBHOOK' | 'API'
export type GateStatus = 'PENDING' | 'PASSED' | 'FAILED' | 'SKIPPED'
export type PipelineStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface CicdConfiguration {
  id: string
  applicationId: string
  applicationCode: string
  repositoryProvider: RepositoryProvider
  repositoryUrl: string
  branchName: string
  deploymentProvider: DeploymentProvider
  deploymentMode: DeploymentMode
  deploymentWebhookConfigured: boolean
  providerBaseUrlConfigured: boolean
  providerApiTokenConfigured: boolean
  providerResourceId: string | null
  callbackSecretConfigured: boolean
  autoDeploy: boolean
  productionApproval: boolean
  autoRollback: boolean
  healthTimeoutSeconds: number
  callbackUrl: string
  oneTimeCallbackSecret: string | null
  updatedAt: string
}

export interface CicdConfigurationPayload {
  repositoryProvider: RepositoryProvider
  repositoryUrl: string
  branchName: string
  deploymentProvider: DeploymentProvider
  deploymentMode: DeploymentMode
  deploymentWebhookUrl: string
  providerBaseUrl: string
  providerApiToken: string
  providerResourceId: string
  autoDeploy: boolean
  productionApproval: boolean
  autoRollback: boolean
  healthTimeoutSeconds: number
  rotateCallbackSecret: boolean
}

export interface PipelineRun {
  id: string
  applicationId: string
  externalRunId: string
  commitSha: string
  branchName: string
  status: PipelineStatus
  testStatus: GateStatus
  securityStatus: GateStatus
  imageUri: string | null
  imageDigest: string | null
  runUrl: string | null
  summary: string | null
  deployStatus: 'NOT_STARTED' | 'TRIGGERING' | 'TRIGGERED' | 'HEALTHY' | 'HEALTH_FAILED' | 'FAILED'
  deployError: string | null
  startedAt: string
  completedAt: string | null
  updatedAt: string
}

export interface CicdDeployment {
  id: string
  applicationId: string
  pipelineRunId: string | null
  rollbackOfId: string | null
  deploymentKind: 'RELEASE' | 'ROLLBACK'
  provider: DeploymentProvider
  imageUri: string
  previousImageUri: string | null
  status: 'TRIGGERING' | 'TRIGGERED' | 'HEALTHY' | 'UNHEALTHY' | 'FAILED' | 'ROLLBACK_TRIGGERED'
  providerDeploymentId: string | null
  logs: string | null
  startedAt: string
  healthDeadlineAt: string
  completedAt: string | null
  updatedAt: string
}

export const cicdApi = {
  async configuration(applicationId: string) {
    const response = await apiClient.get<ApiResponse<CicdConfiguration>>(`/cicd/configurations/${applicationId}`)
    return response.data.data
  },
  async saveConfiguration(applicationId: string, payload: CicdConfigurationPayload) {
    const response = await apiClient.put<ApiResponse<CicdConfiguration>>(`/cicd/configurations/${applicationId}`, payload)
    return response.data.data
  },
  async runs(applicationId: string) {
    const response = await apiClient.get<ApiResponse<PipelineRun[]>>(`/cicd/applications/${applicationId}/runs`)
    return response.data.data
  },
  async deployments(applicationId: string) {
    const response = await apiClient.get<ApiResponse<CicdDeployment[]>>(`/cicd/applications/${applicationId}/deployments`)
    return response.data.data
  },
  async rollback(applicationId: string, deploymentId: string) {
    const response = await apiClient.post<ApiResponse<CicdDeployment>>(`/cicd/applications/${applicationId}/deployments/${deploymentId}/rollback`)
    return response.data.data
  },
}
