#!/usr/bin/env node
// Writes the job summary of a nightly PIT run as Markdown on standard output, and the summary file
// that the next night compares with. A run split into shards passes one --report per shard.
//
// usage: summarize.mjs --report <mutations.xml>... --commit <sha> --ref <branch>
//                      --write <summary.json> [--previous <summary.json>]
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';

const { values: options } = parseArgs({
  options: {
    report: { type: 'string', multiple: true },
    commit: { type: 'string' },
    ref: { type: 'string' },
    write: { type: 'string' },
    previous: { type: 'string' },
  },
});

const STATUS_LABELS = {
  SURVIVED: 'survived',
  NO_COVERAGE: 'no test reaches it',
};

const ROOT_PACKAGE = 'com.streamarr.transcode.';

// Bumped whenever the summary's fields change, so a run never compares with a shape it cannot read.
const SUMMARY_FORMAT = 1;

// GitHub keeps at most 1 MiB of one step's summary; the HTML report lists every mutant.
const LISTED_MUTANTS = 500;

const ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };

function decode(text) {
  return text.replace(/&(lt|gt|amp|quot|apos);/g, (_, name) => ENTITIES[name]);
}

function element(body, name) {
  const match = body.match(new RegExp(`<${name}>([^<]*)</${name}>`));
  return match ? decode(match[1]) : '';
}

// PIT writes one <mutation> element per line, with single-quoted attributes and text-only children.
function parse(xml) {
  return [...xml.matchAll(/<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g)].map(([, attributes, body]) => ({
    detected: /\bdetected='true'/.test(attributes),
    status: attributes.match(/\bstatus='([A-Z_]+)'/)[1],
    mutatedClass: element(body, 'mutatedClass'),
    method: element(body, 'mutatedMethod'),
    methodDescription: element(body, 'methodDescription'),
    line: Number(element(body, 'lineNumber')),
    mutator: element(body, 'mutator'),
    description: element(body, 'description'),
  }));
}

function percent(part, whole) {
  return whole === 0 ? 'n/a' : `${((100 * part) / whole).toFixed(1)}%`;
}

