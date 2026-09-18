import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const nativeScript = fileURLToPath(new URL('./publish-verified-image.sh', import.meta.url));
const fakeDocker = fileURLToPath(new URL('./test-fixtures/docker.mjs', import.meta.url));
const revision = 'a'.repeat(40);
const digest = `sha256:${'b'.repeat(64)}`;
const imageRepository = 'streamarr/streamarr-transcode-worker';
const testedImage = { digest, source: revision, architecture: 'amd64' };
const armImage = { digest: `sha256:${'d'.repeat(64)}`, source: revision, architecture: 'arm64' };

function publication({ environment = {}, architecture = 'amd64' } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-image-publication-'));
  const registry = join(directory, 'registry.json');
  symlinkSync(fakeDocker, join(directory, 'docker'));
  writeFileSync(registry, JSON.stringify({
    local: {
      'worker-image:tested': architecture === 'arm64' ? armImage : testedImage,
      'worker-image:other': { ...testedImage, digest: `sha256:${'e'.repeat(64)}` },
    },
    registry: {},
  }));
  const result = spawnSync('bash', [nativeScript, 'worker-image:tested', architecture], {
    cwd: directory,
    encoding: 'utf8',
    env: { ...process.env, PATH: `${directory}:${process.env.PATH}`,
      GITHUB_SHA: revision, GITHUB_REF: 'refs/heads/main', GITHUB_EVENT_NAME: 'push',
      GITHUB_REPOSITORY: imageRepository, FAKE_REGISTRY: registry, ...environment },
  });
  const read = (name) => { try { return readFileSync(join(directory, name), 'utf8'); } catch { return ''; } };
  const outcome = { ...result, state: JSON.parse(read('registry.json')),
    nativeReceipt: read(`${architecture}-image.json`) };
  rmSync(directory, { recursive: true, force: true });
  return outcome;
}

for (const image of [testedImage, armImage]) {
  test(`publishes the exact tested ${image.architecture} image contents without rebuilding`, () => {
    const result = publication({ architecture: image.architecture });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.state.registry, { [`${imageRepository}:sha-${revision}-${image.architecture}`]: image });
    assert.equal(result.stdout.trim(), `${imageRepository}@${image.digest}`);
    assert.deepEqual(JSON.parse(result.nativeReceipt), {
      sourceRevision: revision, architecture: image.architecture, image: `${imageRepository}@${image.digest}`,
    });
  });
}

test('refuses to publish an image built from a different source revision', () => {
  const result = publication({ environment: { GITHUB_SHA: 'c'.repeat(40) } });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /source revision/);
  assert.deepEqual(result.state.registry, {});
});

for (const [condition, environment, message] of [
  ['a pull request', { GITHUB_EVENT_NAME: 'pull_request', GITHUB_REF: 'refs/pull/8/merge' }, /reviewed main push/],
  ['a feature branch push', { GITHUB_REF: 'refs/heads/feature/image' }, /reviewed main push/],
  ['manual main execution', { GITHUB_EVENT_NAME: 'workflow_dispatch' }, /reviewed main push/],
  ['another repository', { GITHUB_REPOSITORY: 'someone/fork' }, /reviewed main push/],
  ['an abbreviated source revision', { GITHUB_SHA: 'abc123' }, /full source revision/],
]) {
  test(`refuses native publication for ${condition}`, () => {
    const result = publication({ environment });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, message);
    assert.deepEqual(result.state.registry, {});
    assert.equal(result.nativeReceipt, '');
  });
}

test('refuses an unsupported native image architecture', () => {
  const result = publication({ architecture: 'riscv64' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Unsupported image architecture/);
  assert.deepEqual(result.state.registry, {});
});
