import test from 'node:test'
import assert from 'node:assert/strict'
import { releaseGuidanceFor } from './releaseGuidance.ts'

test('approval guidance requires DevPilot confirmation before provider-specific dispatch', () => {
  const run = { status: 'SUCCEEDED', deployStatus: 'AWAITING_APPROVAL' }
  for (const provider of ['GITHUB', 'GITLAB', 'OTHER']) {
    const text = releaseGuidanceFor(true, run, provider)
    assert.match(text, /先在本页.*人工确认/)
    assert.match(text, /应用尚未因此更新/)
    if (provider === 'GITLAB') { assert.match(text, /DEVPILOT_MANUAL_APPROVAL_ID/); assert.doesNotMatch(text, /GitHub/) }
    if (provider === 'GITHUB') {
      assert.match(text, /原构建 Run ID 和确认 ID/)
      assert.match(text, /operation=release/)
      assert.match(text, /build_run_id、manual_approval_id/)
    }
  }
})

test('unknown, cancelled and failed states never promise a healthy running version', () => {
  assert.match(releaseGuidanceFor(true, { status: 'RUNNING', deployStatus: 'BUILDING', observationStatus: 'STALE' }), /当前结果未知/)
  assert.match(releaseGuidanceFor(true, { status: 'CANCELLED', deployStatus: 'BUILD_FAILED' }), /已报告取消，不代表测试失败/)
  for (const deployStatus of ['FAILED', 'HEALTH_FAILED', 'UNHEALTHY']) {
    assert.match(releaseGuidanceFor(true, { status: 'SUCCEEDED', deployStatus }), /不能假定旧版本仍在运行/)
  }
  assert.match(releaseGuidanceFor(true, { status: 'SUCCEEDED', deployStatus: 'HEALTHY' }), /当前是否仍健康/)
  assert.match(releaseGuidanceFor(true, { status: 'SUCCEEDED', deployStatus: 'ROLLBACK_FAILED' }), /尚未确认恢复/)
  assert.match(releaseGuidanceFor(true, { status: 'SUCCEEDED', deployStatus: 'ROLLBACK_TRIGGERED' }), /尚未确认恢复/)
})
