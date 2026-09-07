import test from 'node:test'
import assert from 'node:assert/strict'
import { latestRequest } from './latestRequest.ts'

test('only newest read may update data, errors or loading state', async () => {
  const reads = latestRequest()
  const old = reads.begin()
  const current = reads.begin()
  let data = ''
  await Promise.resolve().then(() => { if (current()) data = 'new application' })
  await Promise.resolve().then(() => { if (old()) data = 'late old response' })
  assert.equal(data, 'new application')
  assert.equal(old(), false)
  reads.invalidate()
  assert.equal(current(), false)
  assert.equal(reads.begin()(), true)
})
