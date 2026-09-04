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
  deployStatus: 'NOT_STARTED' | 'QUEUED' | 'TRIGGERING' | 'TRIGGERED' | 'HEALTHY' | 'HEALTH_FAILED' | 'FAILED'
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
  promotedFromApplicationId: string | null
  promotedFromDeploymentId: string | null
  deploymentKind: 'RELEASE' | 'ROLLBACK' | 'PROMOTION'
  provider: DeploymentProvider
  imageUri: string
  previousImageUri: string | null
  status: 'TRIGGERING' | 'TRIGGERED' | 'VERIFYING' | 'HEALTHY' | 'UNHEALTHY' | 'FAILED' | 'ROLLBACK_TRIGGERED'
  providerDeploymentId: string | null
  logs: string | null
  startedAt: string
  healthDeadlineAt: string
  completedAt: string | null
  updatedAt: string
}

export interface CicdActivity {
  id: string
  applicationId: string
  applicationName: string
  environment: string
  serverId: string | null
  serverName: string
  deploymentKind: 'RELEASE' | 'ROLLBACK' | 'PROMOTION'
  promotedFromApplicationId: string | null
  promotedFromDeploymentId: string | null
  provider: DeploymentProvider
  imageUri: string
  status: CicdDeployment['status']
  logExcerpt: string | null
  startedAt: string
  completedAt: string | null
  updatedAt: string
}

export interface ApplicationEnvironmentVariable {
  key: string
  value: string | null
  secret: boolean
  configured: boolean
  description: string | null
}

export interface ApplicationEnvironment {
  applicationId: string
  revision: number
  syncedRevision: number | null
  variables: ApplicationEnvironmentVariable[]
  syncStatus: 'NOT_CONFIGURED' | 'DIRTY' | 'SYNCED' | 'FAILED'
  syncError: string | null
  providerSyncedAt: string | null
  updatedAt: string | null
}

export interface CicdReadinessCheck {
  code: string
  status: 'PASS' | 'WARN' | 'BLOCK'
  title: string
  detail: string
  action: 'CONFIGURE_CICD' | 'CONFIGURE_APPLICATION' | 'OPEN_SERVER' | 'MANAGE_ENVIRONMENT' | 'VIEW_PIPELINES' | null
}

export interface CicdReadiness {
  applicationId: string
  ready: boolean
  score: number
  blockerCount: number
  warningCount: number
  summary: string
  checkedAt: string
  checks: CicdReadinessCheck[]
}

export interface CicdPromotionTarget {
  applicationId: string
  applicationName: string
  environment: 'TEST' | 'STAGING' | 'PRODUCTION'
  serverId: string
  serverName: string
  accessUrl: string | null
  ready: boolean
  blockers: string[]
  currentHealthyImage: string | null
}

export interface SaveApplicationEnvironmentPayload {
  expectedRevision: number
  variables: Array<{ key: string; value: string | null; secret: boolean; description: string }>
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
  async readiness(applicationId: string) {
    const response = await apiClient.get<ApiResponse<CicdReadiness>>(`/cicd/applications/${applicationId}/readiness`)
    return response.data.data
  },
  async promotionTargets(applicationId: string) {
    const response = await apiClient.get<ApiResponse<CicdPromotionTarget[]>>(`/cicd/applications/${applicationId}/promotion-targets`)
    return response.data.data
  },
  async promote(applicationId: string, deploymentId: string, targetApplicationId: string) {
    const response = await apiClient.post<ApiResponse<CicdDeployment>>(
      `/cicd/applications/${applicationId}/deployments/${deploymentId}/promote`, { targetApplicationId },
    )
    return response.data.data
  },
  async activity(limit = 20) {
    const response = await apiClient.get<ApiResponse<CicdActivity[]>>('/cicd/activity', { params: { limit } })
    return response.data.data
  },
  async rollback(applicationId: string, deploymentId: string) {
    const response = await apiClient.post<ApiResponse<CicdDeployment>>(`/cicd/applications/${applicationId}/deployments/${deploymentId}/rollback`)
    return response.data.data
  },
  async environment(applicationId: string) {
    const response = await apiClient.get<ApiResponse<ApplicationEnvironment>>(`/cicd/applications/${applicationId}/environment`)
    return response.data.data
  },
  async saveEnvironment(applicationId: string, payload: SaveApplicationEnvironmentPayload) {
    const response = await apiClient.put<ApiResponse<ApplicationEnvironment>>(`/cicd/applications/${applicationId}/environment`, payload)
    return response.data.data
  },
  async syncEnvironment(applicationId: string) {
    const response = await apiClient.post<ApiResponse<ApplicationEnvironment>>(`/cicd/applications/${applicationId}/environment/sync`)
    return response.data.data
  },
}
