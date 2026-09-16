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
} else if (args.slice(0, 3).join(' ') === 'buildx imagetools create') {
  const tag = args[args.indexOf('--tag') + 1];
  const sources = args.slice(args.indexOf('--tag') + 2);
  state.indexes[tag] = sources.map((source) => state.registry[source]);
  state.createdIndex = state.indexes[tag];
  if (args.includes('--metadata-file')) {
    writeFileSync(args[args.indexOf('--metadata-file') + 1], JSON.stringify({
      'containerimage.descriptor': { digest: state.indexDigest },
    }));
  }
  if (state.changedIndexTag) state.indexes[tag] = state.indexes[tag].slice(0, 1);
} else if (args.slice(0, 3).join(' ') === 'buildx imagetools inspect') {
  const tag = args.includes('--raw') ? args.at(-1) : args[3];
  if (args.includes('--raw')) {
    output(JSON.stringify(state.indexOverride ?? {
      manifests: (tag.includes('@sha256:') ? state.createdIndex : state.indexes[tag]).map((image) => ({
        digest: image.digest, platform: { os: 'linux', architecture: image.architecture },
      })),
    }));
  } else {
    output(state.indexDigest);
  }
} else {
  process.exitCode = 81;
}
writeFileSync(statePath, JSON.stringify(state));
