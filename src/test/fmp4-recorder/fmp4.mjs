#!/usr/bin/env node
// Independent reader for FFmpeg's fragmented MP4 output (no Java, no FFmpeg).
//
// Reads top-level ISOBMFF boxes: ftyp + moov form the initialization segment; each moof with the
// mdat that follows it forms one fragment. For every fragment it reports, per track, the first
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

export const NON_SYNC = 0x00010000;
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
    if (version === 1) {
      edits.push([fields.u64(), fields.s64()]);
    } else {
      edits.push([BigInt(fields.u32()), BigInt(fields.s32())]);
    }
    fields.skip(4);
  }
  return edits;
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
    tracks.set(trackId, { trackId, handler, timescale, edits: editList(data, trakBody, trakStop) });
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
  fields.skip((flags & 0x1 ? 8 : 0) + (flags & 0x2 ? 4 : 0));
  if (flags & 0x8) {
    defaults.duration = fields.u32();
  }
  if (flags & 0x10) {
    defaults.size = fields.u32();
  }
  if (flags & 0x20) {
    defaults.flags = fields.u32();
  }
  return { track, defaults };
}

function baseMediaDecodeTime(data, body, stop) {
  const { version, fields } = fullBox(data, required(child(data, body, stop, 'tfdt'), 'tfdt'), 'tfdt');
  return version === 1 ? fields.u64() : BigInt(fields.u32());
}

function trackRunSamples(data, trun, defaults) {
  const { version, flags, fields } = fullBox(data, trun, 'trun');
  const count = fields.u32();
  fields.skip(flags & 0x1 ? 4 : 0);
  const firstFlags = flags & 0x4 ? fields.u32() : null;
  const samples = [];
  for (let index = 0; index < count; index++) {
    const sample = { duration: defaults.duration, flags: defaults.flags, compositionOffset: 0, size: defaults.size };
    if (flags & 0x100) {
      sample.duration = fields.u32();
    }
    if (flags & 0x200) {
      sample.size = fields.u32();
    }
    if (flags & 0x400) {
      sample.flags = fields.u32();
    }
    if (flags & 0x800) {
      sample.compositionOffset = version === 1 ? fields.s32() : fields.u32();
    }
    if (index === 0 && firstFlags !== null) {
      sample.flags = firstFlags;
    }
    samples.push(sample);
  }
  return samples;
}

export function isSync(flags) {
  return (flags & NON_SYNC) === 0;
}

function parseTraf(data, body, stop, tracks) {
  const { track, defaults } = trackFragmentHeader(data, body, stop, tracks);
  const base = baseMediaDecodeTime(data, body, stop);
  const samples = [];
  for (const [type, , trunBody, trunStop] of boxes(data, body, stop)) {
    if (type === 'trun') {
      samples.push(...trackRunSamples(data, [trunBody, trunStop], defaults));
    }
  }
  const first = samples[0] ?? null;
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
  };
}

/** Reads one stream: the initialization segment's tracks, then every moof + mdat fragment. */
export function readStream(data) {
  let initEnd = null;
  let tracks = null;
  const fragments = [];
  let pendingMoof = null;
  for (const [type, start, body, stop] of boxes(data, 0, data.length)) {
    if (type === 'moov') {
      tracks = parseMoov(data, body, stop);
      initEnd = stop;
    } else if (type === 'moof') {
      if (tracks === null) {
        throw new Mp4FormatError('moof before moov');
      }
      if (pendingMoof !== null) {
        throw new Mp4FormatError(`moof at ${start} follows a moof with no mdat`);
      }
      const trafs = [];
      for (const [child, , trafBody, trafStop] of boxes(data, body, stop)) {
        if (child === 'traf') {
          trafs.push(parseTraf(data, trafBody, trafStop, tracks));
        }
      }
      pendingMoof = { offset: start, trafs };
    } else if (type === 'mdat') {
      if (pendingMoof === null) {
        throw new Mp4FormatError(`mdat at ${start} without moof`);
      }
      pendingMoof.byteLength = stop - pendingMoof.offset;
      fragments.push(pendingMoof);
      pendingMoof = null;
    }
  }
  if (pendingMoof !== null) {
    throw new Mp4FormatError('stream ends after a moof with no mdat');
  }
  return {
    initByteLength: initEnd,
    initBytes: initEnd === null ? data : data.subarray(0, initEnd),
    tracks,
    fragments,
  };
}

/** Reads several files as one concatenated stream. */
export function readFiles(paths) {
  return readStream(Buffer.concat(paths.map((path) => readFileSync(path))));
}

/** Every video sample in decode order: presentation time, sync, size and fragment index. */
export function videoSamples(stream) {
  const result = [];
  stream.fragments.forEach((fragment, index) => {
    for (const traf of fragment.trafs) {
      if (traf.handler !== 'vide') {
        continue;
      }
      let decode = traf.baseMediaDecodeTime;
      for (const sample of traf.samples) {
        result.push({
          presentationTime: decode + BigInt(sample.compositionOffset),
          sync: isSync(sample.flags),
          size: sample.size,
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
export function videoStart(fragment) {
  return fragment.trafs.find((traf) => traf.handler === 'vide' && traf.sampleCount > 0) ?? null;
}

/** The dump the command line prints: every box fact except the bytes and the sample tables. */
export function describeStream(stream) {
  return {
    initByteLength: stream.initByteLength,
    tracks: Object.fromEntries(
      [...stream.tracks].map(([trackId, track]) => [
        String(trackId),
        { ...track, edits: track.edits.map((edit) => [...edit]) },
      ]),
    ),
    fragments: stream.fragments.map((fragment) => ({
      offset: fragment.offset,
      trafs: fragment.trafs.map(({ samples, ...traf }) => traf),
      byteLength: fragment.byteLength,
    })),
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.stdout.write(`${formatJson(describeStream(readFiles(process.argv.slice(2))), 1)}\n`);
}
