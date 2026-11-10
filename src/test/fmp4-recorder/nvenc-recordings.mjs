#!/usr/bin/env node
// Reads the NVENC recordings that record-nvenc.sh makes on an NVIDIA GPU host, and checks the
// results file they leave offline: no Docker, no FFmpeg, no GPU.
//
// For each recording it keeps the facts its own bytes decide: every video sample's presentation time
// and sync flag, the picture type that starts each keyframe, the HEVC samples that carry static HDR
// metadata, and the initialization segment. From
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

// HEVC prefix and suffix SEI NAL units (ITU-T H.265 table 7-1), and the payload types of the
// mastering display colour volume and the content light level (D.2.1).
const HEVC_SEI_NAL_UNIT_TYPES = new Set([39, 40]);
const STATIC_HDR_METADATA_PAYLOAD_TYPES = new Set([137, 144]);

/** A NAL unit's payload after its two header bytes, without emulation prevention bytes. */
function rawPayload(nalUnit) {
  const bytes = [];
  for (let at = 2; at < nalUnit.length; at++) {
    if (nalUnit[at] === 3 && at >= 4 && nalUnit[at - 1] === 0 && nalUnit[at - 2] === 0) {
      continue;
    }
    bytes.push(nalUnit[at]);
  }
  return bytes;
}

/** The payload type of each message of an SEI NAL unit (ITU-T H.265 7.3.5). */
export function seiPayloadTypes(nalUnit) {
  const payload = rawPayload(nalUnit);
  const types = [];
  let at = 0;
  // The last byte holds only the RBSP trailing bits.
  while (at < payload.length - 1) {
    let type = 0;
    while (payload[at] === 0xff) {
      type += payload[at++];
    }
    type += payload[at++];
    let size = 0;
    while (payload[at] === 0xff) {
      size += payload[at++];
    }
    size += payload[at++];
    types.push(type);
    at += size;
  }
  return types;
}

/** The NAL units of one length-prefixed sample. */
function nalUnitsOf(stream, sample, nalLengthSize) {
  const units = [];
  const end = sample.offset + sample.size;
  let position = sample.offset;
  while (position + nalLengthSize <= end) {
    const length = stream.data.readUIntBE(position, nalLengthSize);
    units.push(stream.data.subarray(position + nalLengthSize, Math.min(end, position + nalLengthSize + length)));
    position += nalLengthSize + length;
  }
  return units;
}

function carriesStaticHdrMetadata(nalUnit) {
  return (
    HEVC_SEI_NAL_UNIT_TYPES.has((nalUnit[0] >> 1) & 0x3f) &&
    seiPayloadTypes(nalUnit).some((type) => STATIC_HDR_METADATA_PAYLOAD_TYPES.has(type))
  );
}

/** How many HEVC samples carry a mastering display or content light level message; null for another codec. */
function staticHdrMetadataSamples(stream, track, samples) {
  if (track.sampleEntry !== 'hvc1') {
    return null;
  }
  return samples.filter((sample) => nalUnitsOf(stream, sample, track.nalLengthSize).some(carriesStaticHdrMetadata)).length;
}

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
    staticHdrMetadataSamples: staticHdrMetadataSamples(stream, track, samples),
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

// A planned forced time that the recording never reaches, so that a truncated recording fails.
const MISSING_KEYFRAME = { presentationTime: null, sync: false, nalUnitType: null, leadingPictures: null, startsClosedGop: false };

