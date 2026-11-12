import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('../fetch-previous.sh', import.meta.url));

// The fake gh answers `run list` with LIST_OUTPUT and LIST_EXIT, and `run download` by writing a
// summary into the --dir it receives, unless DOWNLOAD_EXIT says the run kept no artifact.
function fixture(t, { listOutput = '4242', listExit = 0, downloadExit = 0 } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-mutation-previous-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, 'bin');
  mkdirSync(bin);
  const calls = join(directory, 'gh-calls');
  writeFileSync(join(bin, 'gh'), `#!/bin/bash
printf '%s\\n' "$*" >>"${calls}"
case "$1 $2" in
  "run list")
    printf '%s' "${listOutput}"
    exit ${listExit}
    ;;
  "run download")
    if (( ${downloadExit} != 0 )); then
      echo "no artifact matches any of the names or patterns provided" >&2
      exit ${downloadExit}
    fi
    while (( $# > 0 )); do
      if [[ "$1" == --dir ]]; then
        mkdir -p "$2"
        printf '{"commit":"previous"}' >"$2/summary.json"
      fi
      shift
    done
    ;;
esac
`, { mode: 0o755 });
  const target = join(directory, 'previous');
  return {
    target,
    calls: () => readFileSync(calls, 'utf8').trim().split('\n'),
    run: () => spawnSync(script, [target], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        GITHUB_REPOSITORY: 'streamarr/streamarr-transcode-worker',
      },
    }),
  };
}

test('Should download the summary of the latest successful run on main', (t) => {
  const previous = fixture(t);

  const result = previous.run();

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(previous.calls(), [
    'run list --repo streamarr/streamarr-transcode-worker --workflow mutation-testing.yml --branch main'
      + ' --status success --limit 1 --json databaseId --jq .[0].databaseId // empty',
    `run download 4242 --repo streamarr/streamarr-transcode-worker --name mutation-summary --dir ${previous.target}`,
  ]);
  assert.equal(readFileSync(join(previous.target, 'summary.json'), 'utf8'), '{"commit":"previous"}');
});

test('Should report without a comparison when no earlier run on main succeeded', (t) => {
  const previous = fixture(t, { listOutput: '' });

  const result = previous.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /No earlier successful run on main; reporting without a comparison/);
  assert.equal(previous.calls().length, 1);
  assert.equal(existsSync(join(previous.target, 'summary.json')), false);
});

test('Should report without a comparison when the earlier run kept no summary', (t) => {
  const previous = fixture(t, { downloadExit: 1 });

  const result = previous.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Run 4242 kept no summary; reporting without a comparison/);
  assert.equal(existsSync(join(previous.target, 'summary.json')), false);
});

test('Should report without a comparison when GitHub cannot list the earlier runs', (t) => {
  const previous = fixture(t, { listOutput: '', listExit: 1 });

  const result = previous.run();

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Could not list earlier runs; reporting without a comparison/);
  assert.equal(previous.calls().length, 1);
});
