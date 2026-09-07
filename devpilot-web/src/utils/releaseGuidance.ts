// Guidance must describe evidence, never promise that an old container survived.
export interface GuidanceRun {
  status: string
  deployStatus: string
  observationStatus?: string
  observationMessage?: string | null
}

export function releaseGuidanceFor(configured: boolean, run: GuidanceRun | null | undefined, provider?: string): string {
  if (!configured) return '先完成仓库与部署平台配置，DevPilot 才能接收签名流水线回调。'
  if (!run) return '配置已就绪。向受保护分支推送代码，开始第一条流水线。'
  if (run.observationStatus === 'STALE') return run.observationMessage || 'CI 状态上报已过期，当前结果未知。请打开构建任务核对；不要把此状态当成仍在执行或发布成功。'
  if (run.status === 'CANCELLED') return 'CI 任务已报告取消，不代表测试失败。请核对构建任务；需要再次构建时重新运行 CI，新构建仍须人工确认后发布。'
  if (run.deployStatus === 'BUILDING') return 'CI 已开始构建，尚未发布；等待测试、扫描和镜像构建结果。'
  if (run.deployStatus === 'BUILD_FAILED') return '构建未通过，不会部署。打开构建任务查看失败或跳过的阶段。'
  if (run.deployStatus === 'AWAITING_APPROVAL') {
    const next = provider === 'GITHUB'
      ? '再到 GitHub Run workflow 选择 operation=release，填写原构建 Run ID 和确认 ID（build_run_id、manual_approval_id）。'
      : provider === 'GITLAB'
        ? '再到原 GitLab 流水线运行 production 手动作业，填写 DEVPILOT_MANUAL_APPROVAL_ID。'
        : '再按照对应 CI 的发布指引提交确认 ID。'
    return `构建通过，应用尚未因此更新。请先在本页核对版本和目标并完成人工确认，${next}`
  }
  if (run.deployStatus === 'HEALTHY') return '该次发布已通过部署后健康验证。当前是否仍健康，请核对应用状态和数据更新时间。'
  if (['FAILED', 'HEALTH_FAILED', 'UNHEALTHY'].includes(run.deployStatus)) return '最新发布未通过。请核对当前实际镜像、容器和健康状态，再查看错误及回滚记录；不能假定旧版本仍在运行。'
  if (run.deployStatus === 'ROLLBACK_TRIGGERED') return '已触发回滚，尚未确认恢复。请查看关联回滚任务和最新运行证据，不要重复触发恢复操作。'
  if (run.deployStatus === 'ROLLED_BACK') return '已记录回滚恢复结果。请核对恢复版本和最新健康状态，再修复故障版本；不要直接重复发布。'
  if (run.deployStatus === 'ROLLBACK_FAILED') return '回滚失败，尚未确认恢复。请检查实际容器、镜像、健康检查和部署平台日志，再选择恢复操作。'
  if (run.deployStatus === 'QUEUED') return '已有版本正在发布；当前版本已进入持久队列，前一个发布结束后会重新校验发布条件。'
  if (['TRIGGERED', 'VERIFYING', 'TRIGGERING'].includes(run.deployStatus)) return '发布正在执行，DevPilot 会等待 Provider 完成并使用新的 Agent 探测结果验证。'
  return '请核对 CI 与部署记录。生产发布需要在 DevPilot 完成人工确认；未知状态不代表上线成功。'
}
