// What expected.json records about one recording, derived from the recorded boxes alone, and the
// comparison of a recording with the HLS muxer's run over the same source.

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { readFiles, signed, videoSamples, videoStart } from './fmp4.mjs';
import { groupOnGrid } from './grid.mjs';
import { Decimal } from './json.mjs';
import { Rational } from './rational.mjs';

/** The grid model's view of each fragment: its first video sample, when it has one. */
export function gridFragments(stream) {
  return stream.fragments.map((fragment) => {
    const traf = videoStart(fragment);
    if (traf === null) {
      return { video: null };
    }
    return {
      video: { presentationTime: traf.firstPresentationTime, timescale: traf.timescale, sync: traf.firstSync },
    };
  });
}

export function group(stream, period, startSequenceNumber) {
  return groupOnGrid(gridFragments(stream), { period, startSequenceNumber });
}

export function describe(segment, stream) {
  const fragments = segment.fragments.map((index) => stream.fragments[index]);
  return {
    number: segment.number,
    firstVideoPresentationTime: segment.firstVideoPresentationTime,
    firstFragmentIndex: segment.fragments[0],
    fragmentCount: fragments.length,
    syncFirstFragmentCount: fragments.filter((fragment) => videoStart(fragment)?.firstSync === true).length,
    byteLength: fragments.reduce((sum, fragment) => sum + fragment.byteLength, 0),
  };
}

/**
 * The media time -start_at_zero removes from the video stream: the container start in
 * microseconds, rescaled with rounding to the video time base (fftools ts_offset).
 */
export function sourceStart(probe) {
  const video = probe.streams.find((stream) => stream.codec_type === 'video');
  const [num, den] = video.time_base.split('/').map((part) => BigInt(part));
  const micros = Rational.parseDecimal(probe.format.start_time).times(1_000_000n);
  const ticks = micros.times(den).dividedBy(num * 1_000_000n).roundHalfEven();
  return { start: new Rational(ticks * num, den), startMicros: micros.truncate(), video, num, den };
}

/** Each "pts,flags" line of ffprobe's keyframe listing, as seconds. */
export function sourceKeyframes(csv, num, den) {
  return csv
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => new Rational(BigInt(line.split(',')[0]) * num, den));
}

export function loadSource(work, source) {
  const folder = join(work, 'src');
  const probe = JSON.parse(readFileSync(join(folder, `${source}.probe.json`), 'utf8'));
  const { start, startMicros, video, num, den } = sourceStart(probe);
  const csv = readFileSync(join(folder, `${source}.keyframes.csv`), 'utf8');
  return { start, startMicros, video, keyframes: sourceKeyframes(csv, num, den) };
}

/** The SHA-256 of one sample's bytes in the stream that holds it. */
export function packetDigest(stream, sample) {
  return createHash('sha256')
    .update(stream.data.subarray(sample.offset, sample.offset + sample.size))
    .digest('hex');
}

function withDigests(stream) {
  return videoSamples(stream).map((sample) => ({ ...sample, digest: packetDigest(stream, sample) }));
}

