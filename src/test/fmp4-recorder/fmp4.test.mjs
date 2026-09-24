import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { Mp4FormatError, readFiles, readStream, signed, videoSamples, videoTrafOf } from './fmp4.mjs';
import {
  AUDIO,
  NON_SYNC_SAMPLE_FLAGS,
  SYNC_SAMPLE_FLAGS,
  TFHD_BASE_DATA_OFFSET,
  TFHD_DEFAULT_BASE_IS_MOOF,
  TFHD_DEFAULT_SAMPLE_FLAGS,
  TRUN_SAMPLE_SIZE,
  VIDEO,
  audioFragment,
  box,
  fragment,
  ftyp,
  fullBox,
  initialization,
  largeBox,
  mdat,
  moof,
  moofPointingAtItsMdat,
  moov,
  track,
  traf,
  trun,
  u32,
  u64,
  videoFragment,
  visualSampleEntry,
} from './test-boxes.mjs';

const FIXTURES = new URL('../resources/fmp4/', import.meta.url);

function recording(name) {
  return readFiles([new URL(name, FIXTURES).pathname]);
}

function streamOf(...parts) {
  return readStream(Buffer.concat(parts));
}

function firstVideo(...fragmentParts) {
  return videoTrafOf(streamOf(initialization(), ...fragmentParts).fragments[0]);
}

