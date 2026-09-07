// Build a distributable only from a successful, identity-bound CI run.
// Usage: node scripts/package-release.mjs INPUT_DIR NEW_OUTPUT_DIR VERSION
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, lstatSync, copyFileSync } from 'node:fs';
import { resolve, join, isAbsolute } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const [inputArg, outputArg, version] = process.argv.slice(2);
assert.ok(inputArg && outputArg && /^\d+\.\d+\.\d+$/.test(version), 'Input, new output and semantic version required');
assert.ok(isAbsolute(inputArg) && isAbsolute(outputArg), 'Use absolute paths');
const input = resolve(inputArg), output = resolve(outputArg);
assert.equal(existsSync(output), false, 'Never overwrite a release directory');
const read = file => JSON.parse(readFileSync(join(input, file), 'utf8'));
const run = read('run.json');
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
assert.equal(run.headSha, commit);
assert.equal(run.event, 'push');
assert.equal(run.status, 'completed');
assert.equal(run.conclusion, 'success');
assert.match(String(run.databaseId), /^[1-9][0-9]*$/);
assert.ok(['main', 'master'].includes(run.headBranch));
for (const name of ['Test all components', 'Security gate', 'Build server image', 'Build web image', 'Build agent image']) {
  const matches = run.jobs.filter(job => job.name === name);
  assert.equal(matches.length, 1);
  assert.equal(matches[0].conclusion, 'success');
}
const images = {}, reports = [];
let attempt;
for (const component of ['server', 'web', 'agent']) {
  const names = readdirSync(input).filter(n => new RegExp(`^devpilot-release-platform-${component}-[1-9][0-9]*$`).test(n));
  assert.equal(names.length, 1, 'Use a fresh artifact directory for exactly one attempt');
  const evidence = read(names[0] + '/release.json');
  assert.equal(evidence.commit, commit);
  assert.equal(evidence.repository, 'miaomeng1/DevPilot');
  assert.equal(evidence.branch, run.headBranch);
  assert.equal(evidence.event, 'push');
  assert.equal(evidence.runId, String(run.databaseId));
  assert.match(evidence.runAttempt, /^[1-9][0-9]*$/);
  assert.equal(names[0], `devpilot-release-platform-${component}-${evidence.runAttempt}`);
  if (attempt) assert.equal(attempt, evidence.runAttempt);
  attempt = evidence.runAttempt;
  assert.equal(evidence.image, `ghcr.io/miaomeng1/devpilot-${component}`);
  assert.match(evidence.digest, /^sha256:[a-f0-9]{64}$/);
  const imageUri = evidence.image + '@' + evidence.digest;
  for (const architecture of ['amd64', 'arm64']) {
    const name = `devpilot-image-scan-${component}-${attempt}/${architecture}.json`;
    const report = read(name);
    assert.equal(report.ArtifactName, imageUri);
    assert.equal(report.Metadata.ImageConfig.architecture, architecture);
    assert.ok(Array.isArray(report.Results) && report.Results.length > 0);
    assert.equal(report.Results.flatMap(r => r.Vulnerabilities || []).filter(v => ['HIGH', 'CRITICAL'].includes(v.Severity)).length, 0);
    reports.push(name);
  }
  images[component] = { imageUri, platforms: ['linux/amd64', 'linux/arm64'] };
}
const sbomName = `devpilot-java-sbom-${attempt}/bom.json`;
const sbom = read(sbomName);
assert.ok(sbom.components.length > 0 && sbom.components.every(c => c.version));
assert.ok(sbom.components.some(c => c.name === 'spring-security-test'));
mkdirSync(output, { recursive: true, mode: 0o700 });
const directory = join(output, `DevPilot-${version}`);
mkdirSync(directory);
const archive = execFileSync('git', ['archive', '--format=tar', commit], { maxBuffer: 32 * 1024 * 1024 });
execFileSync('tar', ['-xf', '-', '-C', directory], { input: archive });
mkdirSync(join(directory, 'release-evidence'));
for (const name of [...reports, sbomName, 'run.json']) {
  copyFileSync(join(input, name), join(directory, 'release-evidence', name.replaceAll('/', '-')));
}
const manifest = { version, sourceCommit: commit, buildRunId: String(run.databaseId), buildAttempt: attempt, buildUrl: run.url,
  images, dependencyServices: { mysql: 'mysql:8.4', redis: 'redis:7.4-alpine', gateway: 'nginx:1.29-alpine' },
  primarySupport: 'GitHub Actions + GHCR + Dokploy; Linux amd64/arm64 images; runtime acceptance on ARM64 Ubuntu 24.04' };
writeFileSync(join(directory, 'release.json'), JSON.stringify(manifest, null, 2) + '\n');
writeFileSync(join(directory, 'install-release.sh'), `#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$BASH_SOURCE")"
sha256sum -c SHA256SUMS >/dev/null
exec bash scripts/install.sh --server-image '${images.server.imageUri}' --web-image '${images.web.imageUri}' "$@"
`, { mode: 0o755 });
writeFileSync(join(directory, 'QUICK_START.md'), `# DevPilot ${version}

来源提交：${commit}；构建：[GitHub Actions](${run.url})。镜像按 digest 固定，详见 release.json。

在 Linux 主机准备 Docker Engine、Compose v2、准确 UTC/NTP 和至少 5 GiB 空间。私有 GHCR 镜像需要拥有读取权限；先在安装使用的 Docker 配置中执行 sudo docker login ghcr.io（只读 packages 权限），不要把 Token 写入脚本。

~~~bash
sudo bash install-release.sh --port 8080 --public-url http://YOUR_SERVER_IP:8080
~~~

将 YOUR_SERVER_IP 换成实际地址；生产使用 HTTPS。打开输出地址创建管理员，按初始化向导接入 Dokploy、服务器和 Agent，再使用“自动接入新项目”。push 只构建，生产仍需人工确认。

如已离线导入 release.json 中两项平台镜像及 MySQL/Redis/Nginx，可增加 --offline；不要对已有安装重复运行。备份、升级、恢复、权限和网络说明见 docs/deployment.md；使用流程见 docs/personal-delivery-guide.md。校验和用于检查损坏，不代替来源可信验证。GitLab/Coolify 不在真实验收声明内。
`);
const files = [];
function walk(dir, prefix = '') {
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name), relative = prefix + name, stat = lstatSync(path);
    assert.ok(!stat.isSymbolicLink(), 'Release must not contain symlinks');
    if (stat.isDirectory()) walk(path, relative + '/');
    else { assert.match(relative, /^[a-zA-Z0-9._/+-]+$/); files.push(relative); }
  }
}
walk(directory);
const sha = path => createHash('sha256').update(readFileSync(path)).digest('hex');
writeFileSync(join(directory, 'SHA256SUMS'), files.map(f => `${sha(join(directory, f))}  ${f}\n`).join(''));
const bundle = `DevPilot-${version}.tar.gz`;
execFileSync('tar', ['--no-xattrs', '-czf', join(output, bundle), '-C', output, `DevPilot-${version}`], { env: { ...process.env, COPYFILE_DISABLE: '1' } });
writeFileSync(join(output, bundle + '.sha256'), `${sha(join(output, bundle))}  ${bundle}\n`);
console.log(JSON.stringify({ version, commit, buildRunId: run.databaseId, archive: join(output, bundle), files: files.length }));
