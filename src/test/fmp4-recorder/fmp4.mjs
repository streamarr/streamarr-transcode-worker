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

/** Yields [type, start, bodyStart, end] for each box between start and end. */
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
      const large = data.readBigUInt64BE(position + 8);
      size = large > BigInt(Number.MAX_SAFE_INTEGER) ? Infinity : Number(large);
      header = 16;
    }
    if (size === 0) {
      throw new Mp4FormatError(`size-0 (to end of file) box '${type}' at ${position}`);
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

function fullBox(data, body) {
  return [data.readUInt8(body), data.readUIntBE(body + 1, 3), body + 4];
}

function headerField(data, box) {
  const [version, , position] = fullBox(data, box[0]);
  return data.readUInt32BE(position + (version === 1 ? 16 : 8));
}

function editList(data, trakBody, trakStop) {
  const edts = child(data, trakBody, trakStop, 'edts');
  if (edts === null) {
    return [];
  }
  const elst = required(child(data, edts[0], edts[1], 'elst'), 'elst');
  let [version, , position] = fullBox(data, elst[0]);
  const count = data.readUInt32BE(position);
  position += 4;
  const edits = [];
  for (let entry = 0; entry < count; entry++) {
    if (version === 1) {
      edits.push([data.readBigUInt64BE(position), data.readBigInt64BE(position + 8)]);
      position += 16;
    } else {
      edits.push([BigInt(data.readUInt32BE(position)), BigInt(data.readInt32BE(position + 4))]);
      position += 8;
    }
    position += 4;
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
    const trackId = headerField(data, required(child(data, trakBody, trakStop, 'tkhd'), 'tkhd'));
    const mdia = required(child(data, trakBody, trakStop, 'mdia'), 'mdia');
    const timescale = headerField(data, required(child(data, mdia[0], mdia[1], 'mdhd'), 'mdhd'));
    const hdlr = required(child(data, mdia[0], mdia[1], 'hdlr'), 'hdlr');
    const handler = data.toString('latin1', hdlr[0] + 8, hdlr[0] + 12);
    tracks.set(trackId, { trackId, handler, timescale, edits: editList(data, trakBody, trakStop) });
  }
  const mvex = child(data, body, stop, 'mvex');
  if (mvex !== null) {
    for (const [type, , trexBody] of boxes(data, mvex[0], mvex[1])) {
      if (type !== 'trex') {
        continue;
      }
      const [, , position] = fullBox(data, trexBody);
      const track = tracks.get(data.readUInt32BE(position));
      if (track !== undefined) {
        track.trexDuration = data.readUInt32BE(position + 8);
        track.trexSize = data.readUInt32BE(position + 12);
        track.trexFlags = data.readUInt32BE(position + 16);
      }
    }
  }
  return tracks;
}

function trackFragmentHeader(data, body, stop, tracks) {
  const [, flags, start] = fullBox(data, required(child(data, body, stop, 'tfhd'), 'tfhd')[0]);
  let position = start;
  const trackId = data.readUInt32BE(position);
  position += 4;
  const track = tracks.get(trackId);
  if (track === undefined) {
    throw new Mp4FormatError(`traf for undeclared track ${trackId}`);
  }
  const defaults = {
    duration: track.trexDuration ?? 0,
    size: track.trexSize ?? 0,
    flags: track.trexFlags ?? 0,
  };
  position += (flags & 0x1 ? 8 : 0) + (flags & 0x2 ? 4 : 0);
  if (flags & 0x8) {
    defaults.duration = data.readUInt32BE(position);
    position += 4;
  }
  if (flags & 0x10) {
    defaults.size = data.readUInt32BE(position);
    position += 4;
  }
  if (flags & 0x20) {
    defaults.flags = data.readUInt32BE(position);
  }
  return { track, defaults };
}

function baseMediaDecodeTime(data, body, stop) {
  const [version, , position] = fullBox(data, required(child(data, body, stop, 'tfdt'), 'tfdt')[0]);
  return version === 1 ? data.readBigUInt64BE(position) : BigInt(data.readUInt32BE(position));
}

function trackRunSamples(data, trunBody, defaults) {
  const [version, flags, start] = fullBox(data, trunBody);
  let position = start;
  const count = data.readUInt32BE(position);
  position += 4 + (flags & 0x1 ? 4 : 0);
  let firstFlags = null;
  if (flags & 0x4) {
    firstFlags = data.readUInt32BE(position);
    position += 4;
  }
  const samples = [];
  for (let index = 0; index < count; index++) {
    const sample = { duration: defaults.duration, flags: defaults.flags, compositionOffset: 0, size: defaults.size };
    if (flags & 0x100) {
      sample.duration = data.readUInt32BE(position);
      position += 4;
    }
    if (flags & 0x200) {
      sample.size = data.readUInt32BE(position);
      position += 4;
    }
    if (flags & 0x400) {
      sample.flags = data.readUInt32BE(position);
      position += 4;
    }
    if (flags & 0x800) {
      sample.compositionOffset = version === 1 ? data.readInt32BE(position) : data.readUInt32BE(position);
      position += 4;
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
  for (const [type, , trunBody] of boxes(data, body, stop)) {
    if (type === 'trun') {
      samples.push(...trackRunSamples(data, trunBody, defaults));
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
