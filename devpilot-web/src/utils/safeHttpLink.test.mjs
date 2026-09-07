import test from 'node:test'
import assert from 'node:assert/strict'
import { safeHttpLink } from './safeHttpLink.ts'

test('CI task links allow HTTP(S) only, including historical callback records', () => {
  for (const value of ['javascript:alert(1)', 'java\nscript:alert(1)', 'data:text/html,<script></script>', 'file:///etc/passwd', '//example.com', '/relative', 'https://user:secret@example.com', null, '']) {
    assert.equal(safeHttpLink(value), null)
  }
  assert.equal(safeHttpLink('https://github.com/acme/demo/actions/runs/42'), 'https://github.com/acme/demo/actions/runs/42')
  assert.equal(safeHttpLink('http://gitlab.local/pipelines/42'), 'http://gitlab.local/pipelines/42')
})
