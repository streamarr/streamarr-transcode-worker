import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import {
  checkSourceKeyframes,
  describe as describeSegment,
  diagnostics,
  evaluateOracle,
  group,
  hlsencCuts,
  loadSource,
  mismatches,
  readHls,
  sourceKeyframes,
  sourceStart,
  violatedClaims,
} from './analysis.mjs';
import { readStream, videoSamples, videoStart } from './fmp4.mjs';
import { Decimal } from './json.mjs';
import { Rational } from './rational.mjs';
import { NON_SYNC, SYNC, VIDEO, audioFragment, fragment, initialization, videoFragment } from './test-boxes.mjs';

const RECORDING = new URL('../resources/fmp4/07-copy-start0.fmp4', import.meta.url).pathname;
const seconds = (text) => Rational.parseDecimal(text);

function probe(startTime, timeBase) {
  return { format: { start_time: startTime }, streams: [{ codec_type: 'audio' }, { codec_type: 'video', time_base: timeBase }] };
}

describe('source start', () => {
  it('rounds the container start to the nearest tick of the video time base', () => {
    const { start, startMicros } = sourceStart(probe('11.978667', '1/90000'));

    assert.equal(String(start), '4492/375');
    assert.equal(startMicros, 11978667n);
  });

  it('rounds a start half a tick from two ticks to the even tick', () => {
    assert.equal(String(sourceStart(probe('0.75', '1/2')).start), '1');
    assert.equal(String(sourceStart(probe('0.25', '1/2')).start), '0');
  });

  it('reads each keyframe of the ffprobe listing in seconds', () => {
    assert.deepEqual(sourceKeyframes('0,K__\n180180,K__\n\n', 1n, 90000n).map(String), ['0', '1001/500']);
  });
});

describe('hlsenc model', () => {
  const pts = ['0', '6', '6.5', '12', '12.5'].map(seconds);

  it('cuts at the first keyframe whose time from the reference reaches the next multiple of the period', () => {
    assert.deepEqual(hlsencCuts([0, 1, 2, 3, 4], pts, seconds('0'), 6), [0, 1, 3]);
    assert.deepEqual(hlsencCuts([0, 1, 2, 3, 4], pts, seconds('0.25'), 6), [0, 2, 4]);
  });

  it('never cuts at a keyframe that does not advance past the last cut', () => {
    assert.deepEqual(hlsencCuts([0, 1, 2], ['0', '0', '6'].map(seconds), seconds('-6'), 6), [0, 2]);
  });
});

describe('grid and HLS comparison', () => {
  it('lists every number whose first video sample differs, with the side that lacks it as null', () => {
    const grid = [
      { number: 0, firstVideoPresentationTime: 0n },
      { number: 1, firstVideoPresentationTime: 144144n },
    ];
    const hls = [
      { number: 0, firstVideoPresentationTime: 0n },
      { number: 1, firstVideoPresentationTime: 145145n },
      { number: 2, firstVideoPresentationTime: 288288n },
    ];

    assert.deepEqual(mismatches(grid, hls), [
      { number: 1, grouping: 144144n, hls: 145145n },
      { number: 2, grouping: null, hls: 288288n },
    ]);
  });
});

