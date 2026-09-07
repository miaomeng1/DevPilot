import test from 'node:test'
import assert from 'node:assert/strict'
import { matchesPipelineFilter } from './pipelineFilter.ts'

test('build and deployment failures remain discoverable without conflating cancellation', () => {
  for (const run of [
    { status: 'FAILED', deployStatus: 'BUILD_FAILED' },
    { status: 'FAILED', deployStatus: 'NOT_STARTED' },
    ...['FAILED', 'HEALTH_FAILED', 'ROLLED_BACK', 'ROLLBACK_FAILED'].map(deployStatus => ({ status: 'SUCCEEDED', deployStatus })),
  ]) assert.equal(matchesPipelineFilter(run, 'FAILED'), true)
  const cancelled = { status: 'CANCELLED', deployStatus: 'BUILD_FAILED' }
  assert.equal(matchesPipelineFilter(cancelled, 'CANCELLED'), true)
  for (const filter of ['FAILED', 'ACTIVE', 'APPROVAL', 'HEALTHY']) assert.equal(matchesPipelineFilter(cancelled, filter), false)
})

test('stale builds appear as unknown, not active or awaiting approval', () => {
  const run = { status: 'RUNNING', deployStatus: 'BUILDING', observationStatus: 'STALE' }
  for (const filter of ['ALL', 'UNKNOWN']) assert.equal(matchesPipelineFilter(run, filter), true)
  for (const filter of ['FAILED', 'ACTIVE', 'APPROVAL', 'HEALTHY', 'CANCELLED']) assert.equal(matchesPipelineFilter(run, filter), false)
  assert.equal(matchesPipelineFilter({ ...run, observationStatus: 'LAST_REPORTED' }, 'ACTIVE'), true)
})

test('approval, queued deployments and healthy history are separate views', () => {
  const approval = { status: 'SUCCEEDED', deployStatus: 'AWAITING_APPROVAL' }
  assert.equal(matchesPipelineFilter(approval, 'APPROVAL'), true)
  assert.equal(matchesPipelineFilter(approval, 'ACTIVE'), false)
  assert.equal(matchesPipelineFilter({ ...approval, status: 'RUNNING' }, 'APPROVAL'), false)
  for (const deployStatus of ['QUEUED', 'TRIGGERING', 'TRIGGERED', 'VERIFYING']) {
    assert.equal(matchesPipelineFilter({ status: 'SUCCEEDED', deployStatus }, 'ACTIVE'), true)
  }
  assert.equal(matchesPipelineFilter({ status: 'SUCCEEDED', deployStatus: 'HEALTHY' }, 'HEALTHY'), true)
})
