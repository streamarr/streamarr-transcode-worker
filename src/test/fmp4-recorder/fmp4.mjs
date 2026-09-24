#!/usr/bin/env node
// Independent reader for FFmpeg's fragmented MP4 output (no Java, no FFmpeg).
//
// Reads top-level ISOBMFF boxes: ftyp + moov form the initialization segment; each moof with the
// mdat that follows it forms one fragment, and every sample the moof describes must lie inside that
// mdat. For every fragment it reports, per track, the first
// sample's presentation time (tfdt baseMediaDecodeTime + that sample's composition offset from
// trun, signed when trun version is 1, no edit list), whether that sample is a sync sample (trun
// first_sample_flags, else trun per-sample flags of sample 0, else tfhd default_sample_flags,
// else trex default; sync iff sample_is_non_sync_sample 0x00010000 is clear), the sample count and
// the sum of sample durations (reported only to show it is not a usable fragment end). Decode and
// presentation times are BigInts.
//
// Usage: node fmp4.mjs FILE [FILE ...]   (several files are read as one concatenated stream, e.g.
// init.mp4 segment0.m4s segment1.m4s for an HLS output)

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { formatJson } from './json.mjs';

export const VIDEO_HANDLER = 'vide';
export const AUDIO_HANDLER = 'soun';

const TFHD_BASE_DATA_OFFSET = 0x000001;
const TFHD_SAMPLE_DESCRIPTION_INDEX = 0x000002;
const TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
const TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
const TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;
const TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;
const TRUN_DATA_OFFSET = 0x000001;
const TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
const TRUN_SAMPLE_DURATION = 0x000100;
const TRUN_SAMPLE_SIZE = 0x000200;
const TRUN_SAMPLE_FLAGS = 0x000400;
const TRUN_SAMPLE_COMPOSITION_TIME_OFFSET = 0x000800;
const SAMPLE_IS_NON_SYNC_SAMPLE = 0x00010000;
const TWO_TO_63 = 1n << 63n;
const TWO_TO_64 = 1n << 64n;

export class Mp4FormatError extends Error {}

/** Reads a box's big-endian fields in order; reading past the box's end is a format error. */
class Fields {
  constructor(data, [start, end], owner) {
    this.data = data;
    this.position = start;
    this.end = end;
    this.owner = owner;
  }

  take(bytes) {
    if (this.end - this.position < bytes) {
      throw new Mp4FormatError(`${this.owner} ends before its fields do`);
    }
    const at = this.position;
    this.position += bytes;
    return at;
  }

  skip(bytes) {
    this.take(bytes);
    return this;
  }

  u8() {
    return this.data.readUInt8(this.take(1));
  }

  u24() {
    return this.data.readUIntBE(this.take(3), 3);
  }

  u32() {
    return this.data.readUInt32BE(this.take(4));
  }

  s32() {
    return this.data.readInt32BE(this.take(4));
  }

  u64() {
    return this.data.readBigUInt64BE(this.take(8));
  }

  s64() {
    return this.data.readBigInt64BE(this.take(8));
  }

  fourcc() {
    const at = this.take(4);
    return this.data.toString('latin1', at, at + 4);
  }
}

/**
 * Yields [type, start, bodyStart, end] for each box between start and end. Every box's header and
 * declared size fit inside that range, and no size is smaller than its header.
 */