/** The first frame at or after each forced time, in presentation order, else undefined. */
function forcedFrames(facts, order, forcedKeyframeTimes) {
  return forcedKeyframeTimes.map((time) => ({
    time,
    frame: order.find((entry) => entry.presentationTime >= time * facts.timescale),
  }));
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
    // ADR 0037 asks that the keyframe the GOP places for a missed one also start a closed GOP.
    nextKeyframeStartsClosedGop: next >= 0 && keyframe(facts, order[next]).startsClosedGop,
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
    ...(frame === undefined ? MISSING_KEYFRAME : keyframe(facts, frame)),
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

/** The nclx colour description of a sample entry's colr box (ISO/IEC 14496-12 12.1.5), else null. */
function colourDescriptionOf(data, entry) {
  const colr = childBox(data, [entry[2] + VISUAL_SAMPLE_ENTRY_BYTES, entry[3]], 'colr');
  if (colr === null || colr[3] - colr[2] < 11 || data.toString('latin1', colr[2], colr[2] + 4) !== 'nclx') {
    return null;
  }
  return {
    primaries: data.readUInt16BE(colr[2] + 4),
    transfer: data.readUInt16BE(colr[2] + 6),
    matrix: data.readUInt16BE(colr[2] + 8),
    fullRange: (data[colr[2] + 10] & 0x80) !== 0,
  };
}

/** The first video track's sample entry type, the bytes of its decoder configuration record and its colour description. */
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
    return {
      sampleEntry: entry[0],
      record: record === null ? null : data.subarray(record[2], record[3]),
      colourDescription: colourDescriptionOf(data, entry),
    };
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
 * CodecConfigurationReader reads it: the codec string, the picture format, whether the record
 * holds the parameter sets that its sample entry promises, and the colour description of its colr
 * box.
 */
export function initializationSegmentFacts(bytes) {
  const { sampleEntry, record, colourDescription } = videoConfiguration(bytes);
  const digests = { sha256: sha256(bytes), deliveredSha256: sha256(deliveredInitializationSegment(bytes)) };
  switch (record === null ? null : sampleEntry) {
    case 'avc1':
      return { ...digests, ...avc(sampleEntry, record), colourDescription };
    case 'hvc1':
      return { ...digests, ...hevc(sampleEntry, record), colourDescription };
    default:
      return { ...digests, codecString: sampleEntry, pictureFormat: null, holdsParameterSets: record !== null, colourDescription };
  }
}

// The limited-range BT.2020 colour description that a PQ or HLG encode declares (ITU-T H.273):
// primaries 9, the range's transfer and the non-constant luminance matrix 9. The Dolby Vision range
// names its source: the GPU reshapes it into PQ.
const PQ_COLOUR_DESCRIPTION = { primaries: 9, transfer: 16, matrix: 9, fullRange: false };
const HDR_COLOUR_DESCRIPTIONS = {
  pq: PQ_COLOUR_DESCRIPTION,
  'dolby-vision': PQ_COLOUR_DESCRIPTION,
  hlg: { primaries: 9, transfer: 18, matrix: 9, fullRange: false },
};

/**
 * Whether the initialization segment passes the output check against the job: its codec string, its
 * bit depth in 4:2:0, its parameter sets and, for a job whose output is PQ or HLG, the colour
 * description of that output. Any other job's colour description is not compared, as the worker
 * compares none.
 */
export function outputCheck(initializationSegment, { expectedCodecString, bitDepth, range }) {
  const { codecString, pictureFormat, holdsParameterSets, colourDescription } = initializationSegment;
  const expectedColourDescription = HDR_COLOUR_DESCRIPTIONS[range] ?? null;
  return {
    expectedCodecString,
    codecString,
    expectedBitDepth: bitDepth,
    expectedColourDescription,
    passes:
      codecString === expectedCodecString &&
      pictureFormat?.bitDepth === bitDepth &&
      pictureFormat?.chroma === '4:2:0' &&
      holdsParameterSets &&
      (expectedColourDescription === null || formatJson(colourDescription) === formatJson(expectedColourDescription)),
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
    outputCheck: outputCheck(recording.initializationSegment, recording.plan),
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
    preset: values.preset,
    range: values.range,
    bitDepth: Number(values.bitDepth),
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
  return document.recordings.map(({ name, facts, verdicts, outputCheck: check }) =>
    [
      name.padEnd(40),
      `sync=${verdicts.everyForcedKeyframeIsASyncSample}`,
      `closed-gop=${verdicts.everyForcedKeyframeStartsAClosedGop}`,
      `extra=${verdicts.extraKeyframes.length}`,
      `gop-restart=${verdicts.gopRestart?.restartsAtForcedKeyframe ?? '-'}`,
      `gop-restart-closed-gop=${verdicts.gopRestart?.nextKeyframeStartsClosedGop ?? '-'}`,
      `init-identical=${pairs.get(name) ?? '-'}`,
      `output-check=${check.passes} (${check.codecString})`,
      `static-hdr-samples=${facts.staticHdrMetadataSamples ?? '-'}`,
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