function plural(count, noun) {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

function shortName(mutatedClass) {
  return mutatedClass.startsWith(ROOT_PACKAGE) ? mutatedClass.slice(ROOT_PACKAGE.length) : mutatedClass;
}

// Lines move whenever code above them changes, so a mutant is known again by everything but its line.
function identity(mutant) {
  return [mutant.mutatedClass, mutant.method, mutant.methodDescription, mutant.mutator, mutant.description].join(
    '\n',
  );
}

function cell(text) {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('|', String.raw`\|`);
}

function code(text) {
  return `\`${text.replaceAll('|', String.raw`\|`)}\``;
}

function statusCounts(mutants) {
  const counts = new Map();
  for (const mutant of mutants) {
    counts.set(mutant.status, (counts.get(mutant.status) ?? 0) + 1);
  }
  const order = ['KILLED', 'TIMED_OUT', 'MEMORY_ERROR', 'RUN_ERROR', 'NON_VIABLE', 'SURVIVED', 'NO_COVERAGE'];
  const rank = (status) => (order.includes(status) ? order.indexOf(status) : order.length);
  const statuses = [...counts.keys()].sort((a, b) => rank(a) - rank(b));
  const text = statuses.map((status) => `${label(status)} ${counts.get(status)}`).join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function packageTable(mutants) {
  const packages = new Map();
  for (const mutant of mutants) {
    const name = shortName(mutant.mutatedClass).split('.')[0];
    const counts = packages.get(name) ?? { mutants: 0, detected: 0, survived: 0, noCoverage: 0 };
    counts.mutants += 1;
    counts.detected += mutant.detected ? 1 : 0;
    counts.survived += mutant.status === 'SURVIVED' ? 1 : 0;
    counts.noCoverage += mutant.status === 'NO_COVERAGE' ? 1 : 0;
    packages.set(name, counts);
  }
  const rows = [...packages.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(
      ([name, counts]) =>
        `| ${name} | ${counts.mutants} | ${counts.detected} | ${counts.survived} | ${counts.noCoverage} | ${percent(counts.detected, counts.mutants)} |`,
    );
  return ['| Package | Mutants | Detected | Survived | No coverage | Score |', '|---|---|---|---|---|---|', ...rows];
}

// Matches undetected mutants against the previous night's by identity, so a mutant that only moved
// is not new, and two alike in one method are told apart by count.
function newcomers(undetected, previous) {
  const remaining = new Map();
  for (const mutant of previous.undetected) {
    remaining.set(identity(mutant), (remaining.get(identity(mutant)) ?? 0) + 1);
  }
  const added = new Set();
  for (const mutant of undetected) {
    const left = remaining.get(identity(mutant)) ?? 0;
    if (left > 0) {
      remaining.set(identity(mutant), left - 1);
      continue;
    }
    added.add(mutant);
  }
  const gone = [...remaining.values()].reduce((sum, count) => sum + count, 0);
  return { added, gone };
}

function readPrevious(path) {
  if (path === undefined) {
    return { note: 'No earlier successful run on main left a summary to compare with.' };
  }
  const unreadable = { note: "The previous successful run's summary could not be read, so nothing is compared." };
  let previous;
  try {
    previous = JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return unreadable;
  }
  const readable =
    previous?.format === SUMMARY_FORMAT &&
    typeof previous.commit === 'string' &&
    Number.isInteger(previous.mutants) &&
    previous.mutants > 0 &&
    Number.isInteger(previous.detected) &&
    Array.isArray(previous.undetected);
  return readable ? { previous } : unreadable;
}

function score(summary) {
  return Number(((100 * summary.detected) / summary.mutants).toFixed(1));
}

function comparison(summary, previous, added, gone) {
  const before = score(previous);
  const now = score(summary);
  const change = now - before;
  const sign = change < 0 ? '' : '+';
  return `Since the previous successful run on main (\`${previous.commit.slice(0, 7)}\`): score ${before.toFixed(1)}% to ${now.toFixed(1)}% (${sign}${change.toFixed(1)} points); ${plural(added.size, 'undetected mutant')} ${added.size === 1 ? 'is' : 'are'} new and ${gone} ${gone === 1 ? 'is' : 'are'} gone.`;
}

function undetectedTable(undetected, added) {
  const rows = [...undetected]
    .sort((a, b) => a.mutatedClass.localeCompare(b.mutatedClass) || a.line - b.line)
    .slice(0, LISTED_MUTANTS)
    .map(
      (mutant) =>
        `| ${code(shortName(mutant.mutatedClass))} | ${mutant.line} | ${code(mutant.method)} | ${cell(mutant.description)} | ${status(mutant)}${added.has(mutant) ? ' (new)' : ''} |`,
    );
  return [
    '<details>',
    `<summary>${plural(undetected.length, 'mutant')} no test detected</summary>`,
    '',
    '| Class | Line | Method | Mutation | Status |',
    '|---|---|---|---|---|',
    ...rows,
    ...(undetected.length > LISTED_MUTANTS
      ? ['', `The HTML report lists the other ${undetected.length - LISTED_MUTANTS}.`]
      : []),
    '',
    '</details>',
  ];
}

function label(status) {
  return status.toLowerCase().replaceAll('_', ' ');
}

function status(mutant) {
  return STATUS_LABELS[mutant.status] ?? label(mutant.status);
}

function fail(message) {
  console.log(`## Mutation testing\n\n${message}`);
  process.exit(1);
}

const reports = options.report.filter((report) => existsSync(report));
if (reports.length === 0) {
  fail('PIT wrote no report. Read the log of the step that runs it.');
}
const missing = options.report
  .filter((report) => !reports.includes(report))
  .map((report) => `No report from \`${report}\`: its shard did not finish, so the score leaves out its classes.`);
const mutants = reports.flatMap((report) => parse(readFileSync(report, 'utf8')));
if (mutants.length === 0) {
  fail("PIT's report lists no mutants. Read the log of the step that runs it.");
}

const detected = mutants.filter((mutant) => mutant.detected).length;
const undetected = mutants.filter((mutant) => !mutant.detected);
const reached = mutants.filter((mutant) => mutant.status !== 'NO_COVERAGE').length;
const summary = {
  format: SUMMARY_FORMAT,
  commit: options.commit,
  mutants: mutants.length,
  detected,
  undetected: undetected.map(({ detected: _, ...mutant }) => mutant),
};
writeFileSync(options.write, `${JSON.stringify(summary, null, 2)}\n`);

const { previous, note } = readPrevious(options.previous);
const { added, gone } = previous === undefined ? { added: new Set(), gone: 0 } : newcomers(undetected, previous);

console.log(
  [
    '## Mutation testing',
    '',
    `PIT mutated the production classes of \`${options.commit.slice(0, 7)}\` on \`${options.ref}\`${options.report.length > 1 ? ` in ${options.report.length} shards` : ''} and ran the unit tests against each mutant. Each shard's HTML report is one of this run's \`mutation-report-*\` artifacts.`,
    '',
    ...missing.flatMap((line) => [line, '']),
    `**Mutation score: ${percent(detected, mutants.length)}** (${detected} of ${plural(mutants.length, 'mutant')} detected). Test strength: ${percent(detected, reached)} (${detected} of the ${reached} mutants that a test reaches).`,
    '',
    `${statusCounts(mutants)}.`,
    '',
    previous === undefined ? note : comparison(summary, previous, added, gone),
    '',
    ...packageTable(mutants),
    '',
    ...(undetected.length === 0 ? ['Every mutant was detected.'] : undetectedTable(undetected, added)),
  ].join('\n'),
);
