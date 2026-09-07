import test from 'node:test'
import assert from 'node:assert/strict'
import { readRenewal, beginRenewal, finishRenewal } from './agentTokenRenewal.ts'
function storage() {
  const data = new Map()
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k), data }
}
test('renewal retries preserve the request and old revision across owners and servers', () => {
  const store = storage()
  const first = beginRenewal('a', '123', '456', store)
  assert.deepEqual(beginRenewal('a', '123', '789', store), first)
  assert.deepEqual(readRenewal('a', '123', store), first)
  assert.equal(readRenewal('b', '123', store), null)
  assert.equal(readRenewal('a', '124', store), null)
  assert.deepEqual(Object.keys(first).sort(), ['expectedRevision', 'requestId'])
  finishRenewal('a', '123', 'wrong', store)
  assert.deepEqual(readRenewal('a', '123', store), first)
  finishRenewal('a', '123', first.requestId, store)
  assert.equal(readRenewal('a', '123', store), null)
  assert.notEqual(beginRenewal('a', '123', '789', store).requestId, first.requestId)
})
test('invalid or unavailable storage cannot silently issue a new rotation', () => {
  const store = storage()
  store.setItem('devpilot.agent-renewal.a.123', '{}')
  assert.throws(() => beginRenewal('a', '123', '456', store))
  assert.throws(() => beginRenewal(undefined, '123', '456', store))
  assert.throws(() => beginRenewal('a', 'not-an-id', '456', store))
  assert.throws(() => beginRenewal('b', '123', 'invalid', store))
  assert.throws(() => beginRenewal('a', '123', '456', { getItem: () => null, setItem: () => { throw Error('disabled') } }))
})