export function* boxes(data, start, end) {
  let position = start;
  while (position < end) {
    if (end - position < 8) {
      throw new Mp4FormatError(`truncated box header at ${position}`);
    }
    let size = data.readUInt32BE(position);
    const type = data.toString('latin1', position + 4, position + 8);
    let header = 8;
    if (size === 1) {
      if (end - position < 16) {
        throw new Mp4FormatError(`truncated 64-bit box header of '${type}' at ${position}`);
      }
      const large = data.readBigUInt64BE(position + 8);
      size = large > BigInt(Number.MAX_SAFE_INTEGER) ? Infinity : Number(large);
      header = 16;
    }
    if (size === 0) {
      throw new Mp4FormatError(`size-0 (to end of file) box '${type}' at ${position}`);
    }
    if (size < header) {
      throw new Mp4FormatError(`box '${type}' at ${position} declares ${size} bytes, less than its ${header}-byte header`);
    }
    if (position + size > end) {
      throw new Mp4FormatError(`box '${type}' at ${position} overruns its parent`);
    }
    yield [type, position, position + header, position + size];
    position += size;
  }
}

function child(data, start, end, type) {
  for (const [found, , body, stop] of boxes(data, start, end)) {
    if (found === type) {
      return [body, stop];
    }
  }
  return null;
}

function required(box, type) {
  if (box === null) {
    throw new Mp4FormatError(`no ${type} box`);
  }
  return box;
}

/** A full box's version and flags, and the fields that follow them. */
function fullBox(data, box, owner) {
  const fields = new Fields(data, box, owner);
  return { version: fields.u8(), flags: fields.u24(), fields };
}

function headerField(data, box, owner) {
  const { version, fields } = fullBox(data, box, owner);
  return fields.skip(version === 1 ? 16 : 8).u32();
}

function editList(data, trakBody, trakStop) {
  const edts = child(data, trakBody, trakStop, 'edts');
  if (edts === null) {
    return [];
  }
  const { version, fields } = fullBox(data, required(child(data, edts[0], edts[1], 'elst'), 'elst'), 'elst');
  const count = fields.u32();
  const edits = [];
  for (let entry = 0; entry < count; entry++) {
    edits.push(editOf(fields, version));
    fields.skip(4);
  }
  return edits;
}

/** One elst entry's segment duration and media time. */
function editOf(fields, version) {
  if (version === 1) {
    return [fields.u64(), fields.s64()];
  }
  return [BigInt(fields.u32()), BigInt(fields.s32())];
}

const NAL_LENGTH_AT = { avc1: ['avcC', 4], hvc1: ['hvcC', 21], hev1: ['hvcC', 21] };
const VISUAL_SAMPLE_ENTRY_BYTES = 78;

/**
 * The type of the track's first sample entry, and for H.264 and HEVC the byte length of the NAL
 * unit lengths its decoder configuration declares.
 */
function sampleEntryOf(data, mdia) {
  const minf = child(data, mdia[0], mdia[1], 'minf');
  const stbl = minf === null ? null : child(data, minf[0], minf[1], 'stbl');
  const stsd = stbl === null ? null : child(data, stbl[0], stbl[1], 'stsd');
  if (stsd === null) {
    return { sampleEntry: null, nalLengthSize: null };
  }
  const { fields } = fullBox(data, stsd, 'stsd');
  fields.skip(4);
  const [entry] = boxes(data, fields.position, stsd[1]);
  const [type, , entryBody, entryEnd] = required(entry ?? null, 'sample entry');
  if (!(type in NAL_LENGTH_AT)) {
    return { sampleEntry: type, nalLengthSize: null };
  }
  const [configurationType, lengthAt] = NAL_LENGTH_AT[type];
  const configuration = required(child(data, entryBody + VISUAL_SAMPLE_ENTRY_BYTES, entryEnd, configurationType), configurationType);
  const lengthSizeMinusOne = new Fields(data, configuration, configurationType).skip(lengthAt).u8() & 0x3;
  return { sampleEntry: type, nalLengthSize: lengthSizeMinusOne + 1 };
}

