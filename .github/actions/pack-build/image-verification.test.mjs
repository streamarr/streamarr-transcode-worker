import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./verify-ffmpeg-image.sh', import.meta.url));
const fakeDocker = fileURLToPath(new URL('./test-fixtures/image-inspection.mjs', import.meta.url));
const revision = 'a'.repeat(40);
const source = 'https://github.com/streamarr/streamarr-transcode-worker';
const expectedContract = '1.84.0.2.20260914130423.f6fcaec276f5';

function validateImage({ contract = expectedContract } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-image-verification-'));
  symlinkSync(fakeDocker, join(directory, 'docker'));
  try {
    return spawnSync('bash', [script, 'worker-image:tested', revision, source, revision, expectedContract], {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, IMAGE_LABELS: JSON.stringify({
        'org.opencontainers.image.version': revision,
        'org.opencontainers.image.source': source,
        'org.opencontainers.image.revision': revision,
        'org.streamarr.contract.version': contract,
      }) },
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('Should reject the image when its contract label differs from the expected Buf SDK', () => {
  const result = validateImage({ contract: 'wrong-contract' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Expected org\.streamarr\.contract\.version=.* but found wrong-contract/);
});

test('Should accept the image when its contract label matches the expected Buf SDK', () => {
  const result = validateImage();
  assert.equal(result.status, 0, result.stderr);
});
