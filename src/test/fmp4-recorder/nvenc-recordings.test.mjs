import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { readStream } from './fmp4.mjs';
import {
  RESULTS_FILE,
  analyze,
  checkRecordings,
  compactJson,
  initializationSegmentFacts,
  initializationSegmentPairs,
  keyframeVerdicts,
  main,
  outputCheck,
  planOf,
  recordingFacts,
  summary,
} from './nvenc-recordings.mjs';
import { temporaryDirectory } from './temporary-directories.mjs';
import {
  NON_SYNC_SAMPLE_FLAGS,
  SYNC_SAMPLE_FLAGS,
  VIDEO,
  accessUnit,
  box,
  fragment,
  ftyp,
  moov,
  track,
} from './test-boxes.mjs';

const COMMITTED_RESULTS = fileURLToPath(new URL('../resources/nvenc', import.meta.url));
const FRAME = 1001;
const SECOND = 24000;

function u16(value) {
  const bytes = Buffer.alloc(2);
  bytes.writeUInt16BE(value);
  return bytes;
}

function parameterSet(bytes) {
  return Buffer.concat([u16(bytes.length), Buffer.from(bytes)]);
}

/** An avcC with one SPS and one PPS, and the High profile's chroma format and bit depths when given. */
function avcC({ profile = 100, constraints = 0, level = 40, format = { chroma: 1, bitDepth: 8 } } = {}) {
  const parts = [Buffer.from([1, profile, constraints, level, 0xff, 0xe1]), parameterSet([0x67, profile]), Buffer.from([1])];
  parts.push(parameterSet([0x68, 0xce]));
  if (format !== null) {
    parts.push(Buffer.from([0xfc | format.chroma, 0xf8 | (format.bitDepth - 8), 0xf8 | (format.bitDepth - 8), 0]));
  }
  return box('avcC', ...parts);
}

/** An hvcC with one NAL unit in each array, by default a VPS, an SPS and a PPS. */
function hvcC({ spaceTierAndProfile = 0x01, constraints = [0x90, 0, 0, 0, 0, 0], level = 120, arrays = [32, 33, 34] } = {}) {
  const fields = Buffer.alloc(22);
  fields[0] = 1;
  fields[1] = spaceTierAndProfile;
  fields.writeUInt32BE(0x60000000, 2);
  constraints.forEach((byte, index) => {
    fields[6 + index] = byte;
  });
  fields[12] = level;
  fields.writeUInt16BE(0xf000, 13);
  fields[15] = 0xfc;
  fields[16] = 0xfd;
  fields[17] = 0xf8;
  fields[18] = 0xf8;
  fields[21] = 0x0f;
  const nalUnits = arrays.map((type) => Buffer.concat([Buffer.from([type]), u16(1), parameterSet([type << 1, 1])]));
  return box('hvcC', fields, Buffer.from([arrays.length]), ...nalUnits);
}

function initializationSegment(sampleEntryType, record) {
  const sampleEntry = box(sampleEntryType, Buffer.alloc(78), ...(record === null ? [] : [record]));
  return Buffer.concat([ftyp(), moov(track({ trackId: 2, handler: 'soun', timescale: 48000 }), track({ ...VIDEO, sampleEntry }))]);
}

const HEVC = { IDR_N_LP: [0x28, 0x01], IDR_W_RADL: [0x26, 0x01], CRA: [0x2a, 0x01], RADL_N: [0x0e, 0x01], TRAIL_R: [0x02, 0x01] };

/** Facts for frames given in decode order as [presentation frame, sync, NAL unit type of a keyframe]. */
function facts(frames, sampleEntry = 'hvc1') {
  return {
    sampleEntry,
    timescale: SECOND,
    presentationTimes: frames.map(([frame]) => frame * FRAME),
    syncSamples: frames.flatMap(([, sync], index) => (sync ? [index] : [])),
    keyframePictures: frames.filter(([, sync]) => sync).map(([frame, , type]) => ({ presentationTime: frame * FRAME, nalUnitType: type })),
  };
}

/** COUNT frames (30 by default) in order, with keyframes of the given NAL unit type at the given frames. */
function inOrder(keyframes, count = 30, type = 20) {
  return Array.from({ length: count }, (_, frame) => [frame, keyframes.includes(frame), keyframes.includes(frame) ? type : null]);
}

