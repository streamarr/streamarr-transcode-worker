import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./summarize.mjs', import.meta.url));
const engine = 'com.streamarr.transcode.engine';
const probe = 'com.streamarr.transcode.probe';

function mutant({
  status,
  mutatedClass = `${engine}.FfmpegCommandBuilder`,
  method = 'addAudioArgs',
  methodDescription = '()V',
  line = 10,
  mutator = 'org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator',
  description = 'negated conditional',
}) {
  const detected = ['KILLED', 'TIMED_OUT', 'MEMORY_ERROR', 'RUN_ERROR', 'NON_VIABLE'].includes(status);
  const sourceFile = `${mutatedClass.split('.').pop().split('$')[0]}.java`;
  const killingTest = detected ? `<killingTest>${mutatedClass}Test.[method:test()]</killingTest>` : '<killingTest/>';
  return `<mutation detected='${detected}' status='${status}' numberOfTestsRun='3'>`
    + `<sourceFile>${sourceFile}</sourceFile><mutatedClass>${mutatedClass}</mutatedClass>`
    + `<mutatedMethod>${method}</mutatedMethod><methodDescription>${methodDescription}</methodDescription>`
    + `<lineNumber>${line}</lineNumber><mutator>${mutator}</mutator>`
    + `<indexes><index>7</index></indexes><blocks><block>2</block></blocks>${killingTest}`
    + `<description>${description}</description></mutation>`;
}