describe('recording facts', () => {
  const keyframeRun = (decodeTime) =>
    fragment({
      trackId: VIDEO.trackId,
      decodeTime,
      runs: [{ samples: [{ duration: 1001, size: 1, flags: NON_SYNC }, { duration: 1001, size: 1, flags: SYNC }] }],
    });

  it('describes a stream copy whose keyframes are the source keyframes moved to media time', () => {
    const stream = readStream(
      Buffer.concat([initialization(), videoFragment({ decodeTime: 0, sync: true }), videoFragment({ decodeTime: 48048, sync: true })]),
    );
    const source = (keyframes) => ({ start: seconds('0.5'), keyframes: keyframes.map(seconds) });

    assert.equal(checkSourceKeyframes({ mode: 'encode', stream, source: source([]), timescale: 24000 }), null);
    assert.deepEqual(checkSourceKeyframes({ mode: 'copy', stream, source: source(['-0.5', '0.5', '2.502']), timescale: 24000 }), {
      recordedKeyframesEqualSourceKeyframes: true,
      keyframePresentationTimes: [0n, 48048n],
    });
    assert.equal(
      checkSourceKeyframes({ mode: 'copy', stream, source: source(['0.5', '2.5']), timescale: 24000 }).recordedKeyframesEqualSourceKeyframes,
      false,
    );
  });

  it('counts the fragment kinds, their sizes and how far a start plus durations misses the next start', () => {
    const stream = readStream(
      Buffer.concat([
        initialization(),
        audioFragment({ decodeTime: 0 }),
        videoFragment({ decodeTime: 0, sync: true }),
        videoFragment({ decodeTime: 1001, sync: false, size: 3 }),
        videoFragment({ decodeTime: 3003, sync: true }),
        audioFragment({ decodeTime: 18446744073709550592n }),
      ]),
    );

    assert.deepEqual(diagnostics(stream), {
      fragmentCount: 5,
      syncFirstFragments: 2,
      nonSyncFirstFragments: 1,
      fragmentsWithoutVideo: 2,
      leadingFragmentsWithoutVideo: 1,
      everyKeyframeStartsAFragment: true,
      videoSamples: 3,
      largestFragmentByteLength: stream.fragments[2].byteLength,
      firstVideoPresentationTime: 0n,
      firstAudioBaseMediaDecodeTimeUnsigned: 0n,
      startPlusDurationsMissesNextStartByTicks: { min: 0n, max: 1001n },
    });
  });

  it('reports a keyframe that does not start its fragment', () => {
    const stream = readStream(
      Buffer.concat([initialization(), keyframeRun(0), keyframeRun(2002)]),
    );

    assert.equal(diagnostics(stream).everyKeyframeStartsAFragment, false);
  });

  it('describes a segment by its fragments, keyframes and bytes', () => {
    const stream = readStream(
      Buffer.concat([initialization(), videoFragment({ decodeTime: 0, sync: true }), videoFragment({ decodeTime: 1001, sync: false }), audioFragment({ decodeTime: 0 })]),
    );
    const [segment] = group(stream, 6, 0).delivered;

    assert.deepEqual(describeSegment(segment, stream), {
      number: 0,
      firstVideoPresentationTime: 0n,
      firstFragmentIndex: 0,
      fragmentCount: 3,
      syncFirstFragmentCount: 1,
      byteLength: stream.fragments.reduce((sum, fragment) => sum + fragment.byteLength, 0),
    });
  });
});

/** Writes an HLS output of the recording whose segment n starts at fragment cuts[n]. */
function hlsOutput(cuts) {
  const bytes = readFileSync(RECORDING);
  const stream = readStream(bytes);
  const folder = mkdtempSync(join(tmpdir(), 'hls-'));
  writeFileSync(join(folder, 'init.mp4'), bytes.subarray(0, stream.initByteLength));
  const ends = [...cuts.slice(1), stream.fragments.length];
  const lines = ['#EXTM3U', '#EXT-X-MEDIA-SEQUENCE:0'];
  cuts.forEach((cut, index) => {
    const last = stream.fragments[ends[index] - 1];
    const segment = bytes.subarray(stream.fragments[cut].offset, last.offset + last.byteLength);
    writeFileSync(join(folder, `segment${index}.m4s`), segment);
    lines.push('#EXTINF:6.0,', `segment${index}.m4s`);
  });
  writeFileSync(join(folder, 'stream.m3u8'), `${lines.join('\n')}\n`);
  writeFileSync(join(folder, 'exit-status'), '0\n');
  return folder;
}