/** An HLS muxer run: its segments' first video samples and every video packet, in order. */
export function readHls(folder) {
  const playlist = readFileSync(join(folder, 'stream.m3u8'), 'utf8');
  const sequence = Number(/#EXT-X-MEDIA-SEQUENCE:(\d+)/.exec(playlist)[1]);
  const uris = playlist.split(/\r?\n/).filter((line) => line !== '' && !line.startsWith('#'));
  const init = join(folder, 'init.mp4');
  const tracks = [...readFiles([init]).tracks.values()];
  const videoTrack = tracks.find((track) => track.handler === 'vide');
  const audioTrack = tracks.find((track) => track.handler === 'soun') ?? null;
  const edit = videoTrack.edits.map(([, mediaTime]) => mediaTime).find((mediaTime) => mediaTime >= 0n) ?? 0n;
  const samples = [];
  const segments = [];
  let audioFirst = null;
  uris.forEach((uri, offset) => {
    const stream = readFiles([init, join(folder, uri)]);
    const video = stream.fragments.map(videoStart).find((traf) => traf !== null);
    if (audioFirst === null) {
      const audio = stream.fragments.flatMap((fragment) => fragment.trafs).find((traf) => traf.handler === 'soun');
      audioFirst = audio === undefined ? null : signed(audio.baseMediaDecodeTime);
    }
    segments.push({
      number: sequence + offset,
      ordinal: samples.length,
      ownPresentation: video.firstPresentationTime - edit,
    });
    samples.push(...withDigests(stream));
  });
  return {
    exitStatus: Number(readFileSync(join(folder, 'exit-status'), 'utf8').trim()),
    segments,
    samples,
    videoTimescale: videoTrack.timescale,
    edit,
    audioTimescale: audioTrack?.timescale ?? null,
    audioFirstRaw: audioFirst,
  };
}

/**
 * hlsenc.c's cut rule: the first packet opens segment 0 (number becomes 1); a later keyframe cuts
 * when (pts - start_pts) >= hls_time * number in the video time base, and number counts segments.
 * ptsRaw and reference are Rationals in seconds at the video time base.
 */
export function hlsencCuts(keyframeOrdinals, ptsRaw, reference, period) {
  const cuts = [0];
  let number = 1;
  let current = ptsRaw[0];
  for (const ordinal of keyframeOrdinals) {
    if (ordinal === 0 || ptsRaw[ordinal].minus(current).compare(0) <= 0) {
      continue;
    }
    if (ptsRaw[ordinal].minus(reference).compare(period * number) >= 0) {
      cuts.push(ordinal);
      number += 1;
      current = ptsRaw[ordinal];
    }
  }
  return cuts;
}

/** The segment numbers whose first video sample the grid and the HLS muxer place differently. */
export function mismatches(grouped, hls) {
  const ours = new Map(grouped.map((segment) => [segment.number, segment.firstVideoPresentationTime]));
  const theirs = new Map(hls.map((segment) => [segment.number, segment.firstVideoPresentationTime]));
  return [...new Set([...ours.keys(), ...theirs.keys()])]
    .sort((a, b) => a - b)
    .filter((number) => ours.get(number) !== theirs.get(number))
    .map((number) => ({ number, grouping: ours.get(number) ?? null, hls: theirs.get(number) ?? null }));
}

function hlsencReference({ spec, hls, ptsRaw, timescale }) {
  const videoFirst = ptsRaw[0];
  const videoFirstDts = videoFirst.minus(new Rational(hls.edit, timescale));
  if (spec.audio && hls.audioFirstRaw !== null) {
    const audioSeconds = new Rational(hls.audioFirstRaw, hls.audioTimescale);
    const misread = new Rational(hls.audioFirstRaw, timescale);
    if (audioSeconds.compare(videoFirstDts) < 0 && misread.compare(videoFirst) <= 0) {
      return {
        reference: misread,
        from:
          `first audio packet: pts ${hls.audioFirstRaw} at 1/${hls.audioTimescale} ` +
          `(${audioSeconds.toNumber().toFixed(6)} s), read by hlsenc at the video's 1/${timescale}`,
      };
    }
  }
  return { reference: videoFirst, from: 'first video packet' };
}

/**
 * Maps each HLS segment's first video sample onto the pipe recording by its ordinal in decode
 * order and compares the result with the grid, and models hlsenc's cuts from its own reference.
 *
 * The ordinal names the same frame in both runs only when both hold the same packets in the same
 * order. A run that copies the stream or encodes with the recording's own video arguments
 * (spec.samePackets) must hold byte-identical packets, and identicalPackets proves it packet by
 * packet (size and SHA-256). A run that encodes with other keyframe arguments cannot: there only
 * the packet count is checked, and the ordinal rests on both encoders receiving the same
 * constant-rate frames in the same order.
 */
export function evaluateOracle({ fixture, spec, reference, grouped, source, hls, period }) {
  const refSamples = withDigests(reference.stream);
  const timescale = reference.videoTimescale;
  const identity = {
    hlsVideoSamples: hls.samples.length,
    pipeVideoSamples: refSamples.length,
    identicalPackets: hls.samples.filter(
      (sample, index) => sample.size === refSamples[index]?.size && sample.digest === refSamples[index]?.digest,
    ).length,
    sharesVideoArguments: spec.samePackets,
  };
  if (hls.samples.length !== refSamples.length || hls.videoTimescale !== timescale) {
    throw new Error(`${spec.run}: not the same frames as ${reference.name}: ${JSON.stringify(identity)}`);
  }

  const offset = spec.flags === 'hls-recipe' ? source.start : new Rational(0n);
  const offsetTicks = offset.times(timescale);
  const converted = [];
  let directAgrees = true;
  for (const segment of hls.segments) {
    const pts = refSamples[segment.ordinal].presentationTime;
    directAgrees = directAgrees && new Rational(segment.ownPresentation).minus(offsetTicks).equals(pts);
    if (spec.restrict && segment.number < fixture.start) {
      continue;
    }
    converted.push({ number: segment.number, firstVideoPresentationTime: pts });
  }
  const found = mismatches(grouped, converted);

  const ptsRaw = refSamples.map((sample) => new Rational(sample.presentationTime, timescale).plus(offset));
  const { reference: start, from } = hlsencReference({ spec, hls, ptsRaw, timescale });
  const keyframes = hls.samples.flatMap((sample, index) => (sample.sync ? [index] : []));
  const modeled = hlsencCuts(keyframes, ptsRaw, start, period);
  const observed = hls.segments.map((segment) => segment.ordinal);
  return {
    hlsRun: spec.run,
    flags:
      spec.flags === 'hls-recipe'
        ? "the HLS recipe's common flags (no -start_at_zero, -max_delay)"
        : "the pipe recipe's common flags (-start_at_zero)",
    streams: spec.audio ? 'video and audio' : 'video only',
    pipeReference: `${reference.name}.fmp4`,
    hlsExitStatus: hls.exitStatus,
    frameIdentity: identity,
    segments: converted,
    agrees: found.length === 0,
    expectedToAgree: spec.expect,
    mismatches: found,
    hlsOwnTimestampsMatchPipe: directAgrees,
    hlsencModel: {
      reference: from,
      referenceOnZeroBasedTimelineSeconds: new Decimal(Number(start.minus(offset).toNumber().toFixed(6))),
      reproducesHlsCuts: modeled.length === observed.length && modeled.every((cut, index) => cut === observed[index]),
    },
  };
}

/** For a stream copy: whether every recorded keyframe is the source's own, moved to media time. */
export function checkSourceKeyframes({ mode, stream, source, timescale }) {
  if (mode !== 'copy') {
    return null;
  }
  const recorded = videoSamples(stream)
    .filter((sample) => sample.sync)
    .map((sample) => sample.presentationTime)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const expected = [
    ...new Set(source.keyframes.map((keyframe) => keyframe.minus(source.start).times(timescale).truncate())),
  ]
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .filter((keyframe) => keyframe >= recorded[0]);
  return {
    recordedKeyframesEqualSourceKeyframes:
      recorded.length === expected.length && recorded.every((keyframe, index) => keyframe === expected[index]),
    keyframePresentationTimes: recorded,
  };
}

function everyKeyframeStartsAFragment(stream) {
  const samples = videoSamples(stream);
  const withKeyframes = new Set(samples.filter((sample) => sample.sync).map((sample) => sample.fragmentIndex));
  return [...withKeyframes].every((index) => {
    const start = videoStart(stream.fragments[index]);
    const keyframes = samples.filter((sample) => sample.fragmentIndex === index && sample.sync).length;
    return start.firstSync && start.sampleCount > 0 && keyframes === 1;
  });
}

function startPlusDurationMisses(video) {
  const misses = [];
  let previous = null;
  for (const traf of video) {
    if (traf === null) {
      continue;
    }
    if (previous !== null) {
      misses.push(traf.firstPresentationTime - (previous.firstPresentationTime + previous.durationSum));
    }
    previous = traf;
  }
  return {
    min: misses.reduce((a, b) => (b < a ? b : a)),
    max: misses.reduce((a, b) => (b > a ? b : a)),
  };
}

export function diagnostics(stream) {
  const fragments = stream.fragments;
  const video = fragments.map(videoStart);
  const starts = video.filter((traf) => traf !== null).map((traf) => traf.firstPresentationTime);
  const audio = fragments.flatMap((fragment) => fragment.trafs).find((traf) => traf.handler === 'soun');
  return {
    fragmentCount: fragments.length,
    syncFirstFragments: video.filter((traf) => traf?.firstSync === true).length,
    nonSyncFirstFragments: video.filter((traf) => traf?.firstSync === false).length,
    fragmentsWithoutVideo: video.filter((traf) => traf === null).length,
    leadingFragmentsWithoutVideo: video.findIndex((traf) => traf !== null),
    everyKeyframeStartsAFragment: everyKeyframeStartsAFragment(stream),
    videoSamples: videoSamples(stream).length,
    largestFragmentByteLength: Math.max(...fragments.map((fragment) => fragment.byteLength)),
    firstVideoPresentationTime: starts[0],
    firstAudioBaseMediaDecodeTimeUnsigned: audio?.baseMediaDecodeTime ?? null,
    startPlusDurationsMissesNextStartByTicks: startPlusDurationMisses(video),
  };
}

function packetViolations({ hlsVideoSamples, identicalPackets, sharesVideoArguments }) {
  if (sharesVideoArguments && identicalPackets !== hlsVideoSamples) {
    return [
      `${hlsVideoSamples - identicalPackets} of ${hlsVideoSamples} video packets differ from the pipe recording, ` +
        'whose video arguments it shares',
    ];
  }
  if (!sharesVideoArguments && identicalPackets === hlsVideoSamples) {
    return ["every video packet equals the pipe recording's, whose video arguments it does not share"];
  }
  return [];
}

function oracleViolations(fixture) {
  return fixture.hlsOracles.flatMap((oracle) =>
    [
      ...(oracle.agrees === oracle.expectedToAgree
        ? []
        : [`agrees=${pythonBoolean(oracle.agrees)}, expected ${pythonBoolean(oracle.expectedToAgree)}`]),
      ...packetViolations(oracle.frameIdentity),
      ...(oracle.hlsencModel.reproducesHlsCuts ? [] : ['the hlsenc model does not reproduce the HLS cuts']),
    ].map((violation) => `${fixture.name} / ${oracle.hlsRun}: ${violation}`),
  );
}

function pythonBoolean(value) {
  return value ? 'True' : 'False';
}

function recordingViolations(fixture) {
  const found = [];
  const check = fixture.sourceKeyframeCheck;
  if (check !== null && !check.recordedKeyframesEqualSourceKeyframes) {
    found.push(`${fixture.name}: a recorded keyframe is not the source's own keyframe`);
  }
  if (!fixture.diagnostics.everyKeyframeStartsAFragment) {
    found.push(`${fixture.name}: a keyframe does not start a fragment`);
  }
  return found;
}

function sideClaimViolations(expected) {
  const found = [
    ...expected.initializationSegmentIdentityPairs
      .filter((pair) => !pair.identical)
      .map((pair) => `initialization segments differ: ${pair.label}`),
    ...expected.initializationSegmentDifferencePairs
      .filter((pair) => pair.identical)
      .map((pair) => `initialization segments are identical: ${pair.label}`),
  ];
  const claims = expected.adrSideClaims;
  if (claims.mpegTsAacCopyWithoutAdtstoasc.exitStatus === 0) {
    found.push('an MPEG-TS AAC copy without aac_adtstoasc no longer fails');
  }
  if (!claims.adtstoascLeavesMp4SourceCopyByteIdentical) {
    found.push('aac_adtstoasc changes a copy from an MP4 source');
  }
  if (!claims.maxDelayLeavesMp4OutputByteIdentical) {
    found.push('-max_delay changes mp4 output');
  }
  const cfr = claims.seekWithFpsModeCfr;
  if (cfr.firstVideoPresentationTime !== 0n || cfr.videoSamples <= cfr.videoSamplesWithoutFpsMode) {
    found.push('an explicit -fps_mode cfr after a seek no longer pads from zero');
  }
  return found;
}

/** Every claim in expected.json that the recordings contradict; any one fails the recorder. */
export function violatedClaims(expected) {
  return [
    ...expected.fixtures.flatMap((fixture) => [...oracleViolations(fixture), ...recordingViolations(fixture)]),
    ...sideClaimViolations(expected),
  ];
}
