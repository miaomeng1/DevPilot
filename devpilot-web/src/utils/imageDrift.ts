interface RuntimeEvidence {
  dockerImage: string | null
  agentStatus?: string
  containerObservedAt?: string | null
}

export function imageDriftFor(expected: string, runtime: RuntimeEvidence | null | undefined, now = Date.now()) {
  const unknown = (label: string, detail: string) => ({ state: 'unknown', label, detail })
  if (!expected) return unknown('尚无健康基线', '完成首次健康发布后开始检测')
  if (!runtime?.dockerImage) return unknown('等待运行清单', 'Agent 尚未上报实际运行镜像')
  if (runtime.agentStatus !== 'ONLINE') return unknown('运行证据不可用', 'Agent 不在线或状态未知，不能判断当前镜像一致性')
  const timestamp = runtime.containerObservedAt || ''
  // Backend LocalDateTime fields are UTC and do not carry an offset.
  const observed = Date.parse(/(?:Z|[+-]\d\d:\d\d)$/.test(timestamp) ? timestamp : `${timestamp}Z`)
  if (!Number.isFinite(observed) || now - observed > 60_000 || observed > now + 5_000) {
    return unknown('运行清单已过期', '需要最近 60 秒的容器上报；此状态不表示应用故障')
  }
  const actual = runtime.dockerImage
  const digest = (value: string) => value.match(/@sha256:([a-f0-9]{64})$/)?.[1]
  const wanted = digest(expected)
  const reported = digest(actual)
  if (!wanted || !reported) return unknown('摘要证据不足', `期望 ${expected} · 上报 ${actual}；标签或本地镜像 ID 不能证明 digest 一致或漂移`)
  if (wanted === reported) return { state: 'synced', label: '镜像摘要一致 In sync', detail: `${actual} · 仅代表本次上报，不等同于应用健康` }
  return { state: 'drift', label: '检测到镜像摘要漂移 Drift', detail: `期望 ${expected} · 实际 ${actual}` }
}
