import test from 'node:test'
import assert from 'node:assert/strict'
import { beginServerCreation, readServerCreation, completeServerCreation } from './serverCreationRequest.ts'

function storage() {
  const data = new Map()
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k), data }
}
test('server creation resumes across views without storing credentials or mixing owners', () => {
  const store = storage()
  const first = beginServerCreation('admin-1', 'server-a', store)
  assert.deepEqual(readServerCreation('admin-1', store), first)
  assert.deepEqual(beginServerCreation('admin-1', 'server-a', store), first)
  assert.equal(readServerCreation('admin-2', store), null)
  assert.deepEqual(Object.keys(first).sort(), ['name', 'requestId'])
  assert.throws(() => beginServerCreation('admin-1', 'server-b', store))
  completeServerCreation('admin-1', 'wrong-request', store)
  assert.deepEqual(readServerCreation('admin-1', store), first)
  completeServerCreation('admin-1', first.requestId, store)
  assert.equal(readServerCreation('admin-1', store), null)
})
test('unreadable or unavailable storage prevents silently replacing a pending request', () => {
  const store = storage()
  store.setItem('devpilot.setup.server-create.admin', '{bad JSON')
  assert.throws(() => beginServerCreation('admin', 'server-a', store))
  assert.throws(() => beginServerCreation(undefined, 'server-a', store))
  const blocked = { getItem: () => null, setItem: () => { throw new Error('disabled') }, removeItem: () => {} }
  assert.throws(() => beginServerCreation('admin', 'server-a', blocked))
})
