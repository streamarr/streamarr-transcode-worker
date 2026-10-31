#!/usr/bin/env node
// Reads the NVENC recordings that record-nvenc.sh makes on an NVIDIA GPU host, and checks the
// results file they leave offline: no Docker, no FFmpeg, no GPU.
//
// For each recording it keeps the facts its own bytes decide: every video sample's presentation time
// and sync flag, the picture type that starts each keyframe, and the initialization segment. From
// those facts alone it derives the verdicts that the ADR 0037 amendment asks of an encoder before it
// joins the keyframe-verified list (streamarr/streamarr-transcode-worker#56), and the codec string
// that the check of streamarr/streamarr-transcode-worker#25 compares with the job's.
//
//   node nvenc-recordings.mjs analyze WORK RESULTS   (record-nvenc.sh runs this)
//   node nvenc-recordings.mjs check [RESULTS]        (default: src/test/resources/nvenc)

import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { sha256, videoPictures } from './analysis.mjs';
import { boxes, deliveredInitializationSegment, readStream, videoSamples, videoTrackOf } from './fmp4.mjs';
import { formatJson, parseJson } from './json.mjs';

export const RESULTS_FILE = 'recordings.json';
const DEFAULT_RESULTS = fileURLToPath(new URL('../resources/nvenc', import.meta.url));

// An IDR picture: H.264 NAL unit type 5, HEVC 19 (IDR_W_RADL) or 20 (IDR_N_LP).
const IDR_NAL_UNIT_TYPES = { avc1: new Set([5]), hvc1: new Set([19, 20]) };

/** The facts one recording's bytes decide, compact enough to commit. */
export function recordingFacts(stream) {
  const track = videoTrackOf(stream);
  const samples = videoSamples(stream);
  const pictures = videoPictures(stream);
  return {
    sampleEntry: track.sampleEntry,
    timescale: track.timescale,
    presentationTimes: samples.map((sample) => Number(sample.presentationTime)),
    syncSamples: samples.flatMap((sample, index) => (sample.sync ? [index] : [])),
    keyframePictures: (pictures?.keyframes ?? []).map((keyframe) => ({
      presentationTime: Number(keyframe.presentationTime),
      nalUnitType: keyframe.nalUnitType ?? null,
    })),
  };
}

/** The samples in presentation order, each with its decode index and whether it is a sync sample. */
function presentationOrder({ presentationTimes, syncSamples }) {
  const sync = new Set(syncSamples);
  return presentationTimes
    .map((presentationTime, decodeIndex) => ({ presentationTime, decodeIndex, sync: sync.has(decodeIndex) }))
    .sort((a, b) => a.presentationTime - b.presentationTime);
}

/**
 * The pictures after a keyframe in decode order, up to the next keyframe, that come before it in
 * presentation order: RADL or RASL pictures in HEVC, and any reordered picture in H.264.
 */
function leadingPictures({ presentationTimes, syncSamples }, decodeIndex) {
  const next = syncSamples.find((index) => index > decodeIndex) ?? presentationTimes.length;
  return presentationTimes
    .slice(decodeIndex + 1, next)
    .filter((presentationTime) => presentationTime < presentationTimes[decodeIndex]).length;
}

function keyframe(facts, frame) {
  const picture = facts.keyframePictures.find((entry) => entry.presentationTime === frame.presentationTime);
  const nalUnitType = picture?.nalUnitType ?? null;
  const leading = leadingPictures(facts, frame.decodeIndex);
  const idr = IDR_NAL_UNIT_TYPES[facts.sampleEntry]?.has(nalUnitType) ?? false;
  return {
    presentationTime: frame.presentationTime,
    sync: frame.sync,
    nalUnitType,
    leadingPictures: leading,
    startsClosedGop: frame.sync && idr && leading === 0,
  };
}