/** Facts whose second lasts 10 frames, so that forced time t falls on frame 10 t. */
function tenFrameFacts(frames, sampleEntry) {
  return { ...facts(frames, sampleEntry), timescale: 10 * FRAME };
}

function plan(overrides = {}) {
  return { forcedKeyframeTimes: [0, 1, 2], gopFrames: 9, skippedForcedTime: null, ...overrides };
}

describe('recording facts', () => {
  it('Should keep every sample presentation time, the sync samples and the picture that starts each keyframe when the track is HEVC', () => {
    const units = [
      [accessUnit(HEVC.IDR_N_LP), true, 0],
      [accessUnit(HEVC.TRAIL_R), false, 2 * FRAME],
      [accessUnit(HEVC.TRAIL_R), false, -FRAME],
      [accessUnit(HEVC.CRA), true, 0],
    ];
    const run = {
      samples: units.map(([bytes, sync, compositionOffset]) => ({
        duration: FRAME,
        size: bytes.length,
        flags: sync ? SYNC_SAMPLE_FLAGS : NON_SYNC_SAMPLE_FLAGS,
        compositionOffset,
      })),
      payload: units.flatMap(([bytes]) => bytes),
    };
    const stream = readStream(
      Buffer.concat([initializationSegment('hvc1', hvcC()), fragment({ trackId: VIDEO.trackId, decodeTime: 0, runs: [run] })]),
    );

    assert.deepEqual(recordingFacts(stream), {
      sampleEntry: 'hvc1',
      timescale: SECOND,
      presentationTimes: [0, 3 * FRAME, FRAME, 3 * FRAME],
      syncSamples: [0, 3],
      keyframePictures: [
        { presentationTime: 0, nalUnitType: 20 },
        { presentationTime: 3 * FRAME, nalUnitType: 21 },
      ],
    });
  });

  it('Should keep no picture types when the track is neither H.264 nor HEVC', () => {
    const stream = readStream(
      Buffer.concat([
        initializationSegment('vp09', null),
        fragment({ trackId: VIDEO.trackId, decodeTime: 0, runs: [{ samples: [{ duration: FRAME, size: 1 }], firstSampleFlags: SYNC_SAMPLE_FLAGS }] }),
      ]),
    );

    assert.deepEqual(recordingFacts(stream).keyframePictures, []);
  });
});