describe('fmp4 box reader', () => {
  it('Should read the tracks in trak order when reading the initialization segment', () => {
    const stream = streamOf(initialization());

    assert.equal(stream.initializationSegmentByteLength, initialization().length);
    assert.deepEqual(
      [...stream.tracks.values()].map(({ trackId, handler, timescale, trexFlags }) => [trackId, handler, timescale, trexFlags]),
      [
        [1, 'vide', 24000, NON_SYNC_SAMPLE_FLAGS],
        [2, 'soun', 48000, SYNC_SAMPLE_FLAGS],
      ],
    );
  });

  it('Should read an edit list entry as its duration and media time when a trak declares one', () => {
    const stream = streamOf(ftyp(), moov(track({ ...VIDEO, edit: [0, 2002] })));

    assert.deepEqual(stream.tracks.get(1).edits, [[0n, 2002n]]);
  });

  it('Should read a version 1 edit list and only the trex boxes of mvex when mvex holds other boxes', () => {
    const video = track({ ...VIDEO, defaultFlags: SYNC_SAMPLE_FLAGS, edit: [1n << 40n, -1n], editVersion: 1 });
    const undeclared = track({ ...AUDIO, trackId: 9 }).trex;
    const movie = box('moov', video.trak, box('mvex', fullBox('mehd', 0, 0, u32(0)), video.trex, undeclared));
    const stream = streamOf(ftyp(), movie);

    assert.deepEqual(stream.tracks.get(1).edits, [[1n << 40n, -1n]]);
    assert.equal(stream.tracks.get(1).trexFlags, SYNC_SAMPLE_FLAGS);
    assert.deepEqual([...stream.tracks.keys()], [1]);
  });

  it("Should read each track's sample entry and NAL length when its decoder configuration declares one", () => {
    const stream = streamOf(
      ftyp(),
      moov(track({ ...VIDEO, sampleEntry: visualSampleEntry('hvc1') }), track({ ...AUDIO, trackId: 3, sampleEntry: box('mp4a') })),
    );

    assert.deepEqual(
      [...stream.tracks.values()].map(({ sampleEntry, nalLengthSize }) => [sampleEntry, nalLengthSize]),
      [
        ['hvc1', 4],
        ['mp4a', null],
      ],
    );
    assert.equal(streamOf(initialization()).tracks.get(1).sampleEntry, null);
    assert.throws(
      () => streamOf(ftyp(), moov(track({ ...VIDEO, sampleEntry: Buffer.alloc(0) }))),
      Mp4FormatError,
    );
  });

  it('Should read the tracks when the moov declares no defaults', () => {
    const stream = streamOf(ftyp(), box('moov', track(VIDEO).trak));

    assert.equal(stream.tracks.get(1).trexFlags, undefined);
  });

  it('Should start a fragment at tfdt plus the signed composition offset when the trun is version 1', () => {
    const start = firstVideo(videoFragment({ decodeTime: 48048, sync: true, compositionOffset: -2002 }));

    assert.equal(start.firstPresentationTime, 46046n);
    assert.equal(start.firstSync, true);
  });

  it('Should read the composition offset as unsigned when the trun is version 0', () => {
    const run = { version: 0, samples: [{ size: 1, compositionOffset: 0x80000000 }], firstSampleFlags: SYNC_SAMPLE_FLAGS };
    const start = firstVideo(fragment({ trackId: 1, decodeTime: 0, runs: [run] }));

    assert.equal(start.firstPresentationTime, 2147483648n);
  });

  it('Should read an unsigned 32-bit decode time when the tfdt is version 0', () => {
    const run = { samples: [{ size: 1 }], firstSampleFlags: SYNC_SAMPLE_FLAGS };
    const start = firstVideo(fragment({ trackId: 1, decodeTime: 0xfffffff0, tfdtVersion: 0, runs: [run] }));

    assert.equal(start.firstPresentationTime, 4294967280n);
  });

  it('Should read sync from the first sample flags, then the sample flags, then tfhd, then trex when each declares it', () => {
    const cases = [
      [{ firstSampleFlags: SYNC_SAMPLE_FLAGS, samples: [{ size: 1, flags: NON_SYNC_SAMPLE_FLAGS }] }, null, true],
      [{ samples: [{ size: 1, flags: NON_SYNC_SAMPLE_FLAGS }] }, SYNC_SAMPLE_FLAGS, false],
      [{ samples: [{ size: 1 }] }, SYNC_SAMPLE_FLAGS, true],
      [{ samples: [{ size: 1 }] }, null, false],
    ];
    for (const [run, defaultFlags, sync] of cases) {
      assert.equal(firstVideo(fragment({ trackId: 1, decodeTime: 0, defaultFlags, runs: [run] })).firstSync, sync, JSON.stringify(run));
    }
  });

  it('Should give a fragment no video start when it carries only audio or an empty video run', () => {
    const emptyVideo = fragment({ trackId: 1, decodeTime: 0, runs: [{ samples: [] }] });
    const stream = streamOf(initialization(), audioFragment({ decodeTime: 0 }), emptyVideo);

    assert.deepEqual(stream.fragments.map(videoTrafOf), [null, null]);
  });

  it('Should list every video sample in decode order with its presentation time when a fragment holds several', () => {
    const run = {
      samples: [
        { duration: 1001, size: 3, compositionOffset: 2002 },
        { duration: 1001, size: 4, compositionOffset: 0 },
      ],
      firstSampleFlags: SYNC_SAMPLE_FLAGS,
    };
    const bytes = Buffer.concat([initialization(), audioFragment({ decodeTime: 0 }), fragment({ trackId: 1, decodeTime: 1001, runs: [run] })]);
    const payload = bytes.length - 7;

    assert.deepEqual(videoSamples(readStream(bytes)), [
      { presentationTime: 3003n, sync: true, size: 3, offset: payload, fragmentIndex: 1 },
      { presentationTime: 2002n, sync: false, size: 4, offset: payload + 3, fragmentIndex: 1 },
    ]);
  });

  it('Should read boxes when they declare a 64-bit size', () => {
    const large = (dataOffset) =>
      largeBox(
        'moof',
        largeBox(
          'traf',
          fullBox('tfhd', 0, TFHD_DEFAULT_BASE_IS_MOOF, u32(1)),
          fullBox('tfdt', 0, 0, u32(7)),
          trun({ samples: [{ size: 1 }], dataOffset, firstSampleFlags: SYNC_SAMPLE_FLAGS }),
        ),
      );

    assert.equal(firstVideo(moofPointingAtItsMdat(0, large), mdat([1])).firstPresentationTime, 7n);
  });

  it('Should read the signed -1024 FFmpeg wrote when a decode time is 2^64 - 1024', () => {
    assert.equal(signed(18446744073709550592n), -1024n);
    assert.equal(signed(1024n), 1024n);
  });

  it('Should fail when a stream breaks the box structure', () => {
    const cases = [
      ['a truncated box header', Buffer.concat([initialization(), Buffer.alloc(4)])],
      ['a box of size 0', Buffer.concat([initialization(), u32(0), Buffer.from('mdat')])],
      ['a box that overruns the stream', Buffer.concat([initialization(), u32(64), Buffer.from('mdat')])],
      ['a moof before the moov', Buffer.concat([ftyp(), videoFragment({ decodeTime: 0, sync: true })])],
      ['a moof after a moof', Buffer.concat([initialization(), moof(), moof()])],
      ['an mdat without a moof', Buffer.concat([initialization(), mdat([1])])],
      ['a moof at the end', Buffer.concat([initialization(), moof()])],
      ['a traf for an undeclared track', Buffer.concat([initialization(), moof(traf({ trackId: 9, decodeTime: 0, runs: [] })), mdat([])])],
      ['a traf without tfhd', Buffer.concat([initialization(), moof(box('traf')), mdat([])])],
    ];
    for (const [name, bytes] of cases) {
      assert.throws(() => readStream(bytes), Mp4FormatError, name);
    }
  });
});