function report(...mutants) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="false">\n${mutants.join('\n')}\n</mutations>\n`;
}

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-mutation-report-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const reportFile = join(directory, 'mutations.xml');
  const summaryFile = join(directory, 'summary.json');
  return {
    directory,
    summaryFile,
    write: (...mutants) => writeFileSync(reportFile, report(...mutants)),
    summary: () => JSON.parse(readFileSync(summaryFile, 'utf8')),
    run: ({ previous } = {}) => {
      const args = [script, '--report', reportFile, '--commit', 'abcdef0123456789', '--ref', 'main'];
      args.push('--write', summaryFile);
      if (previous !== undefined) {
        const previousFile = join(directory, 'previous.json');
        writeFileSync(previousFile, typeof previous === 'string' ? previous : JSON.stringify(previous));
        args.push('--previous', previousFile);
      }
      return spawnSync(process.execPath, args, { encoding: 'utf8' });
    },
  };
}

test('Should score the mutants PIT detected when the report holds every status', (t) => {
  const pit = fixture(t);
  pit.write(
    mutant({ status: 'KILLED' }),
    mutant({ status: 'KILLED' }),
    mutant({ status: 'TIMED_OUT' }),
    mutant({ status: 'MEMORY_ERROR' }),
    mutant({ status: 'RUN_ERROR' }),
    mutant({ status: 'SURVIVED' }),
    mutant({ status: 'NO_COVERAGE' }),
    mutant({ status: 'KILLED', mutatedClass: `${probe}.FfprobeResultMapper` }),
  );

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /PIT mutated the production classes of `abcdef0` on `main`/);
  assert.match(result.stdout, /\*\*Mutation score: 75\.0%\*\* \(6 of 8 mutants detected\)/);
  assert.match(result.stdout, /Test strength: 85\.7% \(6 of the 7 mutants that a test reaches\)/);
  assert.match(
    result.stdout,
    /Killed 3, timed out 1, memory error 1, run error 1, survived 1, no coverage 1\./,
  );
  assert.match(result.stdout, /\| engine \| 7 \| 5 \| 1 \| 1 \| 71\.4% \|/);
  assert.match(result.stdout, /\| probe \| 1 \| 1 \| 0 \| 0 \| 100\.0% \|/);
});

test('Should list each mutant no test detected when some escape the tests', (t) => {
  const pit = fixture(t);
  pit.write(
    mutant({ status: 'KILLED' }),
    mutant({
      status: 'SURVIVED',
      mutatedClass: `${engine}.TranscodeCapabilityService`,
      method: 'recheckWithdrawnEncoders',
      line: 405,
      description: 'replaced boolean return with true for com/streamarr/transcode/engine/TranscodeCapabilityService::recheckWithdrawnEncoders',
    }),
    mutant({
      status: 'NO_COVERAGE',
      mutatedClass: `${engine}.TranscodeCapabilityService`,
      method: 'listEncoders',
      line: 378,
      description: 'removed call to java/lang/Thread::interrupt',
    }),
  );

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /<summary>2 mutants no test detected<\/summary>/);
  assert.match(
    result.stdout,
    /\| `engine\.TranscodeCapabilityService` \| 378 \| `listEncoders` \| removed call to java\/lang\/Thread::interrupt \| no test reaches it \|/,
  );
  assert.match(
    result.stdout,
    /\| `engine\.TranscodeCapabilityService` \| 405 \| `recheckWithdrawnEncoders` \| replaced boolean return with true for .*::recheckWithdrawnEncoders \| survived \|/,
  );
  assert.ok(
    result.stdout.indexOf('| 378 |') < result.stdout.indexOf('| 405 |'),
    'mutants of one class are listed in line order',
  );
});

test('Should keep each table cell intact when a name holds markdown or HTML characters', (t) => {
  const pit = fixture(t);
  pit.write(
    mutant({
      status: 'SURVIVED',
      mutatedClass: `${engine}.Outer$Inner`,
      method: '&lt;init&gt;',
      description: 'replaced return value with &quot;a|b&quot; for &lt;init&gt; &amp; more',
    }),
  );

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(
    result.stdout,
    /\| `engine\.Outer\$Inner` \| 10 \| `<init>` \| replaced return value with "a\\\|b" for &lt;init&gt; &amp; more \| survived \|/,
  );
});

test('Should read each mutant as PIT 1.30.2 writes it when names hold escaped characters', (t) => {
  const pit = fixture(t);
  // Two lines verbatim from a PIT 1.30.2 report of this repository.
  writeFileSync(
    join(pit.directory, 'mutations.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>
<mutations partial="true">
<mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'><sourceFile>FfmpegCommandBuilder.java</sourceFile><mutatedClass>com.streamarr.transcode.engine.FfmpegCommandBuilder</mutatedClass><mutatedMethod>planarFormat</mutatedMethod><methodDescription>(Lcom/streamarr/transcode/engine/TranscodeJob;)Ljava/lang/String;</methodDescription><lineNumber>1394</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.returns.EmptyObjectReturnValsMutator</mutator><indexes><index>11</index></indexes><blocks><block>3</block></blocks><killingTest/><description>replaced return value with &quot;&quot; for com/streamarr/transcode/engine/FfmpegCommandBuilder::planarFormat</description></mutation>
<mutation detected='false' status='SURVIVED' numberOfTestsRun='659'><sourceFile>EncodeFeatureCheck.java</sourceFile><mutatedClass>com.streamarr.transcode.engine.EncodeFeatureCheck</mutatedClass><mutatedMethod>&lt;init&gt;</mutatedMethod><methodDescription>(Lcom/streamarr/transcode/engine/EncodeFeature;Lcom/streamarr/transcode/engine/CodecFamily;)V</methodDescription><lineNumber>49</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator</mutator><indexes><index>37</index></indexes><blocks><block>7</block></blocks><killingTest/><description>negated conditional</description></mutation>
</mutations>
`,
  );

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /\*\*Mutation score: 0\.0%\*\* \(0 of 2 mutants detected\)/);
  assert.match(result.stdout, /\| `engine\.EncodeFeatureCheck` \| 49 \| `<init>` \| negated conditional \| survived \|/);
  assert.match(
    result.stdout,
    /\| `engine\.FfmpegCommandBuilder` \| 1394 \| `planarFormat` \| replaced return value with "" for com\/streamarr\/transcode\/engine\/FfmpegCommandBuilder::planarFormat \| no test reaches it \|/,
  );
});

test('Should list the first 500 mutants and point to the HTML report when more escape the tests', (t) => {
  const pit = fixture(t);
  pit.write(...Array.from({ length: 501 }, (_, index) => mutant({ status: 'SURVIVED', line: index + 1 })));

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /<summary>501 mutants no test detected<\/summary>/);
  assert.match(result.stdout, /\| 500 \| `addAudioArgs` \|/);
  assert.doesNotMatch(result.stdout, /\| 501 \| `addAudioArgs` \|/);
  assert.match(result.stdout, /The HTML report lists the other 1\./);
});

test('Should report no comparison when no earlier run on main left a summary', (t) => {
  const pit = fixture(t);
  pit.write(mutant({ status: 'KILLED' }));

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /No earlier successful run on main left a summary to compare with\./);
});

for (const [name, previous] of [
  ['is not JSON', 'not json'],
  ['has an older format', { commit: 'abc', mutants: 1, detected: 1, survivors: [] }],
  ['counts no mutants', { format: 1, commit: 'abc', mutants: 0, detected: 0, undetected: [] }],
]) {
  test(`Should report without a comparison when the previous summary ${name}`, (t) => {
    const pit = fixture(t);
    pit.write(mutant({ status: 'KILLED' }));

    const result = pit.run({ previous });

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /The previous successful run's summary could not be read, so nothing is compared\./);
    assert.match(result.stdout, /\*\*Mutation score: 100\.0%\*\*/);
  });
}

