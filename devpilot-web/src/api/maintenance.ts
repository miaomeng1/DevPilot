import { apiClient, type ApiResponse } from './client'

export interface BackupReport {
  id: string
  fileName: string
  sizeBytes: string
  sha256: string
  destinationType: 'LOCAL' | 'S3' | 'RCLONE'
  createdAt: string
  verifiedAt: string
  reportedAt: string
}

export interface BackupOverview {
  reportingConfigured: boolean
  state: 'NOT_CONFIGURED' | 'NO_BACKUP' | 'HEALTHY' | 'STALE'
  freshnessHours: number
  ageHours: number | null
  latest: BackupReport | null
  latestDrill: RestoreDrill | null
  reports: BackupReport[]
}

export interface RestoreDrill {
  id: string
  backupReportId: string | null
  backupFileName: string | null
  environment: 'ISOLATED' | 'STAGING'
  result: 'PASSED' | 'FAILED'
  notes: string | null
  performedBy: string | null
  performedByName: string | null
  performedAt: string
}

export interface RestoreDrillPayload {
  backupReportId: string
  environment: 'ISOLATED' | 'STAGING'
  result: 'PASSED' | 'FAILED'
  notes: string | null
}

export const maintenanceApi = {
  async backups() {
    const response = await apiClient.get<ApiResponse<BackupOverview>>('/maintenance/backups')
    return response.data.data
  },
  async recordRestoreDrill(payload: RestoreDrillPayload) {
    const response = await apiClient.post<ApiResponse<RestoreDrill>>('/maintenance/restore-drills', payload)
    return response.data.data
  },
}