describe('keyframe verdicts', () => {
  it('Should find every forced keyframe a sync sample that starts a closed GOP when each is an IDR picture without leading pictures', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10, 20])), plan());

    assert.equal(verdicts.everyForcedKeyframeIsASyncSample, true);
    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, true);
    assert.deepEqual(
      verdicts.forcedKeyframes.map((entry) => entry.presentationTime),
      [0, 10 * FRAME, 20 * FRAME],
    );
    assert.deepEqual(verdicts.extraKeyframes, []);
    assert.equal(verdicts.encoderAddsKeyframes, false);
    assert.equal(verdicts.gopRestart, null);
  });

  it('Should find a forced keyframe that is not a sync sample when the encoder keys a frame after it instead', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 11, 20])), plan());

    assert.equal(verdicts.everyForcedKeyframeIsASyncSample, false);
    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, false);
    assert.deepEqual(verdicts.forcedKeyframes[1], {
      time: 1,
      presentationTime: 10 * FRAME,
      sync: false,
      nalUnitType: null,
      leadingPictures: 0,
      startsClosedGop: false,
    });
    assert.deepEqual(
      verdicts.extraKeyframes.map((entry) => [entry.presentationTime, entry.framesAfterPreviousKeyframe, entry.placedByGop]),
      [[11 * FRAME, 11, false]],
    );
  });

  it('Should find a forced keyframe that opens its GOP when the encoder writes a CRA picture', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10], 20, 21)), plan({ forcedKeyframeTimes: [0, 1] }));

    assert.equal(verdicts.everyForcedKeyframeIsASyncSample, true);
    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, false);
    assert.equal(verdicts.forcedKeyframes[1].nalUnitType, 21);
  });

  it('Should count the leading pictures of a forced IDR picture when a picture after it in decode order comes before it in presentation order', () => {
    const frames = [
      [0, true, 20],
      [2, false, null],
      [1, false, null],
      [10, true, 19],
      [9, false, null],
      [11, false, null],
    ];

    const verdicts = keyframeVerdicts(tenFrameFacts(frames), plan({ forcedKeyframeTimes: [0, 1] }));

    assert.equal(verdicts.forcedKeyframes[1].leadingPictures, 1);
    assert.equal(verdicts.forcedKeyframes[1].startsClosedGop, false);
    assert.equal(verdicts.forcedKeyframes[0].startsClosedGop, true);
  });

  it('Should read H.264 IDR pictures as type 5 when the track is H.264', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10], 20, 5), 'avc1'), plan({ forcedKeyframeTimes: [0, 1] }));

    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, true);
  });

  it('Should find no closed GOP when the track is a codec whose picture types are not read', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0], 10, null), 'vp09'), plan({ forcedKeyframeTimes: [0] }));

    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, false);
  });

  it('Should tell the keyframes the GOP places from the keyframes the encoder adds when both occur', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 9, 14, 20, 29])), plan());

    assert.deepEqual(
      verdicts.extraKeyframes.map((entry) => [entry.presentationTime / FRAME, entry.framesAfterPreviousKeyframe, entry.placedByGop]),
      [
        [9, 9, true],
        [14, 5, false],
        [29, 9, true],
      ],
    );
    assert.equal(verdicts.encoderAddsKeyframes, true);
  });

  it('Should describe the first frame when a replacement attempt starts between boundaries', () => {
    const frames = inOrder([0, 6]).slice(4, 16);

    const verdicts = keyframeVerdicts(tenFrameFacts(frames.map(([frame, sync]) => [frame, frame === 4 || sync, 20])), plan({ forcedKeyframeTimes: [1] }));

    assert.deepEqual(verdicts.firstFrame, {
      presentationTime: 4 * FRAME,
      sync: true,
      nalUnitType: 20,
      leadingPictures: 0,
      startsClosedGop: true,
    });
    assert.deepEqual(
      verdicts.forcedKeyframes.map((entry) => entry.time),
      [1],
    );
  });

  it('Should find a forced keyframe missing and fail both keyframe verdicts when the recording ends before a planned forced time', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10], 16)), plan());

    assert.deepEqual(verdicts.forcedKeyframes[2], {
      time: 2,
      presentationTime: null,
      sync: false,
      nalUnitType: null,
      leadingPictures: null,
      startsClosedGop: false,
    });
    assert.equal(verdicts.everyForcedKeyframeIsASyncSample, false);
    assert.equal(verdicts.everyForcedKeyframeStartsAClosedGop, false);
  });

  it('Should find that the GOP count restarts at the forced keyframe before a missed one when the next keyframe lands one GOP after it', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10, 19])), plan({ forcedKeyframeTimes: [0, 1], skippedForcedTime: 2 }));

    assert.deepEqual(verdicts.gopRestart, {
      forcedKeyframeBefore: 10 * FRAME,
      expectedKeyframe: 19 * FRAME,
      nextKeyframe: 19 * FRAME,
      restartsAtForcedKeyframe: true,
    });
  });

  it('Should find that the GOP count does not restart when the next keyframe after a missed one lands one GOP after the first frame', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10, 18])), plan({ forcedKeyframeTimes: [0, 1], skippedForcedTime: 2 }));

    assert.equal(verdicts.gopRestart.restartsAtForcedKeyframe, false);
    assert.equal(verdicts.gopRestart.nextKeyframe, 18 * FRAME);
  });

  it('Should find no restart when no keyframe follows the forced keyframe before a missed one', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0, 10], 15)), plan({ forcedKeyframeTimes: [0, 1], skippedForcedTime: 2 }));

    assert.deepEqual(verdicts.gopRestart, {
      forcedKeyframeBefore: 10 * FRAME,
      expectedKeyframe: null,
      nextKeyframe: null,
      restartsAtForcedKeyframe: false,
    });
  });

  it('Should judge no GOP restart when the missed forced time is the first one', () => {
    const verdicts = keyframeVerdicts(tenFrameFacts(inOrder([0])), plan({ forcedKeyframeTimes: [1], skippedForcedTime: 0 }));

    assert.equal(verdicts.gopRestart, null);
  });
});

