import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./shard-classes.sh', import.meta.url));
const sources = [
  'com/streamarr/transcode/engine/Producer.java',
  'com/streamarr/transcode/engine/ProducerFailure.java',
  'com/streamarr/transcode/probe/FfprobeExecutor.java',
  'com/streamarr/transcode/protocol/ProtoUuid.java',
  'com/streamarr/transcode/worker/TranscodeWorker.java',
];

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-mutation-shard-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  for (const source of sources) {
    mkdirSync(join(directory, 'src/main/java', source, '..'), { recursive: true });
    writeFileSync(join(directory, 'src/main/java', source), '');
  }
  return {
    run: (shard, shards) => spawnSync(script, [String(shard), String(shards)], { cwd: directory, encoding: 'utf8' }),
  };
}

function classesOf(output) {
  return output.trim().split(',').filter((glob) => !glob.endsWith('$*'));
}

test('Should give each production class to exactly one shard when the run is split', (t) => {
  const tree = fixture(t);

  const shards = [0, 1, 2].map((shard) => tree.run(shard, 3));

  for (const result of shards) {
    assert.equal(result.status, 0, result.stderr);
  }
  assert.deepEqual(shards.flatMap((result) => classesOf(result.stdout)).sort(), [
    'com.streamarr.transcode.engine.Producer',
    'com.streamarr.transcode.engine.ProducerFailure',
    'com.streamarr.transcode.probe.FfprobeExecutor',
    'com.streamarr.transcode.protocol.ProtoUuid',
    'com.streamarr.transcode.worker.TranscodeWorker',
  ]);
  assert.deepEqual(shards.map((result) => classesOf(result.stdout).length), [2, 2, 1]);
});

test('Should name a class and its nested classes but not a class that shares its prefix', (t) => {
  const tree = fixture(t);

  const result = tree.run(0, 5);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), 'com.streamarr.transcode.engine.Producer,com.streamarr.transcode.engine.Producer$*');
});

for (const [name, args] of [
  ['no shard count', ['0']],
  ['a shard past the last', ['3', '3']],
  ['a shard that is not a number', ['one', '3']],
]) {
  test(`Should refuse to split when given ${name}`, () => {
    const result = spawnSync(script, args, { encoding: 'utf8' });

    assert.equal(result.status, 2);
    assert.match(result.stderr, /usage: shard-classes\.sh <shard> <shards>/);
  });
}
