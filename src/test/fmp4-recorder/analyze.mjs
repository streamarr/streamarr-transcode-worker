#!/usr/bin/env node
// Derives expected.json from the recorded pipe streams and the HLS muxer runs over the same sources.
//
// Independent of the worker's Java code:
//   * fmp4.mjs reads the boxes of every recording;
//   * grid.mjs applies ADR 0037's grouping rules to the pipe stream;
//   * each HLS muxer run is read the same way, and each HLS segment's first video sample is mapped
//     to the pipe recording by its ordinal in decode order. Where the runs share their video
//     arguments (every copy, and every run with the pipe recipe's keyframe arguments), every packet
//     must be byte-identical (size and SHA-256), which proves the ordinal names the same frame; the
//     HLS recipe's own encodes use other keyframe arguments, so there only the packet count is
//     checked (see compareHlsRun). Converting the HLS files' own timestamps is not reliable: with
//     frag_discont the mp4 muxer rebases the first fragment on pts 0 and snaps every later
//     fragment's dts to the running duration sum, so HLS segment timestamps drift from the
//     source's on the variable-frame-rate copy;
//   * analysis.mjs's hlsencCuts() models hlsenc.c's own cut rule (FFmpeg 8.1, lines 2440-2489) with
//     its actual reference point, to show that every disagreement between the grid and the HLS
//     muxer comes from where hlsenc measures from, not from the grouping.
//
//   node analyze.mjs --work WORK --out FIXTURES_DIR --image WORKER_IMAGE

import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import {
  checkSourceKeyframes,
  compareHlsRun,
  initializationSegmentPair,
  loadSource,
  readHls,
  recordingFacts,
  violatedClaims,
} from './analysis.mjs';
import { readFiles, videoSamples, videoTrackOf } from './fmp4.mjs';
import { formatJson } from './json.mjs';

const PERIOD = 6;
const FRAGMENTATION_TARGET_MICROS = 1_000_000;

/**
 * flags: "hls-recipe" (source timestamps, no -start_at_zero) or "pipe-recipe" (-start_at_zero).
 * reference: the pipe recording that decodes the same frames as the HLS run (default: the fixture).
 * restrict: compare only segment numbers >= the fixture's startSequenceNumber.
 */
function hlsComparison(run, flags, { audio = true, reference = null, restrict = false, expect = true } = {}) {
  return { run, flags, audio, reference, restrict, expect };
}

// How a fixture deliberately departs from the worker's recipe; a fixture without one follows it,
// fixture-only additions aside, and FfmpegCommandBuilderRecipeTest pins the worker to it.
const FIRST_30_SECONDS = 'only the first 30 s of the source';
const MISSED_FORCED_KEYFRAME = 'the forced keyframe for 18 s suppressed';
const X265_WITH_FIXTURE_PARAMS = 'with -x265-params that keep it single-threaded and quiet';
const X265_UNDER_THE_VERIFIED_GOP =
  `libx265, not verified, under the verified GOP of 145 frames, ${X265_WITH_FIXTURE_PARAMS}`;
const X265_UNDER_THE_FLOORED_GOP =
  `libx265 under the floored GOP of 143 frames it gets while unverified, ${X265_WITH_FIXTURE_PARAMS}`;

