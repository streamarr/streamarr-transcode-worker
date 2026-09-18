#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';

const path = process.env.FAKE_BUILD_STATE;
const state = JSON.parse(readFileSync(path, 'utf8'));
const args = process.argv.slice(2);
const output = (value) => process.stdout.write(`${value}\n`);

switch (basename(process.argv[1])) {
  case 'mvnw':
    if (args.includes('-Dexpression=project.version')) {
      output(state.version);
      break;
    }
    if (!args.includes('-Dexpression=buf.sdk.version')) process.exit(81);
    output(state.contract);
    break;
  case 'pack': {
    if (args[0] !== 'build') process.exit(81);
    const environment = Object.fromEntries(args.flatMap((argument, index) => {
      if (argument !== '--env') return [];
      const assignment = args[index + 1];
      const separator = assignment.indexOf('=');
      return [[assignment.slice(0, separator), assignment.slice(separator + 1)]];
    }));
    state.images[args[1]] = {
      'org.opencontainers.image.version': environment.BP_OCI_VERSION,
      'org.opencontainers.image.source': environment.BP_OCI_SOURCE,
      'org.opencontainers.image.revision': environment.BP_OCI_REVISION,
      ...Object.fromEntries(environment.BP_IMAGE_LABELS.split(' ').map((label) => label.split('='))),
    };
    writeFileSync(path, JSON.stringify(state));
    break;
  }
  case 'docker': {
    if (args[0] === 'run') {
      process.stdin.resume();
      break;
    }
    if (args[0] !== 'image' || args[1] !== 'inspect') process.exit(81);
    const labels = state.images[args.at(-1)];
    if (!labels) process.exit(1);
    const format = args[args.indexOf('--format') + 1];
    const label = format?.match(/index \.Config\.Labels "([^"]+)"/)?.[1];
    if (label) output(labels[label] ?? '<no value>');
    break;
  }
  default:
    process.exit(81);
}