describe('initialization segment facts', () => {
  it('Should read the codec string, the picture format and the parameter sets when the track is H.264 High', () => {
    const facts = initializationSegmentFacts(initializationSegment('avc1', avcC()));

    assert.equal(facts.codecString, 'avc1.640028');
    assert.deepEqual(facts.pictureFormat, { bitDepth: 8, chroma: '4:2:0' });
    assert.equal(facts.holdsParameterSets, true);
    assert.match(facts.sha256, /^[0-9a-f]{64}$/);
    assert.equal(facts.deliveredSha256, facts.sha256);
  });

  it('Should read no picture format when an H.264 High record ends after its parameter sets', () => {
    const facts = initializationSegmentFacts(initializationSegment('avc1', avcC({ format: null })));

    assert.equal(facts.pictureFormat, null);
  });

  it('Should read 8-bit 4:2:0 when the H.264 profile carries no format in its record', () => {
    const facts = initializationSegmentFacts(initializationSegment('avc1', avcC({ profile: 77, constraints: 0x40, level: 31, format: null })));

    assert.equal(facts.codecString, 'avc1.4D401F');
    assert.deepEqual(facts.pictureFormat, { bitDepth: 8, chroma: '4:2:0' });
  });

  it('Should read the 10-bit 4:2:2 format when the H.264 High record declares it', () => {
    const facts = initializationSegmentFacts(initializationSegment('avc1', avcC({ profile: 122, format: { chroma: 2, bitDepth: 10 } })));

    assert.deepEqual(facts.pictureFormat, { bitDepth: 10, chroma: '4:2:2' });
  });

  it('Should write the codec string as CodecConfigurationReader writes it when the track is HEVC', () => {
    assert.equal(initializationSegmentFacts(initializationSegment('hvc1', hvcC())).codecString, 'hvc1.1.6.L120.90');
    assert.equal(
      initializationSegmentFacts(initializationSegment('hvc1', hvcC({ spaceTierAndProfile: 0x62, constraints: [0xb0, 0, 0, 0, 0, 1], level: 153 })))
        .codecString,
      'hvc1.A2.6.H153.B0.0.0.0.0.1',
    );
  });

  it('Should find a parameter set missing when an HEVC record holds no PPS', () => {
    const facts = initializationSegmentFacts(initializationSegment('hvc1', hvcC({ arrays: [32, 33] })));

    assert.equal(facts.holdsParameterSets, false);
    assert.deepEqual(facts.pictureFormat, { bitDepth: 8, chroma: '4:2:0' });
  });

  it('Should digest the initialization segment as the worker delivers it when the HEVC record holds an SEI message', () => {
    const facts = initializationSegmentFacts(initializationSegment('hvc1', hvcC({ arrays: [32, 33, 34, 39] })));

    assert.notEqual(facts.deliveredSha256, facts.sha256);
  });

  it('Should read only the sample entry when the track is neither H.264 nor HEVC', () => {
    const facts = initializationSegmentFacts(initializationSegment('vp09', null));

    assert.equal(facts.codecString, 'vp09');
    assert.equal(facts.pictureFormat, null);
    assert.equal(facts.holdsParameterSets, false);
  });

  it('Should refuse an initialization segment without a video track', () => {
    const audioOnly = Buffer.concat([ftyp(), moov(track({ trackId: 2, handler: 'soun', timescale: 48000 }))]);

    assert.throws(() => initializationSegmentFacts(audioOnly), /declares no video track/);
  });
});

describe('output check', () => {
  const passing = { codecString: 'avc1.640028', pictureFormat: { bitDepth: 8, chroma: '4:2:0' }, holdsParameterSets: true };

  it('Should pass when the codec string matches the job and the record holds 8-bit 4:2:0 and its parameter sets', () => {
    assert.deepEqual(outputCheck(passing, 'avc1.640028'), { expectedCodecString: 'avc1.640028', codecString: 'avc1.640028', passes: true });
  });

  it('Should fail when the codec string, the picture format or the parameter sets differ from what the job needs', () => {
    assert.equal(outputCheck(passing, 'avc1.64001F').passes, false);
    assert.equal(outputCheck({ ...passing, pictureFormat: { bitDepth: 10, chroma: '4:2:0' } }, 'avc1.640028').passes, false);
    assert.equal(outputCheck({ ...passing, pictureFormat: { bitDepth: 8, chroma: '4:4:4' } }, 'avc1.640028').passes, false);
    assert.equal(outputCheck({ ...passing, pictureFormat: null }, 'avc1.640028').passes, false);
    assert.equal(outputCheck({ ...passing, holdsParameterSets: false }, 'avc1.640028').passes, false);
  });
});