/** The moov's tracks by track id, in trak order. */
export function parseMoov(data, body, stop) {
  const tracks = new Map();
  for (const [type, , trakBody, trakStop] of boxes(data, body, stop)) {
    if (type !== 'trak') {
      continue;
    }
    const trackId = headerField(data, required(child(data, trakBody, trakStop, 'tkhd'), 'tkhd'), 'tkhd');
    const mdia = required(child(data, trakBody, trakStop, 'mdia'), 'mdia');
    const timescale = headerField(data, required(child(data, mdia[0], mdia[1], 'mdhd'), 'mdhd'), 'mdhd');
    const hdlr = new Fields(data, required(child(data, mdia[0], mdia[1], 'hdlr'), 'hdlr'), 'hdlr');
    const handler = hdlr.skip(8).fourcc();
    tracks.set(trackId, {
      trackId,
      handler,
      timescale,
      edits: editList(data, trakBody, trakStop),
      ...sampleEntryOf(data, mdia),
    });
  }
  const mvex = child(data, body, stop, 'mvex');
  if (mvex !== null) {
    for (const [type, , trexBody, trexStop] of boxes(data, mvex[0], mvex[1])) {
      if (type !== 'trex') {
        continue;
      }
      const { fields } = fullBox(data, [trexBody, trexStop], 'trex');
      const track = tracks.get(fields.u32());
      fields.skip(4);
      const defaults = { trexDuration: fields.u32(), trexSize: fields.u32(), trexFlags: fields.u32() };
      if (track !== undefined) {
        Object.assign(track, defaults);
      }
    }
  }
  return tracks;
}

function trackFragmentHeader(data, body, stop, tracks) {
  const { flags, fields } = fullBox(data, required(child(data, body, stop, 'tfhd'), 'tfhd'), 'tfhd');
  const trackId = fields.u32();
  const track = tracks.get(trackId);
  if (track === undefined) {
    throw new Mp4FormatError(`traf for undeclared track ${trackId}`);
  }
  const defaults = {
    duration: track.trexDuration ?? 0,
    size: track.trexSize ?? 0,
    flags: track.trexFlags ?? 0,
  };
  const baseDataOffset = flags & TFHD_BASE_DATA_OFFSET ? Number(fields.u64()) : null;
  const defaultBaseIsMoof = (flags & TFHD_DEFAULT_BASE_IS_MOOF) !== 0;
  fields.skip(flags & TFHD_SAMPLE_DESCRIPTION_INDEX ? 4 : 0);
  if (flags & TFHD_DEFAULT_SAMPLE_DURATION) {
    defaults.duration = fields.u32();
  }
  if (flags & TFHD_DEFAULT_SAMPLE_SIZE) {
    defaults.size = fields.u32();
  }
  if (flags & TFHD_DEFAULT_SAMPLE_FLAGS) {
    defaults.flags = fields.u32();
  }
  return { track, defaults, baseDataOffset, defaultBaseIsMoof };
}

function baseMediaDecodeTime(data, body, stop) {
  const { version, fields } = fullBox(data, required(child(data, body, stop, 'tfdt'), 'tfdt'), 'tfdt');
  return version === 1 ? fields.u64() : BigInt(fields.u32());
}

function trackRun(data, trun, defaults) {
  const { version, flags, fields } = fullBox(data, trun, 'trun');
  const count = fields.u32();
  const dataOffset = flags & TRUN_DATA_OFFSET ? fields.s32() : null;
  const firstFlags = flags & TRUN_FIRST_SAMPLE_FLAGS ? fields.u32() : null;
  const samples = [];
  for (let index = 0; index < count; index++) {
    const sample = { duration: defaults.duration, flags: defaults.flags, compositionOffset: 0, size: defaults.size };
    if (flags & TRUN_SAMPLE_DURATION) {
      sample.duration = fields.u32();
    }
    if (flags & TRUN_SAMPLE_SIZE) {
      sample.size = fields.u32();
    }
    if (flags & TRUN_SAMPLE_FLAGS) {
      sample.flags = fields.u32();
    }
    if (flags & TRUN_SAMPLE_COMPOSITION_TIME_OFFSET) {
      sample.compositionOffset = version === 1 ? fields.s32() : fields.u32();
    }
    if (index === 0 && firstFlags !== null) {
      sample.flags = firstFlags;
    }
    samples.push(sample);
  }
  return { dataOffset, samples };
}

