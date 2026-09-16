#!/usr/bin/env node
const args = process.argv.slice(2);
if (args[0] === 'image' && args[1] === 'inspect') {
  const format = args[args.indexOf('--format') + 1];
  const label = format?.match(/index \.Config\.Labels "([^"]+)"/)?.[1];
  if (label) process.stdout.write(`${JSON.parse(process.env.IMAGE_LABELS)[label] ?? '<no value>'}\n`);
} else if (args[0] === 'run') {
  process.stdin.resume();
} else {
  process.exitCode = 81;
}
