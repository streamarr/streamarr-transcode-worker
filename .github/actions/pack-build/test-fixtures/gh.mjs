#!/usr/bin/env node
import assert from 'node:assert/strict';

assert.deepEqual(process.argv.slice(2), [
  'api', 'repos/streamarr/streamarr-transcode-worker/git/ref/heads/main', '--jq', '.object.sha',
]);
if (process.env.FAKE_GITHUB_UNAVAILABLE === 'true') {
  process.stderr.write('GitHub unavailable\n');
  process.exit(23);
}
process.stdout.write(`${process.env.FAKE_MAIN_REVISION}\n`);
