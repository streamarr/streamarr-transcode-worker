// Writes ISOBMFF boxes for the recorder's tests.

export const SYNC_SAMPLE_FLAGS = 0x02000000;
export const NON_SYNC_SAMPLE_FLAGS = 0x01010000;

export const TFHD_BASE_DATA_OFFSET = 0x000001;
export const TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;
export const TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;
export const TRUN_DATA_OFFSET = 0x000001;
export const TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
export const TRUN_SAMPLE_DURATION = 0x000100;
export const TRUN_SAMPLE_SIZE = 0x000200;
export const TRUN_SAMPLE_FLAGS = 0x000400;
export const TRUN_SAMPLE_COMPOSITION_TIME_OFFSET = 0x000800;
const COMPACT_HEADER_BYTES = 8;

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

/** A visual sample entry of the given type whose decoder configuration declares 4-byte NAL lengths. */
export function visualSampleEntry(type) {
  const configuration =
    type === 'avc1'
      ? box('avcC', Buffer.from([1, 100, 0, 30, 0xff, 0xe0, 0]))
      : box('hvcC', Buffer.alloc(21), Buffer.from([0x0f, 0]));
  return box(type, Buffer.alloc(78), configuration);
}

/** A trak with a v0 tkhd and mdhd, an optional elst entry and sample entry, and its trex defaults. */
export function track({
  trackId,
  handler,
  timescale,
  defaultFlags = 0,
  defaultSize = 0,
  edit = null,
  editVersion = 0,
  sampleEntry = null,
}) {
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
      ...(sampleEntry === null ? [] : [box('minf', box('stbl', fullBox('stsd', 0, 0, u32(1), sampleEntry)))]),
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

export const VIDEO = { trackId: 1, handler: 'vide', timescale: 24000, defaultFlags: NON_SYNC_SAMPLE_FLAGS };
export const AUDIO = { trackId: 2, handler: 'soun', timescale: 48000, defaultFlags: SYNC_SAMPLE_FLAGS };

/**
 * A trun of samples [{ duration, size, flags, compositionOffset }]; each field is written per sample
 * only when the first sample has it. dataOffset and firstSampleFlags are written when not null.
 */
export function trun({ version = 1, samples, dataOffset = null, firstSampleFlags = null }) {
  const first = samples[0] ?? {};
  const fields = ['duration', 'size', 'flags', 'compositionOffset'];
  const bits = [TRUN_SAMPLE_DURATION, TRUN_SAMPLE_SIZE, TRUN_SAMPLE_FLAGS, TRUN_SAMPLE_COMPOSITION_TIME_OFFSET];
  let flags = (dataOffset === null ? 0 : TRUN_DATA_OFFSET) | (firstSampleFlags === null ? 0 : TRUN_FIRST_SAMPLE_FLAGS);
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
      : fullBox('tfhd', 0, TFHD_DEFAULT_BASE_IS_MOOF | TFHD_DEFAULT_SAMPLE_FLAGS, u32(trackId), u32(defaultFlags));
  const tfdt = tfdtVersion === 1 ? fullBox('tfdt', 1, 0, u64(decodeTime)) : fullBox('tfdt', 0, 0, u32(decodeTime));
  return box('traf', tfhd, tfdt, ...runs);
}

export function moof(...trafs) {
  return box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafs);
}

export function mdat(bytes) {
  return box('mdat', Buffer.from(bytes));
}

/**
 * The moof a builder writes for the position where the body of the mdat after it starts, when that
 * mdat has a compact header: pass the moof's stream position for a tfhd base-data-offset, or 0 for
 * data offsets that count from the moof.
 */
export function moofPointingAtItsMdat(moofPosition, moofWithDataAt) {
  return moofWithDataAt(moofPosition + moofWithDataAt(0).length + COMPACT_HEADER_BYTES);
}

function runBytes(run) {
  return run.samples.reduce((sum, sample) => sum + (sample.size ?? 0), 0);
}

/**
 * A moof of trafs ({ trackId, decodeTime, defaultFlags, tfdtVersion, runs: [trun options] }) and
 * the mdat that holds their samples, each run's data offset pointing at its own bytes. A run's
 * payload option gives its sample bytes; otherwise they are filler.
 */
export function fragment(...trafs) {
  const build = (dataStart) => {
    let position = dataStart;
    const offsets = trafs.map((spec) =>
      spec.runs.map((run) => {
        const at = position;
        position += runBytes(run);
        return at;
      }),
    );
    return moof(
      ...trafs.map((spec, index) =>
        traf({ ...spec, runs: spec.runs.map((run, position) => trun({ ...run, dataOffset: offsets[index][position] })) }),
      ),
    );
  };
  const payload = trafs.flatMap((spec) =>
    spec.runs.flatMap((run) => run.payload ?? Array.from({ length: runBytes(run) }, (_, index) => index & 0xff)),
  );
  return Buffer.concat([moofPointingAtItsMdat(0, build), mdat(payload)]);
}

/** A video fragment of one sample whose sync status is its first-sample flags. */
export function videoFragment({ decodeTime, sync, compositionOffset = 0, size = 1 }) {
  return fragment({
    trackId: VIDEO.trackId,
    decodeTime,
    runs: [
      {
        samples: [{ duration: 1001, size, compositionOffset }],
        firstSampleFlags: sync ? SYNC_SAMPLE_FLAGS : NON_SYNC_SAMPLE_FLAGS,
      },
    ],
  });
}

export function audioFragment({ decodeTime, size = 1 }) {
  return fragment({ trackId: AUDIO.trackId, decodeTime, runs: [{ samples: [{ duration: 1024, size }] }] });
}

/** A length-prefixed access unit of NAL units given as their header bytes. */
export function accessUnit(...nalUnits) {
  return [...Buffer.concat(nalUnits.map((header) => Buffer.concat([u32(header.length), Buffer.from(header)])))];
}

export function initialization() {
  return Buffer.concat([ftyp(), moov(track(VIDEO), track(AUDIO))]);
}
