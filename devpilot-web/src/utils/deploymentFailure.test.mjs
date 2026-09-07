import test from 'node:test'
import assert from 'node:assert/strict'
import { deploymentFailureFor } from './deploymentFailure.ts'

const guide = (logs, status = 'UNHEALTHY', deploymentKind = 'RELEASE') => deploymentFailureFor({ logs, status, deploymentKind })

test('successful, unknown and in-progress deployments do not diagnose historical log errors', () => {
  for (const status of ['HEALTHY', 'TRIGGERED', 'VERIFYING', 'TRIGGERING', 'UNKNOWN']) {
    assert.equal(guide('Pulling image failed', status), null)
  }
})

test('registry guidance preserves digest and distinguishes login from pull access', () => {
  for (const log of ['Pulling image failed', 'manifest unknown', 'MANIFEST_UNKNOWN', 'pull access denied', 'ghcr.io manifest unauthorized']) {
    const result = guide(log)
    assert.match(result.stage, /Image pull/)
    assert.match(result.reason, /不能仅据此认定镜像已删除/)
    assert.match(result.next, /应用级.*精确|精确.*应用级/)
    assert.match(result.next, /login 成功不足以证明可拉取/)
  }
  const digestTag = guide('refusing to create a tag with a digest reference')
  assert.match(digestTag.reason, /再次打标签/)
  assert.match(digestTag.next, /不要改成可变 tag/)
})

test('provider uncertainty, runtime mismatch, health failure and port conflict have distinct actions', () => {
  assert.match(guide('Deployment provider did not complete before the health deadline').next, /不要因观察超时立即重复部署/)
  assert.match(guide('Expected image was not observed').next, /旧容器健康不能证明/)
  assert.match(guide('Health verification timed out').reason, /不等于已确认业务故障/)
  assert.match(guide('Application health check reported UNHEALTHY').reason, /新的健康探测/)
  assert.match(guide('Bind: address already in use').next, /不要直接停止未知服务/)
  assert.match(guide(null).reason, /不足以确定/)
})

test('recovery state is separate from original failure and guidance never copies secrets or markup', () => {
  const secretLog = '<script>alert(1)</script> ghp_example_secret Pulling image failed'
  assert.doesNotMatch(JSON.stringify(guide(secretLog)), /ghp_example_secret|<script>/)
  assert.match(guide(secretLog).recovery, /不能假定旧版本仍在运行/)
  assert.match(guide(secretLog, 'ROLLBACK_TRIGGERED').recovery, /尚未确认恢复/)
  assert.match(guide(secretLog, 'ROLLED_BACK').recovery, /历史恢复成功.*最新运行证据/)
  assert.match(guide(secretLog, 'ROLLBACK_FAILED').recovery, /回滚未成功/)
  assert.match(guide(secretLog, 'FAILED', 'ROLLBACK').recovery, /回滚未成功/)
})
