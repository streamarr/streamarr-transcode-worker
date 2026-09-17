import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('../analyze.sh', import.meta.url));
const coverageFiles = [
  'target/jacoco-output/jacoco-unit-tests.exec',
  'target/jacoco-output/jacoco-integration-tests.exec',
  'target/site/jacoco-merged-test-coverage-report/jacoco.xml',
];

function fixture(t, { scannerExit = 0 } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'worker-sonar-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  for (const file of coverageFiles) {
    const target = join(directory, file);
    mkdirSync(join(target, '..'), { recursive: true });
    writeFileSync(target, 'coverage');
  }
  writeFileSync(join(directory, 'mvnw'), `#!/bin/sh
case " $* " in
  *" org.sonarsource.scanner.maven:sonar-maven-plugin:sonar "*) ;;
  *) exit 71 ;;
esac
case " $* " in
  *" -Dsonar.qualitygate.wait=true "*) ;;
  *) exit 72 ;;
esac
exit ${scannerExit}
`, { mode: 0o755 });
  return {
    directory,
    run: ({ token = 'test-analysis-token' } = {}) => spawnSync('bash', [script], {
      cwd: directory,
      encoding: 'utf8',
      env: { ...process.env, SONAR_TOKEN: token },
    }),
  };
}

test('Should wait for the quality gate when analyzing complete coverage', (t) => {
  const result = fixture(t).run();

  assert.equal(result.status, 0, result.stderr);
});

test('Should fail with a setup instruction when the analysis token is missing', (t) => {
  const result = fixture(t).run({ token: '' });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Grant this repository access to ORG_SONAR_TOKEN/);
});

for (const file of coverageFiles) {
  test(`Should reject incomplete coverage when ${file} is empty`, (t) => {
    const analysis = fixture(t);
    writeFileSync(join(analysis.directory, file), '');

    const result = analysis.run();

    assert.notEqual(result.status, 0);
    assert.ok(result.stderr.includes(`Missing coverage: ${file}`));
  });
}

test('Should fail the command when the scanner or quality gate fails', (t) => {
  const result = fixture(t, { scannerExit: 23 }).run();

  assert.equal(result.status, 23);
});
