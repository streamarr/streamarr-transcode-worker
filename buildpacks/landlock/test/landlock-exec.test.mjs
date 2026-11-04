import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

// Landlock is a Linux feature, and the helper compiles only against Linux headers.
const linux = process.platform === 'linux';
const compile = fileURLToPath(new URL('../bin/compile', import.meta.url));
const build = mkdtempSync(join(tmpdir(), 'landlock-exec-'));
const helper = join(build, 'landlock-exec');
if (linux) execFileSync(compile, [helper], { stdio: 'pipe' });
process.on('exit', () => rmSync(build, { recursive: true, force: true }));

// The kernel's answer decides which behaviors this host can prove.
const abiProbe = linux ? spawnSync(helper, ['--abi'], { encoding: 'utf8' }) : { status: 2, stdout: '', stderr: '' };
const abi = abiProbe.status === 0 ? Number(abiProbe.stdout.trim()) : -1;
const landlock = linux && abi >= 1;

// Everything a shell and coreutils need, on Ubuntu and on merged-/usr distributions.
const systemRules = ['--read-execute', '/usr', '--read-execute', '/lib', '--read-execute', '/lib64', '--read', '/etc'];

function run(rules, command) {
  return spawnSync(helper, [...systemRules, ...rules, '--', ...command], { encoding: 'utf8' });
}

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'landlock-fixture-'));
  const media = join(directory, 'media');
  const outside = join(directory, 'outside');
  mkdirSync(media);
  mkdirSync(outside);
  writeFileSync(join(media, 'movie'), 'public bytes\n');
  writeFileSync(join(outside, 'secret'), 'secret bytes\n');
  return { directory, media, outside, remove: () => rmSync(directory, { recursive: true, force: true }) };
}

test('Should print the ABI version when the kernel offers Landlock', { skip: !landlock }, () => {
  assert.equal(abiProbe.status, 0, abiProbe.stderr);
  assert.match(abiProbe.stdout, /^[1-9][0-9]*\n$/);
});

test('Should exit 2 naming the errno when the kernel offers no Landlock', { skip: !linux || landlock }, () => {
  assert.equal(abiProbe.status, 2);
  assert.match(abiProbe.stderr, /landlock-exec: Landlock is unavailable: landlock_create_ruleset: E[A-Z]+: /);
});

test('Should run the command against a listed path when the path is read-only', { skip: !landlock }, () => {
  const { media, remove } = fixture();
  try {
    const result = run(['--read', media], ['/bin/cat', join(media, 'movie')]);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, 'public bytes\n');
  } finally {
    remove();
  }
});

test('Should deny a file outside every listed path when the command reads it', { skip: !landlock }, () => {
  const { media, outside, remove } = fixture();
  try {
    const result = run(['--read', media], ['/bin/cat', join(outside, 'secret')]);
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(result.stdout, /secret bytes/);
  } finally {
    remove();
  }
});

test('Should deny a soft link inside a listed path when its target lies outside', { skip: !landlock }, () => {
  const { media, outside, remove } = fixture();
  try {
    rmSync(join(media, 'movie'));
    symlinkSync(join(outside, 'secret'), join(media, 'movie'));
    const result = run(['--read', media], ['/bin/cat', join(media, 'movie')]);
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(result.stdout, /secret bytes/);
  } finally {
    remove();
  }
});

test('Should deny a soft-linked directory inside a listed path when its target lies outside', { skip: !landlock }, () => {
  const { media, outside, remove } = fixture();
  try {
    symlinkSync(outside, join(media, 'show'));
    const result = run(['--read', media], ['/bin/cat', join(media, 'show', 'secret')]);
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(result.stdout, /secret bytes/);
  } finally {
    remove();
  }
});

test('Should let the command create, truncate and remove files when the path is read-write', { skip: !landlock }, () => {
  const { media, remove } = fixture();
  try {
    const script = `echo first > "$1/out" && : > "$1/out" && echo second > "$1/out" && cat "$1/out" && rm "$1/out" && mkdir "$1/sub" && rmdir "$1/sub"`;
    const result = run(['--read-write', media], ['/bin/sh', '-c', script, 'sh', media]);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, 'second\n');
  } finally {
    remove();
  }
});

test('Should deny writing when the path is read-only', { skip: !landlock }, () => {
  const { media, remove } = fixture();
  try {
    const result = run(['--read', media], ['/bin/sh', '-c', 'echo x > "$1/out"', 'sh', media]);
    assert.notEqual(result.status, 0);
    assert.equal(readFileSync(join(media, 'movie'), 'utf8'), 'public bytes\n');
  } finally {
    remove();
  }
});

test('Should let the command read and write a device node when it is listed as a device', { skip: !landlock }, () => {
  const result = run(['--device', '/dev/null'], ['/bin/sh', '-c', ': > /dev/null && cat /dev/null && echo reached']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'reached\n');
});

test('Should deny a device node when it is not listed', { skip: !landlock }, () => {
  const result = run([], ['/bin/sh', '-c', ': > /dev/null']);
  assert.notEqual(result.status, 0);
});

test('Should skip a listed path that does not exist when building the ruleset', { skip: !landlock }, () => {
  const { media, directory, remove } = fixture();
  try {
    const result = run(['--device', join(directory, 'no-device'), '--read', join(directory, 'no-dir'), '--read', media], ['/bin/cat', join(media, 'movie')]);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, 'public bytes\n');
  } finally {
    remove();
  }
});

test('Should deny a TCP connection when the kernel restricts the network', { skip: !landlock || abi < 4 }, async () => {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const connect = ['/bin/bash', '-c', `exec 3<>/dev/tcp/127.0.0.1/${server.address().port}`];
    const control = spawnSync(connect[0], connect.slice(1), { encoding: 'utf8' });
    assert.equal(control.status, 0, `the unconfined control connects: ${control.stderr}`);
    const result = run([], connect);
    assert.notEqual(result.status, 0);
  } finally {
    server.close();
  }
});

test("Should exit with the command's own status when the command exits", { skip: !landlock }, () => {
  assert.equal(run([], ['/bin/sh', '-c', 'exit 7']).status, 7);
});

test('Should pass every argument through unchanged when running the command', { skip: !landlock }, () => {
  const result = run([], ['/bin/sh', '-c', 'printf "%s|" "$@"', 'sh', 'one', 'two words', '--read', '--']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'one|two words|--read|--|');
});

test('Should exit 127 when the command does not exist', { skip: !landlock }, () => {
  const result = run([], ['/nonexistent/binary']);
  assert.equal(result.status, 127);
  assert.match(result.stderr, /cannot execute \/nonexistent\/binary: ENOENT/);
});

test('Should exit 125 with usage when the command separator is missing', { skip: !linux }, () => {
  const result = spawnSync(helper, ['--read', '/tmp', '/bin/true'], { encoding: 'utf8' });
  assert.equal(result.status, 125);
  assert.match(result.stderr, /^usage: landlock-exec --abi/);
});

test('Should exit 125 with usage when an option is unknown or lacks its path', { skip: !linux }, () => {
  for (const args of [['--bogus', '/tmp', '--', '/bin/true'], ['--read', '--', '/bin/true'], ['--']]) {
    const result = spawnSync(helper, args, { encoding: 'utf8' });
    assert.equal(result.status, 125, args.join(' '));
    assert.match(result.stderr, /^usage: landlock-exec --abi/);
  }
});
