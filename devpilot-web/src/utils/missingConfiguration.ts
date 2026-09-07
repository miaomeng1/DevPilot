// Only the explicit application configuration absence may become an empty form.
// Network, permission and server errors must retain the last known state.
export function missingConfiguration(error: unknown): null {
  const response = (error as { response?: { status?: number, data?: { code?: number } } } | null)?.response
  if (response?.status === 404 && response.data?.code === 40440) return null
  throw error
}
