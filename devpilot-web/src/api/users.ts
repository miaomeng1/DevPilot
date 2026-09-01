import { apiClient, type ApiResponse } from './client'
import type { UserRole } from './auth'

export interface ManagedUser {
  id: string
  username: string
  displayName: string
  email: string | null
  role: UserRole
  status: 'ACTIVE' | 'DISABLED'
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateUserPayload {
  username: string; displayName: string; email: string; role: UserRole; password: string; confirmPassword: string
}

export const usersApi = {
  async list() {
    const response = await apiClient.get<ApiResponse<ManagedUser[]>>('/users')
    return response.data.data
  },
  async create(payload: CreateUserPayload) {
    const response = await apiClient.post<ApiResponse<ManagedUser>>('/users', payload)
    return response.data.data
  },
  async update(id: string, payload: Pick<ManagedUser, 'displayName' | 'email' | 'role' | 'status'>) {
    const response = await apiClient.put<ApiResponse<ManagedUser>>(`/users/${id}`, payload)
    return response.data.data
  },
  async resetPassword(id: string, password: string, confirmPassword: string) {
    await apiClient.put(`/users/${id}/password`, { password, confirmPassword })
  },
  async delete(id: string) { await apiClient.delete(`/users/${id}`) },
}