/** The first frame at or after each forced time that the recording reaches, in presentation order. */
function forcedFrames(facts, order, forcedKeyframeTimes) {
  return forcedKeyframeTimes.flatMap((time) => {
    const frame = order.find((entry) => entry.presentationTime >= time * facts.timescale);
    return frame === undefined ? [] : [{ time, frame }];
  });
}

function gopRestart(facts, order, { forcedKeyframeTimes, gopFrames, skippedForcedTime }) {
  if (skippedForcedTime === null) {
    return null;
  }
  const before = forcedKeyframeTimes.filter((time) => time < skippedForcedTime).at(-1);
  if (before === undefined) {
    return null;
  }
  const start = order.findIndex((entry) => entry.presentationTime >= before * facts.timescale);
  const next = order.findIndex((entry, index) => index > start && entry.sync);
  const expected = order[start + gopFrames]?.presentationTime ?? null;
  const actual = next < 0 ? null : order[next].presentationTime;
  return {
    forcedKeyframeBefore: order[start].presentationTime,
    expectedKeyframe: expected,
    nextKeyframe: actual,
    restartsAtForcedKeyframe: expected !== null && actual === expected,
  };
}

/**
 * What the ADR 0037 amendment asks a recording to show, derived from its facts alone: whether every
 * forced keyframe is a sync sample that starts a closed GOP (an IDR picture with no leading
 * pictures), the keyframes the encoder adds itself, and, for a recording that leaves one forced time
 * out, whether the GOP count restarted at the forced keyframe before it.
 */
export function keyframeVerdicts(facts, plan) {
  const order = presentationOrder(facts);
  const forced = forcedFrames(facts, order, plan.forcedKeyframeTimes).map(({ time, frame }) => ({
    time,
    ...keyframe(facts, frame),
  }));
  const forcedTimes = new Set(forced.map((entry) => entry.presentationTime));
  const extraKeyframes = [];
  let previous = 0;
  order.forEach((frame, index) => {
    if (!frame.sync) {
      return;
    }
    if (index > 0 && !forcedTimes.has(frame.presentationTime)) {
      const framesAfterPreviousKeyframe = index - previous;
      extraKeyframes.push({
        ...keyframe(facts, frame),
        framesAfterPreviousKeyframe,
        placedByGop: framesAfterPreviousKeyframe === plan.gopFrames,
      });
    }
    previous = index;
  });
  return {
    firstFrame: keyframe(facts, order[0]),
    forcedKeyframes: forced,
    everyForcedKeyframeIsASyncSample: forced.every((entry) => entry.sync),
    everyForcedKeyframeStartsAClosedGop: forced.every((entry) => entry.startsClosedGop),
    extraKeyframes,
    encoderAddsKeyframes: extraKeyframes.some((entry) => !entry.placedByGop),
    gopRestart: gopRestart(facts, order, plan),
  };
}

// The decoder configuration record of each sample entry the NVENC encoders write, after the
// visual sample entry's own fields (ISO/IEC 14496-12 12.1.3).
const CONFIGURATION_RECORDS = { avc1: 'avcC', hvc1: 'hvcC' };
const VISUAL_SAMPLE_ENTRY_BYTES = 78;

/** The [type, start, body, stop] of the first child box of that type, else null. */
function childBox(data, [body, stop], type) {
  for (const found of boxes(data, body, stop)) {
    if (found[0] === type) {
      return found;
    }
  }
  return null;
}

function bodyOf(found) {
  return [found[2], found[3]];
}

function handlerOf(data, mdia) {
  const hdlr = childBox(data, mdia, 'hdlr');
  return data.toString('latin1', hdlr[2] + 8, hdlr[2] + 12);
}