export function isSync(flags) {
  return (flags & SAMPLE_IS_NON_SYNC_SAMPLE) === 0;
}

/**
 * Where each run's sample data starts (ISO/IEC 14496-12 8.8.7 and 8.8.8): the traf's base is the
 * tfhd base-data-offset, else the moof with default-base-is-moof, else the moof for the first traf
 * and the end of the previous traf's data after it; a run starts at base + its data offset, else
 * where the previous run of the traf ended, else at the base. Positions are offsets into the stream.
 */
function placeRuns({ header, runs, layout }) {
  let base = layout.previousTrafEnd;
  if (header.defaultBaseIsMoof) {
    base = layout.moofStart;
  }
  if (header.baseDataOffset !== null) {
    base = header.baseDataOffset;
  }
  let end = base;
  const ranges = runs.map((run) => {
    const start = run.dataOffset === null ? end : base + run.dataOffset;
    let position = start;
    for (const sample of run.samples) {
      sample.offset = position;
      position += sample.size;
    }
    end = position;
    return [start, end];
  });
  layout.previousTrafEnd = end;
  return ranges;
}

function parseTraf(data, [body, stop], { tracks, layout }) {
  const header = trackFragmentHeader(data, body, stop, tracks);
  const base = baseMediaDecodeTime(data, body, stop);
  const runs = [];
  for (const [type, , trunBody, trunStop] of boxes(data, body, stop)) {
    if (type === 'trun') {
      runs.push(trackRun(data, [trunBody, trunStop], header.defaults));
    }
  }
  const dataRanges = placeRuns({ header, runs, layout });
  const samples = runs.flatMap((run) => run.samples);
  const first = samples[0] ?? null;
  const track = header.track;
  return {
    trackId: track.trackId,
    handler: track.handler,
    timescale: track.timescale,
    baseMediaDecodeTime: base,
    sampleCount: samples.length,
    firstPresentationTime: first === null ? null : base + BigInt(first.compositionOffset),
    firstSync: first === null ? null : isSync(first.flags),
    durationSum: samples.reduce((sum, sample) => sum + BigInt(sample.duration), 0n),
    syncSamples: samples.filter((sample) => isSync(sample.flags)).length,
    samples,
    dataRanges,
  };
}

/** Every run that carries bytes must lie inside the body of the mdat that follows its moof. */
function requireDataInside(fragment, [bodyStart, end]) {
  for (const traf of fragment.trafs) {
    for (const [start, stop] of traf.dataRanges) {
      if (stop > start && (start < bodyStart || stop > end)) {
        throw new Mp4FormatError(
          `track ${traf.trackId} sample data at ${start}..${stop} lies outside the mdat body at ${bodyStart}..${end}`,
        );
      }
    }
  }
}

/** Reads one stream: the initialization segment's tracks, then every moof + mdat fragment. */
export function readStream(data) {
  const state = { data, initializationSegmentEnd: null, tracks: null, fragments: [], pendingMoof: null };
  for (const box of boxes(data, 0, data.length)) {
    readTopLevelBox(state, box);
  }
  if (state.pendingMoof !== null) {
    throw new Mp4FormatError('stream ends after a moof with no mdat');
  }
  const end = state.initializationSegmentEnd;
  return {
    initializationSegmentByteLength: end,
    initializationSegmentBytes: end === null ? data : data.subarray(0, end),
    tracks: state.tracks,
    fragments: state.fragments,
    data,
  };
}