describe('fmp4 box structure', () => {
  const failures = (bytes) => assert.throws(() => readStream(Buffer.concat([initialization(), bytes])), Mp4FormatError);

  it("Should fail when a box's size is smaller than its header even though the bytes after it parse", () => {
    failures(Buffer.concat([u32(4), u32(8), Buffer.from('free', 'latin1')]));
    failures(Buffer.concat([u32(1), Buffer.from('free', 'latin1'), u64(12), Buffer.from('skip', 'latin1'), Buffer.alloc(4)]));
  });

  it('Should fail when a 64-bit box header does not fit inside its parent', () => {
    failures(Buffer.concat([u32(1), Buffer.from('free', 'latin1')]));
  });

  it("Should fail when a tfhd's flags promise a field its box does not hold", () => {
    const tfhd = fullBox('tfhd', 0, TFHD_DEFAULT_BASE_IS_MOOF | TFHD_DEFAULT_SAMPLE_FLAGS, u32(VIDEO.trackId));
    const run = trun({ samples: [{ size: 1 }], firstSampleFlags: SYNC_SAMPLE_FLAGS });

    failures(Buffer.concat([moof(box('traf', tfhd, fullBox('tfdt', 1, 0, u64(0)), run)), mdat([1])]));
  });

  it('Should fail when a trun declares more samples than its box holds', () => {
    const run = fullBox('trun', 1, TRUN_SAMPLE_SIZE, u32(3), u32(1));
    const trailing = fullBox('free', 0, 0, u32(1), u32(1), u32(1), u32(1));

    failures(Buffer.concat([moof(traf({ trackId: VIDEO.trackId, decodeTime: 0, runs: [run, trailing] })), mdat([1])]));
  });

  it('Should fail when a handler box is too short to name its handler', () => {
    const shortHandler = track(VIDEO);
    const hdlrAt = shortHandler.trak.indexOf('hdlr', 0, 'latin1') - 4;
    const truncated = Buffer.from(shortHandler.trak);
    truncated.writeUInt32BE(12, hdlrAt);

    assert.throws(() => readStream(Buffer.concat([ftyp(), box('moov', truncated)])), Mp4FormatError);
  });
});