/** The first video track's sample entry type and the bytes of its decoder configuration record. */
function videoConfiguration(data) {
  const moov = childBox(data, [0, data.length], 'moov');
  for (const found of boxes(data, moov[2], moov[3])) {
    const mdia = found[0] === 'trak' ? childBox(data, bodyOf(found), 'mdia') : null;
    if (mdia === null || handlerOf(data, bodyOf(mdia)) !== 'vide') {
      continue;
    }
    const stsd = childBox(data, bodyOf(childBox(data, bodyOf(childBox(data, bodyOf(mdia), 'minf')), 'stbl')), 'stsd');
    const [entry] = boxes(data, stsd[2] + 8, stsd[3]);
    const recordType = CONFIGURATION_RECORDS[entry[0]];
    const record = recordType === undefined ? null : childBox(data, [entry[2] + VISUAL_SAMPLE_ENTRY_BYTES, entry[3]], recordType);
    return { sampleEntry: entry[0], record: record === null ? null : data.subarray(record[2], record[3]) };
  }
  throw new Error('the initialization segment declares no video track');
}

const AVC_PROFILES_WITH_FORMAT = new Set([100, 110, 122, 144, 244]);
const CHROMA = ['4:0:0', '4:2:0', '4:2:2', '4:4:4'];
const HEVC_PARAMETER_SET_TYPES = [32, 33, 34];

function hex(value) {
  return value.toString(16).toUpperCase();
}

/** Each length-prefixed parameter set from position, and the position after them. */
function skipParameterSets(record, position, count) {
  let at = position;
  for (let index = 0; index < count; index++) {
    at += 2 + record.readUInt16BE(at);
  }
  return at;
}

function avc(sampleEntry, record) {
  const [, profile, constraints, level] = record;
  const codecString = `${sampleEntry}.${[profile, constraints, level].map((byte) => hex(byte).padStart(2, '0')).join('')}`;
  const sequenceParameterSets = record[5] & 0x1f;
  const afterSequenceParameterSets = skipParameterSets(record, 6, sequenceParameterSets);
  const pictureParameterSets = record[afterSequenceParameterSets];
  const afterParameterSets = skipParameterSets(record, afterSequenceParameterSets + 1, pictureParameterSets);
  let pictureFormat = { bitDepth: 8, chroma: '4:2:0' };
  if (AVC_PROFILES_WITH_FORMAT.has(profile)) {
    pictureFormat =
      record.length - afterParameterSets < 3
        ? null
        : { bitDepth: (record[afterParameterSets + 1] & 0x07) + 8, chroma: CHROMA[record[afterParameterSets] & 0x03] };
  }
  return { codecString, pictureFormat, holdsParameterSets: sequenceParameterSets > 0 && pictureParameterSets > 0 };
}

function reversedBits(value) {
  let reversed = 0;
  for (let bit = 0; bit < 32; bit++) {
    reversed = ((reversed << 1) | ((value >>> bit) & 1)) >>> 0;
  }
  return reversed;
}

function hevcArrayTypes(record) {
  const types = new Set();
  let at = 23;
  for (let array = 0; array < record[22]; array++) {
    const type = record[at] & 0x3f;
    const nalUnits = record.readUInt16BE(at + 1);
    at = skipParameterSets(record, at + 3, nalUnits);
    if (nalUnits > 0) {
      types.add(type);
    }
  }
  return types;
}

function hevc(sampleEntry, record) {
  const spaceTierAndProfile = record[1];
  const constraints = [...record.subarray(6, 12)];
  while (constraints.at(-1) === 0) {
    constraints.pop();
  }
  const space = ['', 'A', 'B', 'C'][spaceTierAndProfile >> 6];
  const tier = (spaceTierAndProfile & 0x20) === 0 ? 'L' : 'H';
  const codecString =
    `${sampleEntry}.${space}${spaceTierAndProfile & 0x1f}.${hex(reversedBits(record.readUInt32BE(2)))}` +
    `.${tier}${record[12]}${constraints.map((byte) => `.${hex(byte)}`).join('')}`;
  const types = hevcArrayTypes(record);
  return {
    codecString,
    pictureFormat: { bitDepth: (record[17] & 0x07) + 8, chroma: CHROMA[record[16] & 0x03] },
    holdsParameterSets: HEVC_PARAMETER_SET_TYPES.every((type) => types.has(type)),
  };
}

