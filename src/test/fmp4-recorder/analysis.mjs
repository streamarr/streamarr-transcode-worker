// What expected.json records about one recording, derived from the recorded boxes alone, and the
// comparison of a recording with the HLS muxer's run over the same source.

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  Mp4FormatError,
  audioTrackOf,
  firstAudioTraf,
  readFiles,
  signed,
  videoSamples,
  videoTrackOf,
  videoTrafOf,
} from './fmp4.mjs';
import { groupOnGrid } from './grid.mjs';
import { Decimal } from './json.mjs';
import { Rational } from './rational.mjs';

/** The grid model's view of each fragment: its first video sample, when it has one. */
export function gridFragments(stream) {
  return stream.fragments.map((fragment) => {
    const traf = videoTrafOf(fragment);
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

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

/**
 * Everything expected.json records about one recording that its own bytes decide: its tracks and
 * initialization segment, the media segments and preroll the grid groups it into, and any failure.
 */
export function recordingFacts(stream, { period, startSequenceNumber }) {
  const videoTrack = videoTrackOf(stream);
  const { delivered, preroll, failure } = group(stream, period, startSequenceNumber);
  const lastVideo = stream.fragments.findLastIndex((fragment) => videoTrafOf(fragment) !== null);
  return {
    videoTrackId: videoTrack.trackId,
    videoTimescale: videoTrack.timescale,
    byteLength: stream.data.length,
    initializationSegment: {
      byteLength: stream.initializationSegmentByteLength,
      sha256: sha256(stream.initializationSegmentBytes),
      tracks: [...stream.tracks.values()].map((track) => ({
        trackId: track.trackId,
        handler: track.handler,
        sampleEntry: track.sampleEntry,
        timescale: track.timescale,
        editList: track.edits,
        trexDefaultSampleFlags: track.trexFlags ?? null,
        trexDefaultSampleDuration: track.trexDuration ?? null,
      })),
    },
    segments: delivered.map((segment) => describeSegment(segment, stream)),
    discardedPreroll: preroll.map((segment) => describeSegment(segment, stream)),
    failure,
    endsWithAudioOnlyFragments: lastVideo < stream.fragments.length - 1,
    trailingAudioOnlyFragmentCount: stream.fragments.length - 1 - lastVideo,
    diagnostics: diagnostics(stream),
  };
}

/** Whether two recordings' initialization segments are byte-identical, and their digests. */
export function initializationSegmentPair(label, [firstName, first], [secondName, second]) {
  const a = first.initializationSegmentBytes;
  const b = second.initializationSegmentBytes;
  return {
    label,
    files: [`${firstName}.fmp4`, `${secondName}.fmp4`],
    identical: a.equals(b),
    byteLengths: [a.length, b.length],
    sha256: [sha256(a), sha256(b)],
  };
}

export function describeSegment(segment, stream) {
  const fragments = segment.fragments.map((index) => stream.fragments[index]);
  return {
    number: segment.number,
    firstVideoPresentationTime: segment.firstVideoPresentationTime,
    firstFragmentIndex: segment.fragments[0],
    fragmentCount: fragments.length,
    syncFirstFragmentCount: fragments.filter((fragment) => videoTrafOf(fragment)?.firstSync === true).length,
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
  const timeBase = new Rational(num, den);
  const micros = Rational.parseDecimal(probe.format.start_time).times(1_000_000n);
  const ticks = micros.dividedBy(timeBase.times(1_000_000n)).roundHalfEven();
  return { start: timeBase.times(ticks), startMicros: micros.truncate(), video, timeBase };
}

/** Each "pts,flags" line of ffprobe's keyframe listing, as seconds at the stream's time base. */
export function sourceKeyframes(csv, timeBase) {
  return csv
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => timeBase.times(BigInt(line.split(',')[0])));
}

export function loadSource(work, source) {
  const folder = join(work, 'src');
  const probe = JSON.parse(readFileSync(join(folder, `${source}.probe.json`), 'utf8'));
  const { start, startMicros, video, timeBase } = sourceStart(probe);
  const csv = readFileSync(join(folder, `${source}.keyframes.csv`), 'utf8');
  return { start, startMicros, video, keyframes: sourceKeyframes(csv, timeBase) };
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
  const initializationSegment = join(folder, 'init.mp4');
  const initialized = readFiles([initializationSegment]);
  const videoTrack = videoTrackOf(initialized);
  const audioTrack = audioTrackOf(initialized);
  const edit = videoTrack.edits.map(([, mediaTime]) => mediaTime).find((mediaTime) => mediaTime >= 0n) ?? 0n;
  const samples = [];
  const segments = [];
  let audioFirst = null;
  uris.forEach((uri, offset) => {
    const stream = readFiles([initializationSegment, join(folder, uri)]);
    const video = stream.fragments.map(videoTrafOf).find((traf) => traf !== null);
    if (audioFirst === null) {
      const audio = firstAudioTraf(stream.fragments);
      audioFirst = audio === null ? null : signed(audio.baseMediaDecodeTime);
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
export function hlsencCuts({ keyframeOrdinals, ptsRaw, reference, period }) {
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
export function compareHlsRun({ fixture, spec, reference, grouped, source, hls, period }) {
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
    const presentationTime = refSamples[segment.ordinal].presentationTime;
    directAgrees = directAgrees && new Rational(segment.ownPresentation).minus(offsetTicks).equals(presentationTime);
    if (spec.restrict && segment.number < fixture.start) {
      continue;
    }
    converted.push({ number: segment.number, firstVideoPresentationTime: presentationTime });
  }
  const found = mismatches(grouped, converted);

  const ptsRaw = refSamples.map((sample) => new Rational(sample.presentationTime, timescale).plus(offset));
  const { reference: start, from } = hlsencReference({ spec, hls, ptsRaw, timescale });
  const keyframes = hls.samples.flatMap((sample, index) => (sample.sync ? [index] : []));
  const modeled = hlsencCuts({ keyframeOrdinals: keyframes, ptsRaw, reference: start, period });
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

function compareBigInt(a, b) {
  if (a < b) {
    return -1;
  }
  if (a > b) {
    return 1;
  }
  return 0;
}

/** The presentation time of every video sync sample, in ascending order. */
export function recordedKeyframes(stream) {
  return videoSamples(stream)
    .filter((sample) => sample.sync)
    .map((sample) => sample.presentationTime)
    .sort(compareBigInt);
}

/** For a stream copy: whether every recorded keyframe is the source's own, moved to media time. */
export function checkSourceKeyframes({ mode, stream, source, timescale }) {
  if (mode !== 'copy') {
    return null;
  }
  const recorded = recordedKeyframes(stream);
  const expected = [
    ...new Set(source.keyframes.map((keyframe) => keyframe.minus(source.start).times(timescale).truncate())),
  ]
    .sort(compareBigInt)
    .filter((keyframe) => keyframe >= recorded[0]);
  return {
    recordedKeyframesEqualSourceKeyframes:
      recorded.length === expected.length && recorded.every((keyframe, index) => keyframe === expected[index]),
    keyframePresentationTimes: recorded,
  };
}

const NAL_UNIT_TYPE = {
  avc1: { of: (header) => header & 0x1f, isPicture: (type) => type >= 1 && type <= 5 },
  hvc1: { of: (header) => (header >> 1) & 0x3f, isPicture: (type) => type <= 31 },
  hev1: { of: (header) => (header >> 1) & 0x3f, isPicture: (type) => type <= 31 },
};
const HEVC_RASL = new Set([8, 9]);

/** The NAL unit types of one length-prefixed sample, in order. */
function nalUnitTypesOf(stream, sample, { nalLengthSize, sampleEntry }) {
  const types = [];
  const end = sample.offset + sample.size;
  let position = sample.offset;
  while (position < end) {
    const length = end - position < nalLengthSize ? Infinity : stream.data.readUIntBE(position, nalLengthSize);
    if (length === 0 || length > end - position - nalLengthSize) {
      throw new Mp4FormatError(`a NAL unit at ${position} runs past its sample`);
    }
    types.push(NAL_UNIT_TYPE[sampleEntry].of(stream.data[position + nalLengthSize]));
    position += nalLengthSize + length;
  }
  return types;
}

/**
 * For an H.264 or HEVC video track: the NAL unit type of the first picture of every keyframe (5 is
 * an H.264 IDR; 19 and 20 are HEVC IDR, 21 an HEVC CRA, which opens a GOP), and for HEVC the
 * number of RASL pictures, which reference the GOP before a CRA. Null for other codecs.
 */
export function videoPictures(stream) {
  const track = videoTrackOf(stream);
  const nalUnitType = NAL_UNIT_TYPE[track.sampleEntry];
  if (nalUnitType === undefined) {
    return null;
  }
  const pictureOf = (sample) => nalUnitTypesOf(stream, sample, track).find(nalUnitType.isPicture);
  const samples = videoSamples(stream);
  const keyframeTypes = new Set(samples.filter((sample) => sample.sync).map(pictureOf));
  return {
    sampleEntry: track.sampleEntry,
    keyframeNalUnitTypes: [...keyframeTypes].sort((a, b) => a - b),
    raslPictures: track.sampleEntry === 'avc1' ? null : samples.filter((sample) => HEVC_RASL.has(pictureOf(sample))).length,
  };
}

function everyKeyframeStartsAFragment(stream) {
  const samples = videoSamples(stream);
  const withKeyframes = new Set(samples.filter((sample) => sample.sync).map((sample) => sample.fragmentIndex));
  return [...withKeyframes].every((index) => {
    const start = videoTrafOf(stream.fragments[index]);
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
  const video = fragments.map(videoTrafOf);
  const starts = video.filter((traf) => traf !== null).map((traf) => traf.firstPresentationTime);
  const audio = firstAudioTraf(fragments);
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
    videoPictures: videoPictures(stream),
  };
}

function packetViolations({ hlsVideoSamples, identicalPackets, sharesVideoArguments }) {
  if (!sharesVideoArguments || identicalPackets === hlsVideoSamples) {
    return [];
  }
  return [
    `${hlsVideoSamples - identicalPackets} of ${hlsVideoSamples} video packets differ from the pipe recording, ` +
      'whose video arguments it shares',
  ];
}

function hlsComparisonViolations(fixture) {
  return fixture.hlsComparisons.flatMap((comparison) =>
    [
      ...(comparison.agrees === comparison.expectedToAgree
        ? []
        : [`agrees=${comparison.agrees}, expected ${comparison.expectedToAgree}`]),
      ...packetViolations(comparison.frameIdentity),
      ...(comparison.hlsencModel.reproducesHlsCuts ? [] : ['the hlsenc model does not reproduce the HLS cuts']),
    ].map((violation) => `${fixture.name} / ${comparison.hlsRun}: ${violation}`),
  );
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
  if (BigInt(cfr.firstVideoPresentationTime) !== 0n || cfr.videoSamples <= cfr.videoSamplesWithoutFpsMode) {
    found.push('an explicit -fps_mode cfr after a seek no longer pads from zero');
  }
  return found;
}

/** Every claim in expected.json that the recordings contradict; any one fails the recorder. */
export function violatedClaims(expected) {
  return [
    ...expected.fixtures.flatMap((fixture) => [...hlsComparisonViolations(fixture), ...recordingViolations(fixture)]),
    ...sideClaimViolations(expected),
  ];
}