describe('initialization segment pairs', () => {
  it('Should pair each replacement attempt with the first attempt it replaces and compare their delivered digests', () => {
    const recording = (name, replaces, deliveredSha256) => ({ name, replaces, initializationSegment: { deliveredSha256 } });

    assert.deepEqual(
      initializationSegmentPairs([recording('a', null, 'x'), recording('b', 'a', 'x'), recording('c', 'a', 'y')]),
      [
        { first: 'a', replacement: 'b', identical: true },
        { first: 'a', replacement: 'c', identical: false },
      ],
    );
  });
});

describe('plan', () => {
  it('Should read the plan record-nvenc.sh writes beside a recording', () => {
    const meta = [
      'encoder=hevc_nvenc',
      'rung=360p',
      'recipe=extended',
      'level=21',
      'tier=main',
      'expectedCodecString=hvc1.1.6.L63.90',
      'startSequenceNumber=0',
      'gopFrames=145',
      'forcedKeyframeTimes=0,6,12,24,30',
      'skippedForcedTime=18',
      'replaces=',
      '',
    ].join('\n');

    assert.deepEqual(planOf(meta), {
      encoder: 'hevc_nvenc',
      rung: '360p',
      recipe: 'extended',
      startSequenceNumber: 0,
      gopFrames: 145,
      forcedKeyframeTimes: [0, 6, 12, 24, 30],
      skippedForcedTime: 18,
      level: 21,
      tier: 'main',
      expectedCodecString: 'hvc1.1.6.L63.90',
    });
    const empty = planOf('forcedKeyframeTimes=\nskippedForcedTime=\ntier=');
    assert.deepEqual([empty.forcedKeyframeTimes, empty.skippedForcedTime, empty.tier], [[], null, null]);
  });
});