describe('HLS oracle', () => {
  const stream = readStream(readFileSync(RECORDING));
  const reference = { name: '07-copy-start0', stream, videoTimescale: 24000 };
  const grid = group(stream, 6, 0);
  const grouped = grid.delivered.map((segment) => describeSegment(segment, stream));
  const gridCuts = grid.delivered.map((segment) => segment.fragments[0]);
  const keyframeAt = (ticks) =>
    stream.fragments.findIndex((fragment) => videoStart(fragment)?.firstPresentationTime === ticks);
  const spec = { run: 'run', flags: 'pipe-recipe', audio: false, reference: null, restrict: false, expect: true, samePackets: true };
  const evaluate = (folder, overrides = {}) =>
    evaluateOracle({
      fixture: { name: reference.name, start: 0 },
      spec,
      reference,
      grouped,
      source: { start: new Rational(0n) },
      hls: readHls(folder),
      period: 6,
      ...overrides,
    });

  it('agrees with the grid when the HLS muxer cut where the grid does', () => {
    const oracle = evaluate(hlsOutput(gridCuts));

    assert.equal(oracle.agrees, true);
    assert.deepEqual(oracle.mismatches, []);
    assert.deepEqual(oracle.frameIdentity, {
      hlsVideoSamples: 1583,
      pipeVideoSamples: 1583,
      identicalPackets: 1583,
      sharesVideoArguments: true,
    });
    assert.equal(oracle.hlsOwnTimestampsMatchPipe, true);
    assert.equal(oracle.hlsencModel.reproducesHlsCuts, true);
    assert.deepEqual(oracle.hlsencModel.referenceOnZeroBasedTimelineSeconds, new Decimal(0));
    assert.equal(oracle.hlsExitStatus, 0);
  });

  it('reports the segment the HLS muxer started one keyframe later', () => {
    const cuts = [...gridCuts];
    cuts[1] = keyframeAt(192192n);
    const oracle = evaluate(hlsOutput(cuts));

    assert.deepEqual(oracle.mismatches, [{ number: 1, grouping: 144144n, hls: 192192n }]);
    assert.equal(oracle.agrees, false);
    assert.equal(oracle.hlsencModel.reproducesHlsCuts, false);
  });

  it('takes the first audio packet as the reference when its decode time precedes the video', () => {
    const oracle = evaluate(hlsOutput(gridCuts), { spec: { ...spec, audio: true } });

    assert.equal(
      oracle.hlsencModel.reference,
      "first audio packet: pts -1024 at 1/48000 (-0.021333 s), read by hlsenc at the video's 1/24000",
    );
    assert.deepEqual(oracle.hlsencModel.referenceOnZeroBasedTimelineSeconds, new Decimal(-0.042667));
    assert.equal(oracle.streams, 'video and audio');
  });

  it('moves the HLS recipe run by the source start and compares only the attempt numbers', () => {
    const oracle = evaluate(hlsOutput(gridCuts), {
      fixture: { name: reference.name, start: 7 },
      spec: { ...spec, flags: 'hls-recipe', restrict: true },
      source: { start: seconds('0.5') },
    });

    assert.deepEqual(
      oracle.segments.map((segment) => segment.number),
      [7, 8, 9, 10],
    );
    assert.equal(oracle.hlsOwnTimestampsMatchPipe, false);
    assert.match(oracle.flags, /no -start_at_zero/);
  });

  it('counts a packet whose bytes differ at the same size as not identical', () => {
    const folder = hlsOutput(gridCuts);
    const path = join(folder, 'segment3.m4s');
    const init = readFileSync(join(folder, 'init.mp4'));
    const segment = readFileSync(path);
    const [firstVideoSample] = videoSamples(readStream(Buffer.concat([init, segment])));
    segment[firstVideoSample.offset - init.length] ^= 0xff;
    writeFileSync(path, segment);
    const oracle = evaluate(folder, { spec: { ...spec, samePackets: false } });

    assert.deepEqual(oracle.frameIdentity, {
      hlsVideoSamples: 1583,
      pipeVideoSamples: 1583,
      identicalPackets: 1582,
      sharesVideoArguments: false,
    });
    assert.equal(oracle.agrees, true);
  });

  it('refuses an HLS run that did not encode the same number of frames', () => {
    const folder = hlsOutput(gridCuts);
    writeFileSync(join(folder, 'stream.m3u8'), '#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\nsegment0.m4s\n');

    assert.throws(() => evaluate(folder), /not the same frames as 07-copy-start0/);
  });
});