/**
 * The digests of the initialization segment as FFmpeg wrote it and as the worker delivers it, and
 * what the output check of streamarr/streamarr-transcode-worker#25 reads from it, the way
 * CodecConfigurationReader reads it: the codec string, the picture format and whether the record
 * holds the parameter sets that its sample entry promises.
 */
export function initializationSegmentFacts(bytes) {
  const { sampleEntry, record } = videoConfiguration(bytes);
  const digests = { sha256: sha256(bytes), deliveredSha256: sha256(deliveredInitializationSegment(bytes)) };
  switch (record === null ? null : sampleEntry) {
    case 'avc1':
      return { ...digests, ...avc(sampleEntry, record) };
    case 'hvc1':
      return { ...digests, ...hevc(sampleEntry, record) };
    default:
      return { ...digests, codecString: sampleEntry, pictureFormat: null, holdsParameterSets: record !== null };
  }
}

/** Whether the initialization segment passes the output check against the job's codec string. */
export function outputCheck(initializationSegment, expectedCodecString) {
  const { codecString, pictureFormat, holdsParameterSets } = initializationSegment;
  return {
    expectedCodecString,
    codecString,
    passes:
      codecString === expectedCodecString &&
      pictureFormat?.bitDepth === 8 &&
      pictureFormat?.chroma === '4:2:0' &&
      holdsParameterSets,
  };
}

/** Each replacement attempt with the first attempt it replaces: identical delivered initialization segments or not. */
export function initializationSegmentPairs(recordings) {
  const byName = new Map(recordings.map((recording) => [recording.name, recording]));
  return recordings
    .filter((recording) => recording.replaces !== null)
    .map((replacement) => {
      const first = byName.get(replacement.replaces);
      return {
        first: first.name,
        replacement: replacement.name,
        identical: first.initializationSegment.deliveredSha256 === replacement.initializationSegment.deliveredSha256,
      };
    });
}

/** What a recording's plan, facts and initialization segment decide. */
export function derivedResults(recording) {
  return {
    verdicts: keyframeVerdicts(recording.facts, recording.plan),
    outputCheck: outputCheck(recording.initializationSegment, recording.plan.expectedCodecString),
  };
}

/** Reads key=value lines. */
function keyValues(text) {
  return Object.fromEntries(
    text
      .split('\n')
      .filter((line) => line.includes('='))
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
  );
}

function numbers(list) {
  return list === '' ? [] : list.split(',').map(Number);
}

/** The plan that record-nvenc.sh wrote beside a recording. */
export function planOf(meta) {
  const values = keyValues(meta);
  return {
    encoder: values.encoder,
    rung: values.rung,
    recipe: values.recipe,
    startSequenceNumber: Number(values.startSequenceNumber),
    gopFrames: Number(values.gopFrames),
    forcedKeyframeTimes: numbers(values.forcedKeyframeTimes),
    skippedForcedTime: values.skippedForcedTime === '' ? null : Number(values.skippedForcedTime),
    level: Number(values.level),
    tier: values.tier === '' ? null : values.tier,
    expectedCodecString: values.expectedCodecString,
  };
}