function readTopLevelBox(state, [type, start, body, stop]) {
  switch (type) {
    case 'moov':
      state.tracks = parseMoov(state.data, body, stop);
      state.initializationSegmentEnd = stop;
      return;
    case 'moof':
      state.pendingMoof = readMoof(state, [start, body, stop]);
      return;
    case 'mdat':
      state.fragments.push(closeFragment(state.pendingMoof, [start, body, stop]));
      state.pendingMoof = null;
      return;
    default:
      return;
  }
}

function readMoof({ data, tracks, pendingMoof }, [start, body, stop]) {
  if (tracks === null) {
    throw new Mp4FormatError('moof before moov');
  }
  if (pendingMoof !== null) {
    throw new Mp4FormatError(`moof at ${start} follows a moof with no mdat`);
  }
  const trafs = [];
  const layout = { moofStart: start, previousTrafEnd: start };
  for (const [child, , trafBody, trafStop] of boxes(data, body, stop)) {
    if (child === 'traf') {
      trafs.push(parseTraf(data, [trafBody, trafStop], { tracks, layout }));
    }
  }
  return { offset: start, trafs };
}

/** The fragment a moof and the mdat after it form, once every sample lies inside that mdat. */
function closeFragment(moof, [start, body, stop]) {
  if (moof === null) {
    throw new Mp4FormatError(`mdat at ${start} without moof`);
  }
  requireDataInside(moof, [body, stop]);
  return { ...moof, byteLength: stop - moof.offset };
}

/** Reads several files as one concatenated stream. */
export function readFiles(paths) {
  return readStream(Buffer.concat(paths.map((path) => readFileSync(path))));
}

/** Every video sample in decode order: presentation time, sync, size, stream offset and fragment index. */
export function videoSamples(stream) {
  const result = [];
  stream.fragments.forEach((fragment, index) => {
    for (const traf of fragment.trafs) {
      if (traf.handler !== VIDEO_HANDLER) {
        continue;
      }
      let decode = traf.baseMediaDecodeTime;
      for (const sample of traf.samples) {
        result.push({
          presentationTime: decode + BigInt(sample.compositionOffset),
          sync: isSync(sample.flags),
          size: sample.size,
          offset: sample.offset,
          fragmentIndex: index,
        });
        decode += BigInt(sample.duration);
      }
    }
  });
  return result;
}

/** A 64-bit decode time read as the signed value FFmpeg wrote, such as the -1024 AAC priming. */
export function signed(value) {
  return value >= TWO_TO_63 ? value - TWO_TO_64 : value;
}

/** The fragment's video traf when it carries video samples, else null. */
export function videoTrafOf(fragment) {
  return fragment.trafs.find((traf) => traf.handler === VIDEO_HANDLER && traf.sampleCount > 0) ?? null;
}

/** The first audio traf among the fragments, else null. */
export function firstAudioTraf(fragments) {
  return fragments.flatMap((fragment) => fragment.trafs).find((traf) => traf.handler === AUDIO_HANDLER) ?? null;
}

/** The stream's video track. */
export function videoTrackOf(stream) {
  return [...stream.tracks.values()].find((track) => track.handler === VIDEO_HANDLER);
}

/** The stream's audio track, else null. */
export function audioTrackOf(stream) {
  return [...stream.tracks.values()].find((track) => track.handler === AUDIO_HANDLER) ?? null;
}

/** The dump the command line prints: every box fact except the bytes and the sample tables. */
export function describeStream(stream) {
  return {
    initializationSegmentByteLength: stream.initializationSegmentByteLength,
    tracks: Object.fromEntries(
      [...stream.tracks].map(([trackId, track]) => [
        String(trackId),
        { ...track, edits: track.edits.map((edit) => [...edit]) },
      ]),
    ),
    fragments: stream.fragments.map((fragment) => ({
      offset: fragment.offset,
      trafs: fragment.trafs.map(({ samples, dataRanges, ...traf }) => traf),
      byteLength: fragment.byteLength,
    })),
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.stdout.write(`${formatJson(describeStream(readFiles(process.argv.slice(2))), 1)}\n`);
}
