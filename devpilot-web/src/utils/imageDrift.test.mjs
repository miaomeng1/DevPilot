import test from 'node:test'
import assert from 'node:assert/strict'
import { imageDriftFor } from './imageDrift.ts'

const now = Date.parse('2026-09-06T02:00:00Z')
const expected = 'ghcr.io/example/app@sha256:' + 'a'.repeat(64)
const runtime = { dockerImage: expected, agentStatus: 'ONLINE', containerObservedAt: '2026-09-06T01:59:50' }

test('only fresh comparable digests establish runtime consistency or drift', () => {
  assert.equal(imageDriftFor(expected, runtime, now).state, 'synced')
  assert.equal(imageDriftFor(expected, { ...runtime, dockerImage: 'mirror/app@sha256:' + 'a'.repeat(64) }, now).state, 'synced')
  assert.equal(imageDriftFor(expected, { ...runtime, dockerImage: 'ghcr.io/example/app@sha256:' + 'b'.repeat(64) }, now).state, 'drift')
  for (const dockerImage of ['ghcr.io/example/app:latest', 'sha256:' + 'a'.repeat(64)]) {
    assert.equal(imageDriftFor(expected, { ...runtime, dockerImage }, now).state, 'unknown')
  }
})

test('offline, missing, stale and future evidence cannot report healthy consistency', () => {
  for (const changed of [{ agentStatus: 'OFFLINE' }, { agentStatus: undefined },
    { containerObservedAt: null }, { containerObservedAt: 'invalid' },
    { containerObservedAt: '2026-09-06T01:58:00Z' }, { containerObservedAt: '2026-09-06T02:01:00Z' }]) {
    assert.equal(imageDriftFor(expected, { ...runtime, ...changed }, now).state, 'unknown')
  }
})
