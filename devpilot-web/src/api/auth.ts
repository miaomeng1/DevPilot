import { apiClient, type ApiResponse } from './client'

export type UserRole = 'ADMIN' | 'DEVELOPER' | 'VIEWER'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  roles: UserRole[]
}

export interface AuthTokens {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: AuthUser
}

export interface SetupStatus {
  setupRequired: boolean
}

export interface LoginPayload {
  username: string
  password: string
}

export interface SetupPayload extends LoginPayload {
  confirmPassword: string
  displayName: string
  email?: string
}

export const authApi = {
  async setupStatus() {
    const response = await apiClient.get<ApiResponse<SetupStatus>>('/auth/setup/status')
    return response.data.data
  },

  async setup(payload: SetupPayload) {
    const response = await apiClient.post<ApiResponse<AuthTokens>>('/auth/setup', payload)
    return response.data.data
  },

  async login(payload: LoginPayload) {
    const response = await apiClient.post<ApiResponse<AuthTokens>>('/auth/login', payload)
    return response.data.data
  },

  async refresh() {
    const response = await apiClient.post<ApiResponse<AuthTokens>>('/auth/refresh', {})
    return response.data.data
  },

  async logout() {
    await apiClient.post<ApiResponse<null>>('/auth/logout', {})
  },

  async changePassword(currentPassword: string, newPassword: string, confirmPassword: string) {
    await apiClient.put<ApiResponse<null>>('/auth/password', { currentPassword, newPassword, confirmPassword })
  },
}