/** Reads every recording record-nvenc.sh left in WORK and writes the results file and initialization segments to RESULTS. */
export function analyze(work, results) {
  const out = join(work, 'out');
  const names = readdirSync(out)
    .filter((file) => file.endsWith('.fmp4'))
    .map((file) => file.slice(0, -'.fmp4'.length))
    .sort();
  const recordings = names.map((name) => {
    const stream = readStream(readFileSync(join(out, `${name}.fmp4`)));
    const meta = readFileSync(join(out, `${name}.meta`), 'utf8');
    const replaces = keyValues(meta).replaces;
    const initializationFile = `${name}.init.mp4`;
    writeFileSync(join(results, initializationFile), stream.initializationSegmentBytes);
    const recording = {
      name,
      replaces: replaces === '' ? null : replaces,
      plan: planOf(meta),
      ffmpegArguments: readFileSync(join(out, `${name}.args`), 'utf8').split('\n').filter((line) => line !== ''),
      initializationSegment: { file: initializationFile, ...initializationSegmentFacts(stream.initializationSegmentBytes) },
      facts: recordingFacts(stream),
    };
    return { ...recording, ...derivedResults(recording) };
  });
  const document = {
    environment: keyValues(readFileSync(join(work, 'environment'), 'utf8')),
    recordings,
    initializationSegmentPairs: initializationSegmentPairs(recordings),
  };
  writeFileSync(join(results, RESULTS_FILE), compactJson(document));
  return document;
}

/** JSON with two-space indents, and every array of integers on one line. */
export function compactJson(document) {
  return `${JSON.stringify(document, null, 2).replace(/\[\n\s+(-?\d+(?:,\n\s+-?\d+)*)\n\s+\]/g, (_match, items) => `[${items.replace(/,\n\s+/g, ', ')}]`)}\n`;
}

/** One line per recording: the verdicts a person reads first. */
export function summary(document) {
  const pairs = new Map(document.initializationSegmentPairs.map((pair) => [pair.replacement, pair.identical]));
  return document.recordings.map(({ name, verdicts, outputCheck: check }) =>
    [
      name.padEnd(40),
      `sync=${verdicts.everyForcedKeyframeIsASyncSample}`,
      `closed-gop=${verdicts.everyForcedKeyframeStartsAClosedGop}`,
      `extra=${verdicts.extraKeyframes.length}`,
      `gop-restart=${verdicts.gopRestart?.restartsAtForcedKeyframe ?? '-'}`,
      `init-identical=${pairs.get(name) ?? '-'}`,
      `output-check=${check.passes} (${check.codecString})`,
    ].join(' '),
  );
}

function same(recorded, derived) {
  return formatJson(recorded) === formatJson(parseJson(formatJson(derived)));
}

/** Every result in the results file that its committed facts and initialization segments do not reproduce. */
export function checkRecordings(directory) {
  const document = parseJson(readFileSync(join(directory, RESULTS_FILE), 'utf8'));
  const found = [];
  for (const recording of document.recordings) {
    const path = join(directory, recording.initializationSegment.file);
    if (!existsSync(path)) {
      found.push(`${recording.name}: ${recording.initializationSegment.file} is missing`);
      continue;
    }
    const initializationSegment = { file: recording.initializationSegment.file, ...initializationSegmentFacts(readFileSync(path)) };
    if (!same(recording.initializationSegment, initializationSegment)) {
      found.push(`${recording.name}: the initialization segment facts do not match ${recording.initializationSegment.file}`);
    }
    const derived = derivedResults({ ...recording, initializationSegment });
    for (const key of Object.keys(derived)) {
      if (!same(recording[key], derived[key])) {
        found.push(`${recording.name}: ${key} does not follow from the recorded facts`);
      }
    }
  }
  if (!same(document.initializationSegmentPairs, initializationSegmentPairs(document.recordings))) {
    found.push('initializationSegmentPairs does not follow from the initialization segments');
  }
  return found;
}

function main([command, ...paths]) {
  if (command === 'analyze' && paths.length === 2) {
    process.stdout.write(`${summary(analyze(paths[0], paths[1])).join('\n')}\n`);
    return 0;
  }
  if (command === 'check' && paths.length <= 1) {
    const found = checkRecordings(paths[0] ?? DEFAULT_RESULTS);
    for (const line of found) {
      process.stderr.write(`${line}\n`);
    }
    return found.length === 0 ? 0 : 1;
  }
  process.stderr.write('usage: nvenc-recordings.mjs analyze WORK RESULTS | check [RESULTS]\n');
  return 2;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exitCode = main(process.argv.slice(2));
}

export { main };
