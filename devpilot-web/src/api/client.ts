import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface ApiErrorBody {
  code: number
  message: string
  data: null
}

interface RetryableRequest extends InternalAxiosRequestConfig {
  _retriedAfterRefresh?: boolean
}

interface RefreshPayload {
  accessToken: string
}

let accessToken: string | null = null
let refreshInFlight: Promise<string> | null = null
let authenticationLostHandler: (() => void) | null = null

export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
})

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function onAuthenticationLost(handler: () => void) {
  authenticationLostHandler = handler
}

apiClient.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiErrorBody>) => {
    const request = error.config as RetryableRequest | undefined
    const isAuthenticationEndpoint = request?.url?.includes('/auth/') ?? false
    if (error.response?.status !== 401 || !request || request._retriedAfterRefresh || isAuthenticationEndpoint) {
      return Promise.reject(error)
    }

    request._retriedAfterRefresh = true
    try {
      if (!refreshInFlight) {
        refreshInFlight = axios
          .post<ApiResponse<RefreshPayload>>('/api/auth/refresh', {}, { withCredentials: true })
          .then((response) => response.data.data.accessToken)
          .finally(() => {
            refreshInFlight = null
          })
      }
      const token = await refreshInFlight
      setAccessToken(token)
      request.headers.Authorization = `Bearer ${token}`
      return apiClient.request(request)
    } catch (refreshError) {
      setAccessToken(null)
      authenticationLostHandler?.()
      return Promise.reject(refreshError)
    }
  },
)

export function apiErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    return error.response?.data?.message || fallback
  }
  return error instanceof Error ? error.message : fallback
}
