// Isolated acceptance fixture, NOT a Dokploy replacement. No external traffic.
// Run in the restored backend's network namespace with the private fixture mounted at /state.
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { timingSafeEqual } from 'node:crypto'

const expected = Buffer.from(JSON.parse(readFileSync('/state/setup-request.json', 'utf8')).providerApiToken)
if (!expected.toString().startsWith('fixture-')) throw new Error('Only synthetic fixture keys are accepted')
const accepted = new Set()
const server = createServer((request, response) => {
  const supplied = Buffer.from(String(request.headers['x-api-key'] || ''))
  const valid = supplied.length === expected.length && timingSafeEqual(supplied, expected)
  if (!valid || request.method !== 'GET' || !['/api/project.all', '/api/server.all'].includes(request.url)) {
    response.writeHead(401).end()
    return
  }
  accepted.add(request.url)
  response.writeHead(200, { 'Content-Type': 'application/json' }).end('[]')
  if (accepted.size === 2) {
    console.log('Verified: restored backend decrypted the original synthetic key for both read-only API calls.')
    server.close()
  }
})
const timeout = setTimeout(() => { console.error('Credential verification timed out'); process.exit(1) }, 60000)
server.on('close', () => clearTimeout(timeout))
server.listen(9, '127.0.0.1', () => console.log('Synthetic provider fixture listening on loopback only'))