describe('analysis and offline check', () => {
  /** A recording of 30 frames at 23.976 fps from the start, keyed at 0 s and 1 s with HEVC IDR pictures. */
  function hevcRecording(keyframes = [0, 24]) {
    const units = Array.from({ length: 30 }, (_, frame) => [keyframes.includes(frame) ? accessUnit(HEVC.IDR_N_LP) : accessUnit(HEVC.TRAIL_R), keyframes.includes(frame)]);
    const run = {
      samples: units.map(([bytes, sync]) => ({ duration: FRAME, size: bytes.length, flags: sync ? SYNC_SAMPLE_FLAGS : NON_SYNC_SAMPLE_FLAGS })),
      payload: units.flatMap(([bytes]) => bytes),
    };
    return Buffer.concat([initializationSegment('hvc1', hvcC()), fragment({ trackId: VIDEO.trackId, decodeTime: 0, runs: [run] })]);
  }

  function meta(replaces) {
    return [
      'encoder=hevc_nvenc',
      'rung=1080p',
      'recipe=worker',
      'level=40',
      'tier=main',
      'expectedCodecString=hvc1.1.6.L120.90',
      'startSequenceNumber=0',
      'gopFrames=9',
      'forcedKeyframeTimes=0,1',
      'skippedForcedTime=',
      `replaces=${replaces}`,
    ].join('\n');
  }

  function recordedWork() {
    const work = temporaryDirectory('nvenc-work-');
    mkdirSync(join(work, 'out'));
    for (const [name, replaces] of [
      ['hevc_nvenc-1080p-first', ''],
      ['hevc_nvenc-1080p-replacement', 'hevc_nvenc-1080p-first'],
    ]) {
      writeFileSync(join(work, 'out', `${name}.fmp4`), hevcRecording());
      writeFileSync(join(work, 'out', `${name}.meta`), meta(replaces));
      writeFileSync(join(work, 'out', `${name}.args`), 'ffmpeg\n-y\n');
    }
    writeFileSync(join(work, 'environment'), 'driverVersion=1.2.3\ncomputeCapability=9.0\n');
    return work;
  }

  function analyzed() {
    const results = temporaryDirectory('nvenc-results-');
    const work = recordedWork();
    const document = analyze(work, results);
    return { results, document };
  }

  it('Should write the results file and every initialization segment when record-nvenc.sh has left its recordings', () => {
    const { results, document } = analyzed();

    assert.deepEqual(document.environment, { driverVersion: '1.2.3', computeCapability: '9.0' });
    assert.deepEqual(
      document.recordings.map((recording) => [recording.name, recording.replaces, recording.ffmpegArguments]),
      [
        ['hevc_nvenc-1080p-first', null, ['ffmpeg', '-y']],
        ['hevc_nvenc-1080p-replacement', 'hevc_nvenc-1080p-first', ['ffmpeg', '-y']],
      ],
    );
    assert.deepEqual(document.initializationSegmentPairs, [
      { first: 'hevc_nvenc-1080p-first', replacement: 'hevc_nvenc-1080p-replacement', identical: true },
    ]);
    assert.equal(document.recordings[0].outputCheck.passes, true);
    assert.ok(existsSync(join(results, 'hevc_nvenc-1080p-first.init.mp4')));
    assert.deepEqual(JSON.parse(readFileSync(join(results, RESULTS_FILE), 'utf8')), JSON.parse(JSON.stringify(document)));
  });

  it('Should find nothing to report when the results file and its initialization segments are unchanged', () => {
    const { results } = analyzed();

    assert.deepEqual(checkRecordings(results), []);
  });

  it('Should name each result that the committed facts or initialization segments no longer reproduce', () => {
    const { results } = analyzed();
    const path = join(results, RESULTS_FILE);
    const document = JSON.parse(readFileSync(path, 'utf8'));
    document.recordings[0].verdicts.everyForcedKeyframeIsASyncSample = false;
    document.initializationSegmentPairs[0].identical = false;
    writeFileSync(path, JSON.stringify(document));
    writeFileSync(join(results, 'hevc_nvenc-1080p-replacement.init.mp4'), initializationSegment('hvc1', hvcC({ level: 93 })));

    assert.deepEqual(checkRecordings(results), [
      'hevc_nvenc-1080p-first: verdicts does not follow from the recorded facts',
      'hevc_nvenc-1080p-replacement: the initialization segment facts do not match hevc_nvenc-1080p-replacement.init.mp4',
      'hevc_nvenc-1080p-replacement: outputCheck does not follow from the recorded facts',
      'initializationSegmentPairs does not follow from the initialization segments',
    ]);
  });

  it('Should name a missing initialization segment', () => {
    const { results } = analyzed();
    rmSync(join(results, 'hevc_nvenc-1080p-first.init.mp4'));

    assert.deepEqual(checkRecordings(results), ['hevc_nvenc-1080p-first: hevc_nvenc-1080p-first.init.mp4 is missing']);
  });

  it('Should print one line of verdicts per recording', () => {
    const { document } = analyzed();

    assert.deepEqual(
      summary(document).map((line) => line.replace(/\s+/g, ' ')),
      [
        'hevc_nvenc-1080p-first sync=true closed-gop=true extra=0 gop-restart=- init-identical=- output-check=true (hvc1.1.6.L120.90)',
        'hevc_nvenc-1080p-replacement sync=true closed-gop=true extra=0 gop-restart=- init-identical=true output-check=true (hvc1.1.6.L120.90)',
      ],
    );
  });

  it('Should exit 0 after analyzing, 0 or 1 after checking, and 2 on a usage error when run from the command line', () => {
    const { results } = analyzed();
    const write = process.stdout.write;
    const writeError = process.stderr.write;
    process.stdout.write = () => true;
    process.stderr.write = () => true;
    try {
      assert.equal(main(['analyze', recordedWork(), temporaryDirectory('nvenc-results-')]), 0);
      assert.equal(main(['check', results]), 0);
      rmSync(join(results, 'hevc_nvenc-1080p-first.init.mp4'));
      assert.equal(main(['check', results]), 1);
      assert.equal(main(['record']), 2);
    } finally {
      process.stdout.write = write;
      process.stderr.write = writeError;
    }
  });

  it('Should write every array of integers on one line', () => {
    assert.equal(compactJson({ a: [1, -2, 3], b: [{ c: 1 }], d: [] }), '{\n  "a": [1, -2, 3],\n  "b": [\n    {\n      "c": 1\n    }\n  ],\n  "d": []\n}\n');
  });
});

describe('committed recordings', () => {
  it(
    'Should derive every committed verdict again from the committed facts and initialization segments when NVENC recordings are committed',
    { skip: existsSync(join(COMMITTED_RESULTS, RESULTS_FILE)) ? false : 'no NVENC recordings yet: run record-nvenc.sh on an NVIDIA GPU host' },
    () => {
      assert.deepEqual(checkRecordings(COMMITTED_RESULTS), []);
    },
  );
});
