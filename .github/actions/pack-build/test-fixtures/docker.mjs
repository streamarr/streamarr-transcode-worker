#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';

const statePath = process.env.FAKE_REGISTRY;
const state = JSON.parse(readFileSync(statePath, 'utf8'));
const args = process.argv.slice(2);
const imageRepository = 'streamarr/streamarr-transcode-worker';
const output = (value) => process.stdout.write(`${value}\n`);

if (args[0] === 'image' && args[1] === 'inspect') {
  output(state.local[args.at(-1)].source);
} else if (args[0] === 'tag') {
  state.local[args[2]] = state.local[args[1]];
} else if (args[0] === 'push') {
  state.registry[args[1]] = state.local[args[1]];
} else if (args[0] === 'inspect') {
  output(`${imageRepository}@${state.registry[args.at(-1)].digest}`);
} else {
  process.exitCode = 81;
}
writeFileSync(statePath, JSON.stringify(state));
