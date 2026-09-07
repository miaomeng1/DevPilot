export interface FilterablePipeline {
  status: string
  deployStatus: string
  observationStatus?: string
}

export type PipelineFilter = 'ALL' | 'HEALTHY' | 'FAILED' | 'CANCELLED' | 'ACTIVE' | 'UNKNOWN' | 'APPROVAL'

export function matchesPipelineFilter(run: FilterablePipeline, filter: PipelineFilter): boolean {
  if (filter === 'ALL') return true
  if (filter === 'UNKNOWN') return run.observationStatus === 'STALE'
  // Missing terminal evidence must not be presented as confirmed execution/failure.
  if (run.observationStatus === 'STALE') return false
  if (filter === 'CANCELLED') return run.status === 'CANCELLED'
  if (run.status === 'CANCELLED') return false
  if (filter === 'HEALTHY') return run.deployStatus === 'HEALTHY'
  if (filter === 'FAILED') return run.status === 'FAILED'
    || ['BUILD_FAILED', 'FAILED', 'HEALTH_FAILED', 'ROLLED_BACK', 'ROLLBACK_FAILED'].includes(run.deployStatus)
  if (filter === 'APPROVAL') return run.status === 'SUCCEEDED' && run.deployStatus === 'AWAITING_APPROVAL'
  if (filter === 'ACTIVE') return run.status === 'RUNNING'
    || ['BUILDING', 'QUEUED', 'TRIGGERING', 'TRIGGERED', 'VERIFYING'].includes(run.deployStatus)
  return false
}
