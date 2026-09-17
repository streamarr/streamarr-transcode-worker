import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const nativeScript = fileURLToPath(new URL('./publish-verified-image.sh', import.meta.url));
const indexScript = fileURLToPath(new URL('./publish-image-index.sh', import.meta.url));
const fakeGithub = fileURLToPath(new URL('./test-fixtures/gh.mjs', import.meta.url));
const fakeDocker = fileURLToPath(new URL('./test-fixtures/docker.mjs', import.meta.url));
const revision = 'a'.repeat(40);
const digest = `sha256:${'b'.repeat(64)}`;
const manifestDigest = `sha256:${'f'.repeat(64)}`;
const imageRepository = 'streamarr/streamarr-transcode-worker';
const testedImage = { digest, source: revision, architecture: 'amd64' };
const armImage = { digest: `sha256:${'d'.repeat(64)}`, source: revision, architecture: 'arm64' };

function publication({ mode = 'native', environment = {}, architecture = 'amd64', indexDigest = manifestDigest, indexOverride, changedTags = false, changedIndexTag = false, receiptOverrides = {}, version = '0.1.0', mainRevision = revision, indexes = {} } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-image-publication-'));
  const registry = join(directory, 'registry.json');
  symlinkSync(fakeDocker, join(directory, 'docker'));
  symlinkSync(fakeGithub, join(directory, 'gh'));
  writeFileSync(registry, JSON.stringify({
    local: {
      'worker-image:tested': architecture === 'arm64' ? armImage : testedImage,
      'worker-image:other': { ...testedImage, digest: `sha256:${'e'.repeat(64)}` },
    },
    registry: mode === 'index' ? {
      [`${imageRepository}:sha-${revision}-amd64`]: changedTags ? armImage : testedImage,
      [`${imageRepository}:sha-${revision}-arm64`]: changedTags ? testedImage : armImage,
      [`${imageRepository}@${testedImage.digest}`]: testedImage,
      [`${imageRepository}@${armImage.digest}`]: armImage,
    } : {},
    indexes, indexDigest, indexOverride, changedIndexTag,
  }));
  for (const image of mode === 'index' ? [testedImage, armImage] : []) {
    writeFileSync(join(directory, `${image.architecture}-image.json`), JSON.stringify({
      sourceRevision: revision, architecture: image.architecture,
      image: `${imageRepository}@${image.digest}`, ...receiptOverrides[image.architecture],
    }));
  }
  writeFileSync(join(directory, 'pom.xml'), `<project><version>${version}</version><properties><buf.sdk.version>sdk-revision</buf.sdk.version></properties></project>`);
  const args = mode === 'index' ? [indexScript] : [nativeScript, 'worker-image:tested', architecture];
  const result = spawnSync('bash', args, {
    cwd: directory,
    encoding: 'utf8',
    env: { ...process.env, PATH: `${directory}:${process.env.PATH}`,
      GITHUB_SHA: revision, GITHUB_REF: 'refs/heads/main', GITHUB_EVENT_NAME: 'push',
      GITHUB_REPOSITORY: imageRepository, FAKE_REGISTRY: registry, FAKE_MAIN_REVISION: mainRevision,
      GITHUB_STEP_SUMMARY: join(directory, 'summary'), ...environment },
  });
  const read = (name) => { try { return readFileSync(join(directory, name), 'utf8'); } catch { return ''; } };
  const outcome = { ...result, state: JSON.parse(read('registry.json')), receipt: read('worker-image.json'),
    nativeReceipt: read(`${architecture}-image.json`), index: read('image-index.json'), summary: read('summary') };
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

test('records the immutable multi-architecture image with its source and contract revisions', () => {
  const result = publication({ mode: 'index' });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.state.indexes, { [`${imageRepository}:sha-${revision}`]: [testedImage, armImage] });
  assert.deepEqual(JSON.parse(result.receipt), {
    sourceRevision: revision, contractRevision: 'sdk-revision', image: `${imageRepository}@${manifestDigest}`,
    nativeImages: { amd64: `${imageRepository}@${testedImage.digest}`, arm64: `${imageRepository}@${armImage.digest}` },
  });
  assert.deepEqual(JSON.parse(result.index).manifests.map((entry) => entry.platform.architecture).sort(), ['amd64', 'arm64']);
  assert.equal(result.summary, result.receipt);
});

