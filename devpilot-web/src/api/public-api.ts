import { apiClient, type ApiResponse } from './client'

export interface ApiAccessToken {
  id: string
  name: string
  prefix: string
  scope: 'READ'
  status: 'ACTIVE' | 'REVOKED'
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  createdAt: string
}

export const publicApiAdmin = {
  async tokens() {
    const response = await apiClient.get<ApiResponse<ApiAccessToken[]>>('/api-tokens')
    return response.data.data
  },
  async createToken(name: string, expiresAt: string | null) {
    const response = await apiClient.post<ApiResponse<{ token: ApiAccessToken; oneTimeSecret: string }>>('/api-tokens', { name, expiresAt })
    return response.data.data
  },
  async revokeToken(id: string) {
    await apiClient.delete(`/api-tokens/${id}`)
  },
}