describe('fmp4 sample data', () => {
  const tfdt = fullBox('tfdt', 1, 0, u64(0));
  const videoRun = (options = {}) => trun({ samples: [{ size: 2 }], firstSampleFlags: SYNC_SAMPLE_FLAGS, ...options });
  const audioRun = (options = {}) => trun({ samples: [{ size: 3 }], ...options });

  /** A moof whose video traf starts its data at an absolute base and whose audio traf follows it. */
  function explicitBase(shift) {
    const build = (base) =>
      moof(
        box('traf', fullBox('tfhd', 0, TFHD_BASE_DATA_OFFSET, u32(VIDEO.trackId), u64(base + shift)), tfdt, videoRun()),
        box('traf', fullBox('tfhd', 0, 0, u32(AUDIO.trackId)), tfdt, audioRun()),
      );
    const moofBytes = moofPointingAtItsMdat(initialization().length, build);
    return Buffer.concat([initialization(), moofBytes, mdat([1, 2, 3, 4, 5])]);
  }

  it("Should fail when a video run's data offset points at its moof instead of its mdat", () => {
    const fragment = Buffer.concat([moof(traf({ trackId: VIDEO.trackId, decodeTime: 0, runs: [videoRun({ dataOffset: 0 })] })), mdat([1, 2])]);

    assert.throws(() => readStream(Buffer.concat([initialization(), fragment])), Mp4FormatError);
  });

  it("Should fail when an audio run's samples end past its mdat", () => {
    const fourByteSample = audioFragment({ decodeTime: 0, size: 4 });
    const moofOnly = fourByteSample.subarray(0, fourByteSample.length - 12);

    assert.throws(() => readStream(Buffer.concat([initialization(), moofOnly, mdat([1, 2, 3])])), Mp4FormatError);
  });

  it('Should read runs after the previous run and traf when they declare no data offset', () => {
    assert.deepEqual(
      readStream(explicitBase(0)).fragments[0].trafs.map((entry) => entry.sampleCount),
      [1, 1],
    );
    assert.throws(() => readStream(explicitBase(1)), Mp4FormatError);
    assert.throws(() => readStream(explicitBase(-1)), Mp4FormatError);
  });

  it("Should read a traf's second run after the first when it declares no data offset", () => {
    const build = (dataOffset) =>
      moof(traf({ trackId: VIDEO.trackId, decodeTime: 0, runs: [videoRun({ dataOffset }), trun({ samples: [{ size: 3 }] })] }));
    const valid = moofPointingAtItsMdat(0, build);

    assert.equal(readStream(Buffer.concat([initialization(), valid, mdat([1, 2, 3, 4, 5])])).fragments[0].trafs[0].sampleCount, 2);
    assert.throws(() => readStream(Buffer.concat([initialization(), valid, mdat([1, 2, 3, 4])])), Mp4FormatError);
  });
});

describe('fmp4 box reader over the recordings', () => {
  it('Should read the stream-copy replacement attempt from its preroll keyframe at 28.028 s when it seeks to 30 s', () => {
    const stream = recording('07-copy-seek30.fmp4');
    const first = videoTrafOf(stream.fragments[0]);

    assert.equal(first.timescale, 24000);
    assert.equal(first.firstPresentationTime, 672672n);
    assert.equal(first.firstSync, true);
  });

  it('Should read the AAC priming as the unsigned wrap of -1024 when reading the first audio tfdt', () => {
    const audio = recording('01-encode-cfr.fmp4')
      .fragments.flatMap((fragment) => fragment.trafs)
      .find((entry) => entry.handler === 'soun');

    assert.equal(audio.baseMediaDecodeTime, 18446744073709550592n);
  });

  it('Should read the fragment at 20.02 s when the 10 s-GOP copy skips a segment number there', () => {
    const start = videoTrafOf(recording('10-copy-gop-exceeds-period.fmp4').fragments[20]);

    assert.equal(start.firstPresentationTime, 480480n);
    assert.equal(start.firstSync, true);
  });

  it('Should read every byte as the initialization segment and fragments when reading a recording', () => {
    const path = new URL('07-copy-start0.fmp4', FIXTURES).pathname;
    const stream = readFiles([path]);

    assert.equal(
      stream.initializationSegmentByteLength + stream.fragments.reduce((sum, fragment) => sum + fragment.byteLength, 0),
      readFileSync(path).length,
    );
  });
});

describe('fmp4 box reader command line', () => {
  it('Should print every box fact except the bytes and sample tables when run from the command line', () => {
    const script = new URL('fmp4.mjs', import.meta.url).pathname;
    const run = spawnSync(process.execPath, [script, new URL('10-copy-gop-exceeds-period.fmp4', FIXTURES).pathname], {
      encoding: 'utf8',
    });
    const dump = JSON.parse(run.stdout);

    assert.equal(run.status, 0, run.stderr);
    assert.equal(dump.initializationSegmentByteLength, 1348);
    assert.deepEqual(Object.keys(dump.tracks), ['1', '2']);
    assert.equal(dump.fragments[20].trafs[0].firstPresentationTime, 480480);
    assert.equal(dump.fragments[20].trafs[0].samples, undefined);
  });
});
