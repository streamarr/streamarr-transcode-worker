import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const fakeTools = fileURLToPath(new URL('./test-fixtures/image-build.mjs', import.meta.url));
const image = 'worker-image:tested';
const contract = '1.84.0.2.20260914130423.f6fcaec276f5';

function buildImage({ version } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-image-build-'));
  try {
    for (const relative of ['buildpacks/ffmpeg', '.github/actions/pack-build/build-worker-image.sh', '.github/actions/pack-build/verify-ffmpeg-image.sh']) {
      const destination = join(directory, relative);
      mkdirSync(dirname(destination), { recursive: true });
      cpSync(join(repository, relative), destination, {
        recursive: true,
        filter: (source) => !source.includes('/generated'),
      });
    }
    const bin = join(directory, 'tools');
    mkdirSync(bin);
    for (const tool of ['pack', 'docker']) symlinkSync(fakeTools, join(bin, tool));
    symlinkSync(fakeTools, join(directory, 'mvnw'));
    const statePath = join(directory, 'build-state.json');
    writeFileSync(statePath, JSON.stringify({ contract, version: '0.1.0-SNAPSHOT', images: {} }));
    const env = {
      ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_BUILD_STATE: statePath,
      GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null',
      GIT_AUTHOR_NAME: 'Release fixture', GIT_COMMITTER_NAME: 'Release fixture',
      GIT_AUTHOR_EMAIL: 'fixture@example.test', GIT_COMMITTER_EMAIL: 'fixture@example.test',
    };
    const git = (...args) => execFileSync('git', ['-c', 'commit.gpgsign=false', ...args], { cwd: directory, env, encoding: 'utf8' }).trim();
    git('init', '--quiet');
    git('commit', '--quiet', '--allow-empty', '-m', 'fixture');
    const revision = git('rev-parse', 'HEAD');
    const result = spawnSync('bash', [join(directory, '.github/actions/pack-build/build-worker-image.sh'), image, ...version ? [version] : []], {
      cwd: directory, env, encoding: 'utf8',
    });
    const state = JSON.parse(readFileSync(statePath, 'utf8'));
    return { ...result, revision, labels: state.images[image], environment: state.environments?.[image] };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('records the release version and source revision when building a versioned image', () => {
  const result = buildImage({ version: '0.1.0' });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.labels, {
    'org.opencontainers.image.version': '0.1.0',
    'org.opencontainers.image.source': 'https://github.com/streamarr/streamarr-transcode-worker',
    'org.opencontainers.image.revision': result.revision,
    'org.streamarr.contract.version': contract,
  });
});

test('Should record the Maven version and source revision when CI supplies no release version', () => {
  const result = buildImage();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.labels['org.opencontainers.image.version'], '0.1.0-SNAPSHOT');
  assert.equal(result.labels['org.opencontainers.image.revision'], result.revision);
});

test('Should default the runtime locale to UTF-8 when building the image', () => {
  const result = buildImage();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.environment.BPE_DEFAULT_LANG, 'C.UTF-8');
});
