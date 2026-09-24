// Writes ISOBMFF boxes for the recorder's tests.

export const SYNC = 0x02000000;
export const NON_SYNC = 0x01010000;
export const TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

export function u32(value) {
  const bytes = Buffer.alloc(4);
  bytes.writeUInt32BE(value >>> 0);
  return bytes;
}

export function u64(value) {
  const bytes = Buffer.alloc(8);
  bytes.writeBigUInt64BE(BigInt.asUintN(64, BigInt(value)));
  return bytes;
}

export function box(type, ...parts) {
  const body = Buffer.concat(parts);
  return Buffer.concat([u32(8 + body.length), Buffer.from(type, 'latin1'), body]);
}

export function largeBox(type, ...parts) {
  const body = Buffer.concat(parts);
  return Buffer.concat([u32(1), Buffer.from(type, 'latin1'), u64(16 + body.length), body]);
}

export function fullBox(type, version, flags, ...parts) {
  return box(type, u32(((version << 24) | flags) >>> 0), ...parts);
}

export function ftyp() {
  return box('ftyp', Buffer.from('iso6', 'latin1'), u32(512), Buffer.from('iso6cmfc', 'latin1'));
}

/** A trak with a v0 tkhd and mdhd, an optional elst entry, and its trex defaults. */
export function track({ trackId, handler, timescale, defaultFlags = 0, defaultSize = 0, edit = null, editVersion = 0 }) {
  const entry = editVersion === 1 ? [u64(edit?.[0] ?? 0), u64(edit?.[1] ?? 0)] : [u32(edit?.[0] ?? 0), u32(edit?.[1] ?? 0)];
  const edts = edit === null ? [] : [box('edts', fullBox('elst', editVersion, 0, u32(1), ...entry, u32(1 << 16)))];
  const trak = box(
    'trak',
    fullBox('tkhd', 0, 3, u32(0), u32(0), u32(trackId), Buffer.alloc(64)),
    ...edts,
    box(
      'mdia',
      fullBox('mdhd', 0, 0, u32(0), u32(0), u32(timescale), u32(0), u32(0)),
      fullBox('hdlr', 0, 0, u32(0), Buffer.from(handler, 'latin1'), Buffer.alloc(12), Buffer.from('name\0', 'latin1')),
    ),
  );
  const trex = fullBox('trex', 0, 0, u32(trackId), u32(1), u32(0), u32(defaultSize), u32(defaultFlags));
  return { trak, trex };
}

export function moov(...tracks) {
  return box(
    'moov',
    fullBox('mvhd', 0, 0, Buffer.alloc(96)),
    ...tracks.map((entry) => entry.trak),
    box('mvex', ...tracks.map((entry) => entry.trex)),
  );
}

export const VIDEO = { trackId: 1, handler: 'vide', timescale: 24000, defaultFlags: NON_SYNC };
export const AUDIO = { trackId: 2, handler: 'soun', timescale: 48000, defaultFlags: SYNC };

/**
 * A trun of samples [{ duration, size, flags, compositionOffset }]; each field is written per sample
 * only when the first sample has it. dataOffset and firstSampleFlags are written when not null.
 */
export function trun({ version = 1, samples, dataOffset = null, firstSampleFlags = null }) {
  const first = samples[0] ?? {};
  const fields = ['duration', 'size', 'flags', 'compositionOffset'];
  const bits = [0x100, 0x200, 0x400, 0x800];
  let flags = (dataOffset === null ? 0 : 0x1) | (firstSampleFlags === null ? 0 : 0x4);
  fields.forEach((field, index) => {
    if (first[field] !== undefined) {
      flags |= bits[index];
    }
  });
  const entries = samples.flatMap((sample) =>
    fields.filter((field) => first[field] !== undefined).map((field) => u32(sample[field])),
  );
  const header = [u32(samples.length)];
  if (dataOffset !== null) {
    header.push(u32(dataOffset));
  }
  if (firstSampleFlags !== null) {
    header.push(u32(firstSampleFlags));
  }
  return fullBox('trun', version, flags, ...header, ...entries);
}

/** A traf whose tfhd carries only the track id, or default flags when given, and a v1 tfdt. */
export function traf({ trackId, decodeTime, runs, defaultFlags = null, tfdtVersion = 1 }) {
  const tfhd =
    defaultFlags === null
      ? fullBox('tfhd', 0, TFHD_DEFAULT_BASE_IS_MOOF, u32(trackId))
      : fullBox('tfhd', 0, TFHD_DEFAULT_BASE_IS_MOOF | 0x20, u32(trackId), u32(defaultFlags));
  const tfdt = tfdtVersion === 1 ? fullBox('tfdt', 1, 0, u64(decodeTime)) : fullBox('tfdt', 0, 0, u32(decodeTime));
  return box('traf', tfhd, tfdt, ...runs);
}

export function moof(...trafs) {
  return box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafs);
}

export function mdat(bytes) {
  return box('mdat', Buffer.from(bytes));
}

/** A video fragment of one sample whose sync status is its first-sample flags. */
export function videoFragment({ decodeTime, sync, compositionOffset = 0, payload = [1] }) {
  return Buffer.concat([
    moof(
      traf({
        trackId: VIDEO.trackId,
        decodeTime,
        runs: [trun({ samples: [{ duration: 1001, size: payload.length, compositionOffset }], firstSampleFlags: sync ? SYNC : NON_SYNC })],
      }),
    ),
    mdat(payload),
  ]);
}

export function audioFragment({ decodeTime, payload = [2] }) {
  return Buffer.concat([
    moof(traf({ trackId: AUDIO.trackId, decodeTime, runs: [trun({ samples: [{ duration: 1024, size: payload.length }] })] })),
    mdat(payload),
  ]);
}

export function initialization() {
  return Buffer.concat([ftyp(), moov(track(VIDEO), track(AUDIO))]);
}