test('does not report an immutable image when the registry returns no valid digest', () => {
  const result = publication({ mode: 'index', indexDigest: '<no value>' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /manifest digest/);
  assert.equal(result.receipt, '');
});

for (const mode of ['native', 'index']) {
  for (const [condition, environment, message] of [
    ['a pull request', { GITHUB_EVENT_NAME: 'pull_request', GITHUB_REF: 'refs/pull/8/merge' }, /reviewed main push/],
    ['a feature branch push', { GITHUB_REF: 'refs/heads/feature/image' }, /reviewed main push/],
    ['manual main execution', { GITHUB_EVENT_NAME: 'workflow_dispatch' }, /reviewed main push/],
    ['another repository', { GITHUB_REPOSITORY: 'someone/fork' }, /reviewed main push/],
    ['an abbreviated source revision', { GITHUB_SHA: 'abc123' }, /full source revision/],
  ]) {
    test(`refuses ${mode} publication for ${condition}`, () => {
      const result = publication({ mode, environment });
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, message);
      assert.deepEqual(result.state.indexes, {});
      if (mode === 'native') assert.deepEqual(result.state.registry, {});
      assert.equal(result.receipt, '');
    });
  }
}

test('refuses an unsupported native image architecture', () => {
  const result = publication({ architecture: 'riscv64' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Unsupported image architecture/);
  assert.deepEqual(result.state.registry, {});
});

test('does not report an image index that lacks a supported platform', () => {
  const result = publication({ mode: 'index', indexOverride: {
    manifests: [{ platform: { os: 'linux', architecture: 'amd64' } }],
  } });
  assert.notEqual(result.status, 0);
  assert.equal(result.receipt, '');
});

test('uses the native digest receipts even when the source tags have changed', () => {
  const result = publication({ mode: 'index', changedTags: true });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.state.indexes[`${imageRepository}:sha-${revision}`], [testedImage, armImage]);
  assert.deepEqual(JSON.parse(result.receipt).nativeImages, {
    amd64: `${imageRepository}@${testedImage.digest}`, arm64: `${imageRepository}@${armImage.digest}`,
  });
});

for (const [condition, fields] of [
  ['another revision', { sourceRevision: 'c'.repeat(40) }],
  ['another architecture', { architecture: 'arm64' }],
  ['a mutable tag', { image: `${imageRepository}:latest` }],
  ['another repository', { image: `someone/fork@${digest}` }],
]) {
  test(`rejects a native receipt for ${condition} before creating the index`, () => {
    const result = publication({ mode: 'index', receiptOverrides: { amd64: fields } });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /native image receipt/);
    assert.deepEqual(result.state.indexes, {});
    assert.equal(result.receipt, '');
  });
}

test('records and inspects the created digest when the index tag is replaced', () => {
  const result = publication({ mode: 'index', changedIndexTag: true });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.receipt).image, `${imageRepository}@${manifestDigest}`);
  assert.deepEqual(JSON.parse(result.index).manifests.map((entry) => entry.digest), [testedImage.digest, armImage.digest]);
});


test('Should publish the exact Maven snapshot tag when the tested main build has a snapshot version', () => {
  const result = publication({ mode: 'index', version: '0.1.0-SNAPSHOT' });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.state.indexes, {
    [`${imageRepository}:sha-${revision}`]: [testedImage, armImage],
    [`${imageRepository}:0.1.0-SNAPSHOT`]: [testedImage, armImage],
  });
});


test('Should preserve the newer snapshot when an older main run is retried', () => {
  const newerImages = [testedImage, armImage].map((image) => ({ ...image, source: 'c'.repeat(40) }));
  const snapshot = `${imageRepository}:0.1.0-SNAPSHOT`;
  const result = publication({ mode: 'index', version: '0.1.0-SNAPSHOT',
    mainRevision: 'c'.repeat(40), indexes: { [snapshot]: newerImages } });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.state.indexes[snapshot], newerImages);
  assert.deepEqual(result.state.indexes[`${imageRepository}:sha-${revision}`], [testedImage, armImage]);
});

for (const mainRevision of ['', 'null', 'abc123']) {
  test(`Should fail snapshot publication when GitHub returns an invalid main revision (${mainRevision || 'empty'})`, () => {
    const result = publication({ mode: 'index', version: '0.1.0-SNAPSHOT', mainRevision });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /valid main revision/);
    assert.equal(result.state.indexes[`${imageRepository}:0.1.0-SNAPSHOT`], undefined);
  });
}

for (const version of ['0.1.0', '0.1.0-RC1', '0.1.0-SNAPSHOT-SNAPSHOT']) {
  test(`Should leave version tags to the release workflow when Maven is not a valid snapshot (${version})`, () => {
    const result = publication({ mode: 'index', version });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.state.indexes, { [`${imageRepository}:sha-${revision}`]: [testedImage, armImage] });
  });
}

test('Should fail without promoting the snapshot when GitHub is unavailable', () => {
  const result = publication({ mode: 'index', version: '0.1.0-SNAPSHOT',
    environment: { FAKE_GITHUB_UNAVAILABLE: 'true' } });
  assert.equal(result.status, 23);
  assert.match(result.stderr, /GitHub unavailable/);
  assert.equal(result.state.indexes[`${imageRepository}:0.1.0-SNAPSHOT`], undefined);
});
