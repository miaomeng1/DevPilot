export interface PendingRenewal { requestId: string; expectedRevision: string }
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
function key(owner: string | undefined, server: string) {
  if (!owner || !/^\d+$/.test(server)) throw new Error('请先登录并核对服务器')
  return `devpilot.agent-renewal.${owner}.${server}`
}
export function readRenewal(owner: string | undefined, server: string, store: Store = localStorage): PendingRenewal | null {
  const raw = store.getItem(key(owner, server))
  if (!raw) return null
  const value = JSON.parse(raw)
  if (!value || typeof value.requestId !== 'string'
      || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.requestId)
      || typeof value.expectedRevision !== 'string' || !/^(none|\d+)$/.test(value.expectedRevision)) {
    throw new Error('签发恢复标识损坏，请核对服务器后处理，不能自动再次轮换')
  }
  return { requestId: value.requestId, expectedRevision: value.expectedRevision }
}
export function beginRenewal(owner: string | undefined, server: string, revision: string, store: Store = localStorage): PendingRenewal {
  const existing = readRenewal(owner, server, store)
  if (existing) return existing
  if (!/^(none|\d+)$/.test(revision)) throw new Error('凭据版本无效，请重新核对服务器')
  const value = { requestId: crypto.randomUUID(), expectedRevision: revision }
  store.setItem(key(owner, server), JSON.stringify(value))
  return value
}
export function finishRenewal(owner: string | undefined, server: string, requestId: string, store: Store = localStorage) {
  if (readRenewal(owner, server, store)?.requestId === requestId) store.removeItem(key(owner, server))
}
