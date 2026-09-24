#!/usr/bin/env node
// Checks expected.json against the committed recordings offline: no Docker, no FFmpeg. It re-reads
// every .fmp4, re-derives with the grid model everything that the recording's own bytes decide
// (tracks, initialization segment, media segments, preroll, failure, audio-only tail, diagnostics,
// a copy's keyframes, each HLS oracle's disagreements with the grid, the initialization-segment
// pairs), and names every fact expected.json states differently. What only a recording run can
// decide (the HLS muxer's own cuts, the sources, the ADR side claims) it takes as recorded.
//
//   node check-expectations.mjs [FIXTURES_DIR]   (default: src/test/resources/fmp4)

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { initializationSegmentPair, mismatches, recordedKeyframes, recordingFacts } from './analysis.mjs';
import { readStream } from './fmp4.mjs';
import { formatJson, parseJson } from './json.mjs';

/** The value as expected.json holds it, so that a derived BigInt equals a parsed small number. */
function asRecorded(value) {
  return parseJson(formatJson(value));
}

function same(recorded, derived) {
  return formatJson(recorded) === formatJson(derived);
}

function fixtureDisagreements(fixture, stream) {
  const facts = recordingFacts(stream, {
    period: fixture.period,
    startSequenceNumber: fixture.startSequenceNumber,
  });
  const differing = Object.keys(facts).filter((key) => !same(fixture[key], facts[key]));
  const keyframes = fixture.sourceKeyframeCheck?.keyframePresentationTimes;
  if (keyframes !== undefined && !same(keyframes, recordedKeyframes(stream))) {
    differing.push('sourceKeyframeCheck.keyframePresentationTimes');
  }
  const found = differing.map((key) => `${fixture.name}: ${key} in expected.json does not match the recording`);
  const grid = asRecorded(facts.segments);
  for (const oracle of fixture.hlsOracles) {
    const derived = mismatches(grid, oracle.segments);
    if (!same(oracle.mismatches, derived) || oracle.agrees !== (derived.length === 0)) {
      found.push(
        `${fixture.name} / ${oracle.hlsRun}: mismatches or agrees in expected.json does not match the grid segments`,
      );
    }
  }
  return found;
}

function readRecording(directory, fixture, streams) {
  const path = join(directory, fixture.file);
  if (!existsSync(path)) {
    return [`${fixture.name}: ${fixture.file} is missing`];
  }
  try {
    streams.set(fixture.file, readStream(readFileSync(path)));
  } catch (error) {
    return [`${fixture.name}: ${fixture.file} cannot be read: ${error.message}`];
  }
  return fixtureDisagreements(fixture, streams.get(fixture.file));
}

function pairDisagreements(pairs, streams) {
  return pairs
    .filter((pair) => pair.files.every((file) => streams.has(file)))
    .filter((pair) => {
      const [first, second] = pair.files.map((file) => [file.replace(/\.fmp4$/, ''), streams.get(file)]);
      return !same(pair, initializationSegmentPair(pair.label, first, second));
    })
    .map((pair) => `${pair.label}: the initialization segment comparison in expected.json does not match the recordings`);
}

/** Every way expected.json disagrees with the recordings in the directory; empty when none. */
export function checkExpectations(directory) {
  const expected = parseJson(readFileSync(join(directory, 'expected.json'), 'utf8'));
  const streams = new Map();
  const described = new Set(expected.fixtures.map((fixture) => fixture.file));
  return [
    ...expected.fixtures.flatMap((fixture) => readRecording(directory, fixture, streams)),
    ...pairDisagreements(
      [...expected.initializationSegmentIdentityPairs, ...expected.initializationSegmentDifferencePairs],
      streams,
    ),
    ...readdirSync(directory)
      .filter((file) => file.endsWith('.fmp4') && !described.has(file))
      .sort()
      .map((file) => `${file}: no fixture in expected.json describes this recording`),
  ];
}

function main(directory) {
  const found = checkExpectations(directory);
  for (const disagreement of found) {
    process.stderr.write(`DRIFT ${disagreement}\n`);
  }
  if (found.length > 0) {
    process.exitCode = 1;
    return;
  }
  const count = readdirSync(directory).filter((file) => file.endsWith('.fmp4')).length;
  process.stdout.write(`expected.json agrees with all ${count} recordings in ${directory}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main(process.argv[2] ?? fileURLToPath(new URL('../resources/fmp4/', import.meta.url)));
}