test('Should compare the rounded scores when the change is smaller than a tenth of a point', (t) => {
  const pit = fixture(t);
  pit.write(...['KILLED', 'KILLED', 'KILLED', 'KILLED', 'SURVIVED'].map((status) => mutant({ status })));

  const result = pit.run({
    previous: { format: 1, commit: 'fedcba9876543210', mutants: 10000, detected: 8004, undetected: [] },
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /score 80\.0% to 80\.0% \(\+0\.0 points\)/);
});

test('Should show the change since the previous night when its summary is given', (t) => {
  const pit = fixture(t);
  const kept = { status: 'SURVIVED', method: 'kept', line: 20 };
  const added = { status: 'SURVIVED', method: 'added', line: 30 };
  const gone = { status: 'SURVIVED', method: 'gone', line: 40 };
  pit.write(mutant({ status: 'KILLED' }), mutant(kept), mutant(gone));
  const previousRun = pit.run();
  assert.equal(previousRun.status, 0, previousRun.stderr);
  const previous = pit.summary();
  pit.write(
    mutant({ status: 'KILLED' }),
    mutant({ status: 'KILLED' }),
    mutant({ ...kept, line: 22 }),
    mutant(added),
  );

  const result = pit.run({ previous: { ...previous, commit: '0123456789abcdef' } });

  assert.equal(result.status, 0, result.stderr);
  assert.match(
    result.stdout,
    /Since the previous successful run on main \(`0123456`\): score 33\.3% to 50\.0% \(\+16\.7 points\); 1 undetected mutant is new and 1 is gone\./,
  );
  assert.match(result.stdout, /\| 22 \| `kept` \| negated conditional \| survived \|/);
  assert.match(result.stdout, /\| 30 \| `added` \| negated conditional \| survived \(new\) \|/);
});

test('Should show a fall in the score when more mutants escape than the previous night', (t) => {
  const pit = fixture(t);
  pit.write(mutant({ status: 'KILLED' }), mutant({ status: 'KILLED' }));
  assert.equal(pit.run().status, 0);
  const previous = pit.summary();
  pit.write(
    mutant({ status: 'KILLED' }),
    mutant({ status: 'SURVIVED', method: 'first', line: 5 }),
    mutant({ status: 'NO_COVERAGE', method: 'second', line: 6 }),
  );

  const result = pit.run({ previous });

  assert.equal(result.status, 0, result.stderr);
  assert.match(
    result.stdout,
    /score 100\.0% to 33\.3% \(-66\.7 points\); 2 undetected mutants are new and 0 are gone\./,
  );
  assert.match(result.stdout, /\| 5 \| `first` \| negated conditional \| survived \(new\) \|/);
  assert.match(result.stdout, /\| 6 \| `second` \| negated conditional \| no test reaches it \(new\) \|/);
});

test('Should say that every mutant was detected when none escaped the tests', (t) => {
  const pit = fixture(t);
  pit.write(mutant({ status: 'KILLED' }), mutant({ status: 'TIMED_OUT' }));

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /\*\*Mutation score: 100\.0%\*\* \(2 of 2 mutants detected\)/);
  assert.match(result.stdout, /\nEvery mutant was detected\.\n$/);
  assert.doesNotMatch(result.stdout, /<details>/);
});

test('Should write the summary that the next night compares with', (t) => {
  const pit = fixture(t);
  pit.write(
    mutant({ status: 'KILLED' }),
    mutant({ status: 'SURVIVED', method: 'survivor', line: 12 }),
  );

  const result = pit.run();

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(pit.summary(), {
    format: 1,
    commit: 'abcdef0123456789',
    mutants: 2,
    detected: 1,
    undetected: [
      {
        mutatedClass: `${engine}.FfmpegCommandBuilder`,
        method: 'survivor',
        methodDescription: '()V',
        line: 12,
        mutator: 'org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator',
        description: 'negated conditional',
        status: 'SURVIVED',
      },
    ],
  });
});

test('Should fail with a pointer to the log when PIT wrote no report', (t) => {
  const pit = fixture(t);

  const result = pit.run();

  assert.equal(result.status, 1);
  assert.match(result.stdout, /PIT wrote no report\. Read the log of the step that runs it\./);
});

test('Should fail with a pointer to the log when PIT wrote a report without mutants', (t) => {
  const pit = fixture(t);
  pit.write();

  const result = pit.run();

  assert.equal(result.status, 1);
  assert.match(result.stdout, /PIT's report lists no mutants\. Read the log of the step that runs it\./);
});