describe('violated claims', () => {
  it('names every claim the recordings contradict', () => {
    const expected = {
      fixtures: [
        {
          name: 'f',
          hlsOracles: [
            {
              hlsRun: 'r',
              agrees: false,
              expectedToAgree: true,
              frameIdentity: { hlsVideoSamples: 3, identicalPackets: 2, sharesVideoArguments: true },
              hlsencModel: { reproducesHlsCuts: false },
            },
            {
              hlsRun: 's',
              agrees: true,
              expectedToAgree: true,
              frameIdentity: { hlsVideoSamples: 3, identicalPackets: 3, sharesVideoArguments: false },
              hlsencModel: { reproducesHlsCuts: true },
            },
            {
              hlsRun: 't',
              agrees: true,
              expectedToAgree: true,
              frameIdentity: { hlsVideoSamples: 3, identicalPackets: 0, sharesVideoArguments: false },
              hlsencModel: { reproducesHlsCuts: true },
            },
          ],
          sourceKeyframeCheck: { recordedKeyframesEqualSourceKeyframes: false },
          diagnostics: { everyKeyframeStartsAFragment: false },
        },
      ],
      initializationSegmentIdentityPairs: [{ label: 'same', identical: false }],
      initializationSegmentDifferencePairs: [{ label: 'different', identical: true }],
      adrSideClaims: {
        mpegTsAacCopyWithoutAdtstoasc: { exitStatus: 0 },
        adtstoascLeavesMp4SourceCopyByteIdentical: false,
        maxDelayLeavesMp4OutputByteIdentical: false,
        seekWithFpsModeCfr: { firstVideoPresentationTime: 720720n, videoSamples: 863, videoSamplesWithoutFpsMode: 863 },
      },
    };

    assert.deepEqual(violatedClaims(expected), [
      'f / r: agrees=False, expected True',
      'f / r: 1 of 3 video packets differ from the pipe recording, whose video arguments it shares',
      'f / r: the hlsenc model does not reproduce the HLS cuts',
      "f / s: every video packet equals the pipe recording's, whose video arguments it does not share",
      "f: a recorded keyframe is not the source's own keyframe",
      'f: a keyframe does not start a fragment',
      'initialization segments differ: same',
      'initialization segments are identical: different',
      'an MPEG-TS AAC copy without aac_adtstoasc no longer fails',
      'aac_adtstoasc changes a copy from an MP4 source',
      '-max_delay changes mp4 output',
      'an explicit -fps_mode cfr after a seek no longer pads from zero',
    ]);
  });
});

describe('source files', () => {
  it('reads the source probe and keyframe listing of a recording run', () => {
    const work = mkdtempSync(join(tmpdir(), 'work-'));
    mkdirSync(join(work, 'src'));
    const probed = { ...probe('11.978667', '1/90000') };
    probed.streams[1].r_frame_rate = '24000/1001';
    writeFileSync(join(work, 'src', 'late.ts.probe.json'), JSON.stringify(probed));
    writeFileSync(join(work, 'src', 'late.ts.keyframes.csv'), '1078080,K__\n1258260,K__\n');
    const source = loadSource(work, 'late.ts');

    assert.equal(String(source.start), '4492/375');
    assert.equal(source.startMicros, 11978667n);
    assert.equal(source.video.r_frame_rate, '24000/1001');
    assert.deepEqual(
      source.keyframes.map((keyframe) => String(keyframe.minus(source.start))),
      ['0', '1001/500'],
    );
  });
});
