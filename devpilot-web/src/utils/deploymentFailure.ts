export interface FailedDeployment {
  status: string
  deploymentKind: string
  logs: string | null
}

// Logs are untrusted clues, not proof of current runtime state. Never echo them
// into guidance or infer that a registry-wide login grants access to an image.
export function deploymentFailureFor(deployment: FailedDeployment) {
  if (!['FAILED', 'UNHEALTHY', 'ROLLBACK_TRIGGERED', 'ROLLED_BACK', 'ROLLBACK_FAILED'].includes(deployment.status)) return null
  const log = deployment.logs || ''
  let stage = '部署平台 Deployment'
  let reason = '该次部署未通过，现有证据不足以确定具体原因。'
  let next = '展开采集日志，并按平台任务 ID 核对结果；先确认远端是否已执行，再决定是否重新确认发布。'
  if (/refusing to create a tag with a digest reference/i.test(log)) {
    reason = '日志提示平台尝试对 digest 镜像再次打标签。'
    next = '检查 Dokploy 应用是否启用了构建后的 Registry 推送；已构建镜像应使用拉取凭据部署原 digest，不要改成可变 tag 或扩大推送权限。'
  } else if (/(pulling image failed|manifest unknown|manifest_unknown|pull access denied|unauthorized.*(?:manifest|ghcr)|(?:manifest|ghcr).*unauthorized)/i.test(log)) {
    stage = '镜像拉取 Image pull'
    reason = '日志包含镜像读取失败信号；可能是凭据、包权限或镜像引用问题，不能仅据此认定镜像已删除。'
    next = '核对精确 digest 和应用级 Registry 拉取凭据，并在目标服务器验证该镜像可拉取。GHCR 使用有 read:packages 的 Classic PAT；仅保存或 login 成功不足以证明可拉取。'
  } else if (/port is already allocated|address already in use|port.*already in use/i.test(log)) {
    reason = '日志提示端口占用冲突。'
    next = '检查目标服务器的端口映射和占用服务；确认归属后调整本应用发布端口，不要直接停止未知服务。'
  } else if (/Expected image was not observed/i.test(log)) {
    stage = '运行版本验证 Runtime'
    reason = '未验证到批准镜像对应的运行容器。'
    next = '检查 Agent 在线状态、容器关联、实际镜像 digest 和平台任务；旧容器健康不能证明新版本已上线。'
  } else if (/Application health check reported UNHEALTHY|Health verification timed out/i.test(log)) {
    stage = '健康验证 Health'
    reason = /Application health check reported UNHEALTHY/i.test(log) ? '新的健康探测报告不健康。' : '期限内没有获得所需的健康验证证据，不等于已确认业务故障。'
    next = '检查 Agent 数据时间、健康地址和端口、容器日志及启动耗时；核对回滚记录后再选择恢复操作。'
  } else if (/Deployment provider did not complete before the health deadline/i.test(log)) {
    reason = '期限内未确认平台完成部署，远端可能仍在执行。'
    next = '按平台任务 ID 核对实际状态并重新验证连接；不要因观察超时立即重复部署。'
  }
  const recovery = deployment.status === 'ROLLED_BACK'
    ? '已有历史恢复成功记录，当前状态仍需核对最新运行证据。'
    : deployment.status === 'ROLLBACK_TRIGGERED'
      ? '已触发回滚，尚未确认恢复；查看关联回滚记录。'
      : deployment.status === 'ROLLBACK_FAILED' || deployment.deploymentKind === 'ROLLBACK'
        ? '回滚未成功，尚未确认恢复；请人工核对实际运行版本。'
        : '不能假定旧版本仍在运行；请核对应用的实际镜像、容器和数据更新时间。'
  return { stage, reason, next, recovery }
}
