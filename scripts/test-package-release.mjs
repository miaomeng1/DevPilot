import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
const root = mkdtempSync(join(tmpdir(), 'devpilot-package-fixture-'));
const input = join(root, 'input'); mkdirSync(input);
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const put = (name, value) => { const p = join(input, name); mkdirSync(resolve(p, '..'), { recursive: true }); writeFileSync(p, JSON.stringify(value)); };
const jobNames = ['Test all components', 'Security gate', 'Build server image', 'Build web image', 'Build agent image'];
const run = { headSha: commit, event: 'push', status: 'completed', conclusion: 'success', databaseId: 123, headBranch: 'main', jobs: jobNames.map(name => ({ name, conclusion: 'success' })), url: 'https://example.invalid/unit-test-only' };
function seed() {
  put('run.json', run);
  for (const component of ['server', 'web', 'agent']) {
    const image = `ghcr.io/miaomeng1/devpilot-${component}`, digest = 'sha256:' + 'a'.repeat(64);
    put(`devpilot-release-platform-${component}-1/release.json`, { image, digest, commit, repository: 'miaomeng1/DevPilot', branch: 'main', event: 'push', runId: '123', runAttempt: '1' });
    for (const arch of ['amd64', 'arm64']) put(`devpilot-image-scan-${component}-1/${arch}.json`, { ArtifactName: image + '@' + digest, Metadata: { ImageConfig: { architecture: arch } }, Results: [{ Vulnerabilities: [] }] });
  }
  put('devpilot-java-sbom-1/bom.json', { components: [{ name: 'spring-security-test', version: 'fixture' }] });
}
let count = 0;
try {
  const cases = [
    ['failed run', () => put('run.json', { ...run, conclusion: 'failure' })],
    ['wrong commit', () => put('run.json', { ...run, headSha: 'f'.repeat(40) })],
    ['missing gate', () => put('run.json', { ...run, jobs: run.jobs.slice(1) })],
    ['duplicate gate', () => put('run.json', { ...run, jobs: [...run.jobs, run.jobs[0]] })],
    ['missing architecture', () => put('devpilot-image-scan-server-1/arm64.json', { Metadata: { ImageConfig: { architecture: 'amd64' } } })],
    ['high vulnerability', () => put('devpilot-image-scan-server-1/amd64.json', { ArtifactName: 'ghcr.io/miaomeng1/devpilot-server@sha256:' + 'a'.repeat(64), Metadata: { ImageConfig: { architecture: 'amd64' } }, Results: [{ Vulnerabilities: [{ Severity: 'HIGH' }] }] })],
  ];
  for (const [name, change] of cases) {
    seed(); change();
    const r = spawnSync(process.execPath, ['scripts/package-release.mjs', input, join(root, 'reject-' + count), '1.0.0'], { encoding: 'utf8' });
    assert.notEqual(r.status, 0, name); count++;
  }
  seed(); const output = join(root, 'valid');
  execFileSync(process.execPath, ['scripts/package-release.mjs', input, output, '1.0.0'], { stdio: 'pipe' });
  const manifest = JSON.parse(readFileSync(join(output, 'DevPilot-1.0.0/release.json')));
  assert.equal(manifest.sourceCommit, commit); assert.equal(manifest.buildRunId, '123');
  execFileSync('bash', ['-n', join(output, 'DevPilot-1.0.0/install-release.sh')]);
  const duplicate = spawnSync(process.execPath, ['scripts/package-release.mjs', input, output, '1.0.0'], { stdio: 'pipe' });
  assert.notEqual(duplicate.status, 0); count += 2;
  console.log(`${count} packaging cases passed (synthetic evidence only; no release published).`);
} finally { rmSync(root, { recursive: true, force: true }); }