const FIXTURES = [
  {
    name: '01-encode-cfr', source: 'cfr.mp4', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    proves: 'Constant 23.976 fps libx264 encode of 66 s under the verified-encoder GOP of ceil(6 x 23.976) + 1 = ' +
      '145 frames: every forced keyframe (frames 0, 144, 288, ..., 1007, 1151, ...) opens a segment and the ' +
      'frame-count GOP never fires, so every segment holds exactly one keyframe-first fragment.',
    hlsComparisons: [
      hlsComparison('01-encode-cfr.hls-recipe', 'hls-recipe'),
      hlsComparison('01-encode-cfr.video-only', 'pipe-recipe', { audio: false }),
      hlsComparison('01-encode-cfr.pipe-keyframes-with-audio', 'pipe-recipe'),
    ],
  },
  {
    name: '01-encode-cfr-seek30', source: 'cfr.mp4', mode: 'encode', encoder: 'libx264', seek: 30, start: 5,
    proves: 'An encoded seek (-ss 30) under -r without -fps_mode starts at the seek point (30.030 s, ' +
      'segment 5), pads nothing from zero, and has no preroll. force_key_frames measures t from the attempt\'s ' +
      'first frame, so from segment 7 on its forced keyframes sit one frame after the start-0 recording\'s ' +
      '(42.042 s against 42.0003 s), inside the same intervals.',
    hlsComparisons: [
      hlsComparison('01-encode-cfr-seek30.hls-recipe', 'hls-recipe'),
      hlsComparison('01-encode-cfr-seek30.video-only', 'pipe-recipe', { audio: false }),
      hlsComparison('01-encode-cfr.hls-recipe', 'hls-recipe', { reference: '01-encode-cfr', restrict: true, expect: false }),
    ],
  },
  {
    name: '02-copy-irregular-keyframes', source: 'irregular.mp4', mode: 'copy', encoder: null, seek: 0, start: 0,
    proves: 'Stream copy of a 24 fps source with keyframes only at 0, 6.5, 12, 18, 24.5, 30, 36.5, 42, ' +
      '48, 54.5 and 60 s over 66 s (12, 18, 30, 42, 48 and 60 s sit exactly on a boundary and open that ' +
      "boundary's segment): 11 segments, one keyframe each.",
    hlsComparisons: [
      hlsComparison('02-copy-irregular-keyframes.hls-recipe', 'hls-recipe'),
      hlsComparison('02-copy-irregular-keyframes.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '03-encode-vfr', source: 'vfr.mp4', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    proves: 'Variable-frame-rate source (avg 16.2 fps on a 23.976 grid) encoded with libx264 under ' +
      '-r 23.976 and no -fps_mode: constant-rate output starting at the first source frame (41.7 ms), ' +
      'one keyframe in every interval.',
    hlsComparisons: [
      hlsComparison('03-encode-vfr.hls-recipe', 'hls-recipe'),
      hlsComparison('03-encode-vfr.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '04-copy-vfr-bframes', source: 'vfr.mp4', mode: 'copy', encoder: null, seek: 0, start: 0,
    proves: 'Variable-frame-rate stream copy with B-frames and one or two keyframes per interval: ' +
      'segments open only at a sync-first fragment inside a new interval, whatever the frame spacing.',
    hlsComparisons: [
      hlsComparison('04-copy-vfr-bframes.hls-recipe', 'hls-recipe', { expect: false }),
      hlsComparison('04-copy-vfr-bframes.video-only', 'pipe-recipe', { audio: false, expect: false }),
    ],
  },
  {
    name: '05-encode-late-start', source: 'late.ts', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    proves: 'MPEG-TS source whose timestamps begin at 12 s: -start_at_zero puts media time zero at the ' +
      'container start (the AAC priming frame, 21.3 ms before the first video frame), so the first ' +
      'fragment is segment 0, not segment 2.',
    hlsComparisons: [
      hlsComparison('05-encode-late-start.hls-recipe', 'hls-recipe'),
      hlsComparison('05-encode-late-start.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '05-copy-late-start', source: 'late.ts', mode: 'copy', encoder: null, seek: 0, start: 0,
    proves: 'Stream copy of the same 12 s-origin MPEG-TS source with -bsf:a aac_adtstoasc: the first ' +
      "fragment is segment 0 and every keyframe's media time is its source timestamp minus the container start.",
    hlsComparisons: [
      hlsComparison('05-copy-late-start.hls-recipe-adtstoasc', 'hls-recipe'),
      hlsComparison('05-copy-late-start.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '06-encode-audio-tail', source: 'tail.mp4', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    proves: 'Audio outlasts video by 3.5 s: the recording ends in audio-only fragments (no video traf), which ' +
      'join the last open segment; none precedes the first segment.',
    hlsComparisons: [
      hlsComparison('06-encode-audio-tail.hls-recipe', 'hls-recipe'),
      hlsComparison('06-encode-audio-tail.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '07-copy-start0', source: 'cfr.mp4', mode: 'copy', encoder: null, seek: 0, start: 0,
    proves: 'Stream copy of a 2.002 s-GOP 23.976 fps source from the start: three keyframes per segment.',
    hlsComparisons: [
      hlsComparison('07-copy-start0.hls-recipe', 'hls-recipe'),
      hlsComparison('07-copy-start0.video-only', 'pipe-recipe', { audio: false }),
    ],
  },
  {
    name: '07-copy-seek30', source: 'cfr.mp4', mode: 'copy', encoder: null, seek: 30, start: 5,
    proves: 'Stream-copy replacement attempt at -ss 30 (start sequence number 5): the seek lands on the keyframe at ' +
      '28.028 s (segment 4), which is preroll and is discarded with the non-sync fragment after it; ' +
      "segments 5 to 10 carry the start-0 recording's video samples at the same ticks (audio packets regroup by one AAC frame).",
    hlsComparisons: [
      hlsComparison('07-copy-start0.hls-recipe', 'hls-recipe', { reference: '07-copy-start0', restrict: true }),
      hlsComparison('07-copy-seek30.hls-recipe', 'hls-recipe', { expect: false }),
    ],
  },
  {
    name: '09-svtav1-vfr', source: 'vfr.mp4', mode: 'encode', encoder: 'libsvtav1', seek: 0, start: 0,
    proves: 'Irregular variable-frame-rate source through SVT-AV1 with -r 23.976, the verified-encoder GOP of ' +
      '145 frames and time-based forced keyframes: one keyframe in every interval, on the same frames libx264 ' +
      'chose in 03.',
    hlsComparisons: [
      hlsComparison('09-svtav1-vfr.video-only', 'pipe-recipe', { audio: false }),
      hlsComparison('09-svtav1-vfr.hls-recipe', 'hls-recipe', { expect: false }),
      hlsComparison('09-svtav1-vfr.pipe-keyframes-with-audio', 'pipe-recipe', { expect: true }),
    ],
  },
  {
    name: '09-svtav1-vfr-seek30', source: 'vfr.mp4', mode: 'encode', encoder: 'libsvtav1', seek: 30, start: 5,
    proves: 'The same SVT-AV1 recipe after -ss 30: starts at the first source frame after the seek point ' +
      '(30.072 s), pads nothing from zero, one keyframe in every interval.',
    hlsComparisons: [
      hlsComparison('09-svtav1-vfr-seek30.video-only', 'pipe-recipe', { audio: false }),
      hlsComparison('09-svtav1-vfr-seek30.hls-recipe', 'hls-recipe'),
    ],
  },
  {
    name: '10-copy-gop-exceeds-period', source: 'gop10.mp4', mode: 'copy', encoder: null, seek: 0, start: 0,
    proves: 'Stream copy with a keyframe every 10.01 s: interval 2 holds no keyframe. The sync-first ' +
      'fragment at 20.02 s (segment 3) closes segment 1, which is delivered complete with segment 0, and ' +
      'then grouping fails with a skipped segment number; nothing from that fragment on is grouped.',
    hlsComparisons: [hlsComparison('10-copy-gop-exceeds-period.hls-recipe', 'hls-recipe', { expect: false })],
  },
  {
    name: '11-encode-cfr-floored-gop', source: 'cfr.mp4', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    recipeDeviation:
      `libx264 under the floored GOP of 143 frames, standing in for an encoder not verified; ${FIRST_30_SECONDS}`,
    proves: 'The first 30 s of 01 under the floored GOP of floor(6 x 23.976) = 143 frames that ADR 0037 keeps for ' +
      'an encoder not verified to honour forced keyframes: the GOP keyframe one frame before every later boundary ' +
      '(frames 143, 287, 431, 575) and on the last frame (719) is a 1-frame keyframe-first fragment that joins the ' +
      'earlier segment, so each segment holds two keyframes and still groups on the grid.',
    hlsComparisons: [hlsComparison('11-encode-cfr-floored-gop.video-only', 'pipe-recipe', { audio: false })],
  },
  {
    name: '12-encode-cfr-missed-forced-keyframe', source: 'cfr.mp4', mode: 'encode', encoder: 'libx264', seek: 0, start: 0,
    recipeDeviation: `${MISSED_FORCED_KEYFRAME}; ${FIRST_30_SECONDS}`,
    proves: 'libx264 over the first 30 s under the verified GOP of 145 frames with the forced keyframe for 18 s ' +
      'suppressed: the GOP count restarts at the forced keyframe at 12.012 s (frame 288), so the backstop keyframe ' +
      'lands 145 frames later at 18.060 s (frame 433), inside interval 3, and no segment number is skipped.',
    hlsComparisons: [hlsComparison('12-encode-cfr-missed-forced-keyframe.video-only', 'pipe-recipe', { audio: false })],
  },
  {
    name: '12-svtav1-cfr-missed-forced-keyframe', source: 'cfr.mp4', mode: 'encode', encoder: 'libsvtav1', seek: 0, start: 0,
    recipeDeviation: `${MISSED_FORCED_KEYFRAME}; ${FIRST_30_SECONDS}`,
    proves: 'The same suppressed forced keyframe through SVT-AV1: its GOP count also restarts at the forced keyframe ' +
      'at frame 288, and the backstop keyframe lands at frame 433 (18.060 s), inside interval 3.',
    hlsComparisons: [hlsComparison('12-svtav1-cfr-missed-forced-keyframe.video-only', 'pipe-recipe', { audio: false })],
  },
  {
    name: '13-x265-cfr', source: 'cfr.mp4', mode: 'encode', encoder: 'libx265', seek: 0, start: 0,
    recipeDeviation: `${X265_UNDER_THE_VERIFIED_GOP}; ${FIRST_30_SECONDS}`,
    proves: 'libx265 with the worker\'s arguments (open GOP by default) under the 145-frame GOP: it honours every ' +
      'time-based forced keyframe (frames 144, 288, 432, 576), but despite -forced-idr 1 each one is a CRA ' +
      '(NAL type 21); only frame 0 is an IDR (type 20). No RASL picture follows them here.',
    hlsComparisons: [hlsComparison('13-x265-cfr.video-only', 'pipe-recipe', { audio: false })],
  },
  {
    name: '13-x265-cfr-missed-forced-keyframe', source: 'cfr.mp4', mode: 'encode', encoder: 'libx265', seek: 0, start: 0,
    recipeDeviation: `${X265_UNDER_THE_VERIFIED_GOP}; ${MISSED_FORCED_KEYFRAME}; ${FIRST_30_SECONDS}`,
    proves: 'libx265 with the forced keyframe for 18 s suppressed: its GOP count restarts at the forced keyframe at ' +
      'frame 288 and the backstop lands at frame 433 (18.060 s), inside interval 3, but as a CRA whose RASL ' +
      'picture (frame 432) references the previous GOP, so the segment it opens cannot be decoded on its own.',
    hlsComparisons: [hlsComparison('13-x265-cfr-missed-forced-keyframe.video-only', 'pipe-recipe', { audio: false })],
  },
  {
    name: '13-x265-cfr-floored-gop', source: 'cfr.mp4', mode: 'encode', encoder: 'libx265', seek: 0, start: 0,
    recipeDeviation: `${X265_UNDER_THE_FLOORED_GOP}; ${FIRST_30_SECONDS}`,
    proves: 'libx265 with the worker\'s arguments under the floored GOP of 143 frames that it gets while unverified: ' +
      'as in 11, the GOP keyframe one frame before every later boundary (frames 143, 287, 431, 575) and on the last ' +
      'frame (719) joins the earlier segment, so each of the 5 segments holds two keyframes and opens at its forced ' +
      'keyframe. Every keyframe after frame 0 is a CRA; the GOP keyframes at frames 143 and 287 lead two RASL ' +
      'pictures each inside the earlier segment, and no RASL picture follows a forced keyframe that opens a segment.',
    hlsComparisons: [hlsComparison('13-x265-cfr-floored-gop.video-only', 'pipe-recipe', { audio: false })],
  },
];

const INITIALIZATION_SEGMENT_IDENTITY_PAIRS = [
  ['encode (libx264), start 0 vs -ss 30', '01-encode-cfr', '01-encode-cfr-seek30'],
  ['stream copy, start 0 vs -ss 30', '07-copy-start0', '07-copy-seek30'],
  ['encode (libsvtav1), start 0 vs -ss 30', '09-svtav1-vfr', '09-svtav1-vfr-seek30'],
];
const INITIALIZATION_SEGMENT_DIFFERENCE_PAIRS = [
  ['encode vs stream copy of the same source', '01-encode-cfr', '07-copy-start0'],
];

const RECIPE =
  'ffmpeg -y [-ss S] -i SRC -map 0:v:0 -map 0:a:0 -map -0:s -map_metadata -1 -map_chapters -1 -copyts ' +
  '-avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128 <codec args> [-bsf:a aac_adtstoasc ' +
  'when copying AAC] [encode: -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 expr:gte(t,n_forced*6) ' +
  '(-sc_threshold:v:0 0 for libx264) -g:v:0 145 = ceil(6 x 23.976) + 1 for an encoder verified to honour forced ' +
  'keyframes (libx264, SVT-AV1), else 143 = floor(6 x 23.976) (fixtures 11 and 13c) (-keyint_min:v:0 with the ' +
  'same value for SVT-AV1); fixtures 13 and 13b give libx265, not verified, 145 to test whether it qualifies] ' +
  '[fixtures 11 to 13 only: -t 30] -threads 1 -f mp4 -movflags ' +
  'cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont -frag_duration 1000000 pipe:1';

const RULES =
  'ADR 0037: segment N = [N*P, (N+1)*P) on the zero-based timeline. A fragment whose video traf starts ' +
  'with a sync sample opens segment floor(presentationTime / (P * timescale)), or joins it when that segment is ' +
  'already open; every other fragment joins the open segment (or waits for the first one). Segments numbered ' +
  'below startSequenceNumber are discarded preroll; a sync-first fragment beyond the next deliverable number ' +
  'closes the open segment (delivered, or discarded when preroll) and then fails with a skipped segment ' +
  'number, grouping nothing from that fragment on. firstVideoPresentationTime = tfdt + first sample\'s composition offset, ' +
  'in videoTimescale ticks, no edit list.';

function fixtureRecord({ fixture, record, source, pipe, work }) {
  const stream = record.stream;
  if (fixture.mode === 'encode' && source.video.r_frame_rate !== '24000/1001') {
    throw new Error(`${fixture.source} probes as ${source.video.r_frame_rate}, the script passes 24000/1001`);
  }
  const { videoTrackId, videoTimescale, diagnostics, ...facts } = recordingFacts(stream, {
    period: PERIOD,
    startSequenceNumber: fixture.start,
  });
  const comparisons = fixture.hlsComparisons.map((spec) =>
    compareHlsRun({
      fixture,
      // A copy passes the source's packets through, and a pipe-recipe run encodes with the
      // recording's own video arguments; the HLS recipe's encodes use other keyframe arguments.
      spec: { ...spec, samePackets: fixture.mode === 'copy' || spec.flags === 'pipe-recipe' },
      reference: pipe.get(spec.reference ?? fixture.name),
      grouped: facts.segments,
      source,
      hls: readHls(join(work, 'hls', spec.run)),
      period: PERIOD,
    }),
  );
  return {
    name: fixture.name,
    file: `${fixture.name}.fmp4`,
    proves: fixture.proves,
    recipeDeviation: fixture.recipeDeviation ?? null,
    mode: fixture.mode,
    encoder: fixture.encoder,
    source: {
      file: fixture.source,
      containerStartTimeMicros: source.startMicros,
      videoRealFrameRate: source.video.r_frame_rate,
      videoAverageFrameRate: source.video.avg_frame_rate,
    },
    seekSeconds: fixture.seek,
    period: PERIOD,
    fragmentationTargetMicros: FRAGMENTATION_TARGET_MICROS,
    ffmpegArguments: record.ffmpegArguments,
    startSequenceNumber: fixture.start,
    videoTrackId,
    videoTimescale,
    ...facts,
    sourceKeyframeCheck: checkSourceKeyframes({ mode: fixture.mode, stream, source, timescale: videoTimescale }),
    diagnostics,
    hlsComparisons: comparisons,
  };
}

function initializationSegmentPairOf(pipe, [label, first, second]) {
  return initializationSegmentPair(label, [first, pipe.get(first).stream], [second, pipe.get(second).stream]);
}

function sideClaims(work, pipe) {
  const folder = join(work, 'claims');
  const claim = (name) => {
    const log = readFileSync(join(folder, `${name}.log`), 'utf8')
      .split('\n')
      .map((line) => line.trim().replace(/ @ 0x[0-9a-f]+/g, ''))
      .filter((line) => line !== '');
    const path = join(folder, `${name}.fmp4`);
    return {
      status: Number(readFileSync(join(folder, `${name}.exit-status`), 'utf8').trim()),
      firstLogLine: log[0] ?? '',
      data: readFileSync(path),
      path,
    };
  };
  const recorded = (name) => readFileSync(pipe.get(name).path);
  const ts = claim('ts-copy-without-adtstoasc');
  const cfr = claim('encode-seek30-fps-mode-cfr');
  const cfrSamples = videoSamples(readFiles([cfr.path]));
  const seekSamples = videoSamples(pipe.get('01-encode-cfr-seek30').stream);
  return {
    mpegTsAacCopyWithoutAdtstoasc: { exitStatus: ts.status, firstLogLine: ts.firstLogLine },
    adtstoascLeavesMp4SourceCopyByteIdentical: claim('mp4-copy-without-adtstoasc').data.equals(recorded('07-copy-start0')),
    maxDelayLeavesMp4OutputByteIdentical: claim('encode-with-max-delay').data.equals(recorded('01-encode-cfr')),
    seekWithFpsModeCfr: {
      exitStatus: cfr.status,
      videoSamples: cfrSamples.length,
      firstVideoPresentationTime: cfrSamples[0].presentationTime,
      videoSamplesWithoutFpsMode: seekSamples.length,
      firstVideoPresentationTimeWithoutFpsMode: seekSamples[0].presentationTime,
    },
  };
}

function main() {
  const { values: args } = parseArgs({
    options: { work: { type: 'string' }, out: { type: 'string' }, image: { type: 'string' } },
  });
  for (const option of ['work', 'out', 'image']) {
    if (args[option] === undefined) {
      throw new Error(`--${option} is required`);
    }
  }

  const ffmpeg = readFileSync(join(args.work, 'ffmpeg-version.txt'), 'utf8').trim();
  const pipe = new Map();
  for (const fixture of FIXTURES) {
    const path = join(args.work, 'out', `${fixture.name}.fmp4`);
    const stream = readFiles([path]);
    const ffmpegArguments = readFileSync(join(args.work, 'out', `${fixture.name}.args`), 'utf8')
      .split('\n')
      .slice(0, -1);
    const videoTrack = videoTrackOf(stream);
    pipe.set(fixture.name, {
      name: fixture.name,
      stream,
      videoTimescale: videoTrack.timescale,
      videoTrackId: videoTrack.trackId,
      ffmpegArguments,
      path,
    });
  }

  const results = FIXTURES.map((fixture) =>
    fixtureRecord({
      fixture,
      record: pipe.get(fixture.name),
      source: loadSource(args.work, fixture.source),
      pipe,
      work: args.work,
    }),
  );
  const expected = {
    recordedWith: { image: args.image, ffmpeg },
    recipe: RECIPE,
    rules: RULES,
    fixtures: results,
    initializationSegmentIdentityPairs: INITIALIZATION_SEGMENT_IDENTITY_PAIRS.map((pair) => initializationSegmentPairOf(pipe, pair)),
    initializationSegmentDifferencePairs: INITIALIZATION_SEGMENT_DIFFERENCE_PAIRS.map((pair) =>
      initializationSegmentPairOf(pipe, pair),
    ),
    adrSideClaims: sideClaims(args.work, pipe),
  };
  for (const fixture of FIXTURES) {
    copyFileSync(pipe.get(fixture.name).path, join(args.out, `${fixture.name}.fmp4`));
  }
  writeFileSync(join(args.out, 'expected.json'), `${formatJson(expected)}\n`);
  const violations = violatedClaims(expected);
  for (const violation of violations) {
    process.stderr.write(`VIOLATED ${violation}\n`);
  }
  if (violations.length > 0) {
    process.exitCode = 1;
  }
}

main();
