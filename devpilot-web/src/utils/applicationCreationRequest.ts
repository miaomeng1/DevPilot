import type { ApplicationPayload } from '../api/applications'

export interface PendingApplicationCreation { requestId: string; payload: ApplicationPayload }
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
function key(owner: string | undefined) {
  if (!owner) throw new Error('请先登录账号')
  return `devpilot.onboarding.application-create.${owner}`
}
function validate(value: PendingApplicationCreation) {
  const p = value?.payload
  if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value?.requestId || '') || !p
    || !/^[a-z][a-z0-9-]{1,63}$/.test(p.code || '') || typeof p.name !== 'string' || !p.name || p.name.length > 120
    || typeof p.serverId !== 'string' || !/^\d+$/.test(p.serverId) || p.environment !== 'PRODUCTION' || p.containerSnapshotId !== null
    || p.currentVersion !== '' || p.accessUrl !== '' || p.description !== '由自动接入向导配置'
    || !/^http:\/\/127\.0\.0\.1:\d{1,5}\/[^\s?#]*$/.test(p.healthCheckUrl)
    || Object.keys(p).sort().join(',') !== 'accessUrl,code,containerSnapshotId,currentVersion,description,environment,healthCheckUrl,name,serverId')
    throw new Error('原应用创建请求无效，请保留记录并核对已有应用，不能自动重新创建')
  const port = Number(new URL(p.healthCheckUrl).port)
  if (port < 1024 || port > 65535) throw new Error('发布端口必须在 1024–65535 范围内，请先修正参数')
  return value
}
export function readApplicationCreation(owner: string | undefined, store: Store = localStorage): PendingApplicationCreation | null {
  const raw = store.getItem(key(owner))
  return raw ? validate(JSON.parse(raw)) : null
}
export function beginApplicationCreation(owner: string | undefined, payload: ApplicationPayload, store: Store = localStorage) {
  const saved = readApplicationCreation(owner, store)
  if (saved) {
    if (Object.keys(saved.payload).some(k => saved.payload[k as keyof ApplicationPayload] !== payload[k as keyof ApplicationPayload]))
      throw new Error('有未完成的应用创建请求，请恢复原名称、编码、服务器和健康地址后重试；不要改编码重复创建')
    return saved
  }
  if (typeof crypto.randomUUID !== 'function') throw new Error('请通过 HTTPS 或 localhost 访问 DevPilot，浏览器当前无法生成安全的创建请求标识')
  const pending = validate({requestId: crypto.randomUUID(), payload: {...payload}})
  // Only non-secret application creation fields; never repository/registry credentials or business variables.
  store.setItem(key(owner), JSON.stringify(pending))
  return pending
}
export function completeApplicationCreation(owner: string | undefined, requestId: string, store: Store = localStorage) {
  if (readApplicationCreation(owner, store)?.requestId === requestId) store.removeItem(key(owner))
}
