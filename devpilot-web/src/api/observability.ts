import { apiClient, type ApiResponse } from './client'

export interface ObservabilityStatus {
  prometheusEnabled: boolean
  prometheusPath: string
  prometheusAuthentication: string
  otlpEnabled: boolean
  otlpProtocol: string
  snapshotIntervalSeconds: number
}

export const observabilityApi = {
  async status() {
    const response = await apiClient.get<ApiResponse<ObservabilityStatus>>('/observability/status')
    return response.data.data
  },
}
