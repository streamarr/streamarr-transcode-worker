import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, cpSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';
import { checkExpectations } from './check-expectations.mjs';
import { formatJson, parseJson } from './json.mjs';

const FIXTURES = fileURLToPath(new URL('../resources/fmp4/', import.meta.url));
const SCRIPT = fileURLToPath(new URL('check-expectations.mjs', import.meta.url));

/** A copy of the committed fixtures whose expected.json the edit may change in place. */
function copyWith(edit = () => {}) {
  const directory = mkdtempSync(join(tmpdir(), 'fmp4-'));
  cpSync(FIXTURES, directory, { recursive: true });
  const expected = parseJson(readFileSync(join(directory, 'expected.json'), 'utf8'));
  edit(expected, directory);
  writeFileSync(join(directory, 'expected.json'), `${formatJson(expected)}\n`);
  return directory;
}

function fixture(expected, name) {
  return expected.fixtures.find((entry) => entry.name === name);
}

describe('offline expectation check', () => {
  it('finds expected.json in agreement with every committed recording', () => {
    assert.deepEqual(checkExpectations(FIXTURES), []);
  });

  it('names every recorded fact that expected.json misstates', () => {
    const directory = copyWith((expected) => {
      fixture(expected, '01-encode-cfr').segments[1].byteLength += 1;
      fixture(expected, '07-copy-seek30').discardedPreroll = [];
      fixture(expected, '10-copy-gop-exceeds-period').failure.fragmentIndex = 21;
      fixture(expected, '06-encode-audio-tail').diagnostics.videoSamples -= 1;
      fixture(expected, '05-copy-late-start').sourceKeyframeCheck.keyframePresentationTimes.pop();
      fixture(expected, '02-copy-irregular-keyframes').hlsOracles[0].mismatches.push({ number: 11, grouping: null, hls: 1 });
      fixture(expected, '04-copy-vfr-bframes').hlsOracles[1].agrees = true;
      expected.initializationSegmentIdentityPairs[0].sha256[1] = '0';
    });

    assert.deepEqual(checkExpectations(directory), [
      '01-encode-cfr: segments in expected.json does not match the recording',
      '02-copy-irregular-keyframes / 02-copy-irregular-keyframes.hls-recipe: mismatches or agrees in expected.json does not match the grid segments',
      '04-copy-vfr-bframes / 04-copy-vfr-bframes.video-only: mismatches or agrees in expected.json does not match the grid segments',
      '05-copy-late-start: sourceKeyframeCheck.keyframePresentationTimes in expected.json does not match the recording',
      '06-encode-audio-tail: diagnostics in expected.json does not match the recording',
      '07-copy-seek30: discardedPreroll in expected.json does not match the recording',
      '10-copy-gop-exceeds-period: failure in expected.json does not match the recording',
      'encode (libx264), start 0 vs -ss 30: the initialization segment comparison in expected.json does not match the recordings',
    ]);
  });

  it('names a recording that expected.json does not describe and a described recording that is missing', () => {
    const directory = copyWith((_expected, folder) => {
      copyFileSync(join(folder, '07-copy-start0.fmp4'), join(folder, '99-unlisted.fmp4'));
      rmSync(join(folder, '06-encode-audio-tail.fmp4'));
      writeFileSync(join(folder, '10-copy-gop-exceeds-period.fmp4'), Buffer.from([0, 0, 0, 4, 0x66, 0x72, 0x65, 0x65]));
    });

    assert.deepEqual(checkExpectations(directory), [
      '06-encode-audio-tail: 06-encode-audio-tail.fmp4 is missing',
      "10-copy-gop-exceeds-period: 10-copy-gop-exceeds-period.fmp4 cannot be read: box 'free' at 0 declares 4 bytes, less than its 8-byte header",
      '99-unlisted.fmp4: no fixture in expected.json describes this recording',
    ]);
  });

  it('exits with the disagreements as its failure when run from the command line', () => {
    const agreeing = spawnSync(process.execPath, [SCRIPT], { encoding: 'utf8' });
    const drifted = spawnSync(
      process.execPath,
      [SCRIPT, copyWith((expected) => (fixture(expected, '01-encode-cfr').byteLength += 1))],
      { encoding: 'utf8' },
    );

    assert.equal(agreeing.status, 0, agreeing.stderr);
    const recordings = readdirSync(FIXTURES).filter((file) => file.endsWith('.fmp4')).length;
    assert.ok(recordings > 0);
    assert.match(agreeing.stdout, new RegExp(`expected\\.json agrees with all ${recordings} recordings`));
    assert.equal(drifted.status, 1);
    assert.equal(drifted.stderr, 'DRIFT 01-encode-cfr: byteLength in expected.json does not match the recording\n');
  });
});
