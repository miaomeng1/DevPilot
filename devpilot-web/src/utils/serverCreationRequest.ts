export interface PendingServerCreation { name: string; requestId: string }
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
function key(owner: string | undefined) {
  if (!owner) throw new Error('请先登录管理员账号')
  return `devpilot.setup.server-create.${owner}`
}
export function readServerCreation(owner: string | undefined, store: Store = localStorage): PendingServerCreation | null {
  const raw = store.getItem(key(owner))
  if (!raw) return null
  const saved = JSON.parse(raw)
  if (!saved || typeof saved.name !== 'string' || saved.name.trim().length < 2 || saved.name.length > 100
      || typeof saved.requestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved.requestId)) {
    throw new Error('创建重试标识无效，请先核对已有服务器，不能自动重新创建')
  }
  return { name: saved.name, requestId: saved.requestId }
}
export function beginServerCreation(owner: string | undefined, name: string, store: Store = localStorage): PendingServerCreation {
  const existing = readServerCreation(owner, store)
  if (existing) {
    if (existing.name !== name.trim()) throw new Error('请先恢复尚未完成的服务器创建请求')
    return existing
  }
  const pending = { name: name.trim(), requestId: crypto.randomUUID() }
  // Store before sending any request. No credentials are persisted in the browser.
  store.setItem(key(owner), JSON.stringify(pending))
  return pending
}
export function completeServerCreation(owner: string | undefined, requestId: string, store: Store = localStorage) {
  if (readServerCreation(owner, store)?.requestId === requestId) store.removeItem(key(owner))
}
