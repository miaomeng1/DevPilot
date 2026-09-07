import test from 'node:test'
import assert from 'node:assert/strict'
import { missingConfiguration } from './missingConfiguration.ts'

test('only explicit missing CI configuration produces an empty setup state', () => {
  assert.equal(missingConfiguration({ response: { status: 404, data: { code: 40440 } } }), null)
  for (const error of [new Error('network'), null, { response: { status: 500 } },
    { response: { status: 403 } }, { response: { status: 404, data: { code: 40420 } } },
    { response: { status: 404 } }, { response: { status: 200, data: { code: 40440 } } }]) {
    let caught = false
    try { missingConfiguration(error) } catch (actual) { caught = true; assert.equal(actual, error) }
    assert.equal(caught, true)
  }
})
