// ADR 0037's segment rule, written as a partition of the whole video sample sequence rather than
// as a sample-by-sample state machine, so that it checks the worker's grouper instead of restating
// it:
//
//   * a keyframe (a sync video sample) lies in interval
//     floor(presentationTime / (period * timescale)) of the zero-based grid;
//   * a segment opens at every keyframe whose interval differs from the previous keyframe's, and
//     holds every video sample up to the next such opening; samples before the first opening
//     belong to the first segment;
//   * a segment holds every fragment that carries one of its video samples, so a fragment whose
//     keyframe opens a segment after its first video sample belongs to both segments; fragments
//     without video belong to the segment open when they arrive;
//   * segments numbered below the start sequence number are preroll and are discarded;
//   * numbers are contiguous from the start sequence number: the first opening must be the start
//     number or below it, and each later one at most one past the previous opening or the start
//     number, whichever is larger; the first opening beyond that skips a segment number;
//   * media time never moves backwards from one keyframe to the next.
//
// A failure ends the grouping at the failing keyframe: nothing from that keyframe on is grouped.
// A keyframe that skips a segment number still marks where the open segment ends, so that segment
// is complete and is delivered (or discarded, when it is preroll) before the failure. A keyframe
// that moves media time backwards leaves the open segment's end unknown, so it is not delivered.

import { floorDiv } from './rational.mjs';

function keyframesOf(fragments, period) {
  const samples = fragments.flatMap((fragment, index) =>
    (fragment.video?.samples ?? []).map((sample, offset) => ({ index, offset, timescale: fragment.video.timescale, sample })),
  );
  return samples.flatMap(({ index, offset, timescale, sample }, position) => {
    if (!sample.sync) {
      return [];
    }
    const number = Number(floorDiv(sample.presentationTime, BigInt(period) * BigInt(timescale)));
    return [{ index, offset, position, presentationTime: sample.presentationTime, number }];
  });
}

function videoSampleCount(fragments) {
  return fragments.reduce((sum, fragment) => sum + (fragment.video?.samples.length ?? 0), 0);
}

function firstRegression(keyframes) {
  return (
    keyframes.find(
      (keyframe, position) => position > 0 && keyframe.presentationTime < keyframes[position - 1].presentationTime,
    ) ?? null
  );
}

function openingsOf(keyframes) {
  return keyframes.filter(
    (keyframe, position) => position === 0 || keyframe.number !== keyframes[position - 1].number,
  );
}

function firstSkip(openings, startSequenceNumber) {
  for (const [position, opening] of openings.entries()) {
    const expectedNumber =
      position === 0 ? startSequenceNumber : Math.max(startSequenceNumber, openings[position - 1].number + 1);
    if (opening.number > expectedNumber) {
      return { opening, expectedNumber };
    }
  }
  return null;
}

function failureOf(skip, regression) {
  if (skip !== null) {
    return {
      reason: 'SKIPPED_SEGMENT_NUMBER',
      fragmentIndex: skip.opening.index,
      expectedNumber: skip.expectedNumber,
      actualNumber: skip.opening.number,
      presentationTime: skip.opening.presentationTime,
    };
  }
  if (regression !== null) {
    return {
      reason: 'PRESENTATION_TIME_REGRESSED',
      fragmentIndex: regression.index,
      presentationTime: regression.presentationTime,
    };
  }
  return null;
}

// A fragment whose keyframe opens the next segment after its first video sample holds the tail of
// the segment before it too.
function fragmentEnd(next, fragmentCount) {
  if (next === null) {
    return fragmentCount;
  }
  return next.offset > 0 ? next.index + 1 : next.index;
}

function range(from, to) {
  return Array.from({ length: to - from }, (_, offset) => from + offset);
}

/**
 * Groups fragments ({ video: null | { timescale, samples: [{ presentationTime: bigint, sync }] } },
 * the video samples in decode order) in arrival order. Returns the delivered segments, the
 * discarded preroll and the failure, if any; a segment is
 * { number, firstVideoPresentationTime, openedAtFragment, fragments: [fragment indexes],
 * videoSamples: { from, to } }: the index of the fragment whose keyframe opened it, and the video
 * samples numbered across every fragment in decode order, from inclusive, to exclusive.
 */
export function groupOnGrid(fragments, { period, startSequenceNumber }) {
  const keyframes = keyframesOf(fragments, period);
  const regression = firstRegression(keyframes);
  const ordered = regression === null ? keyframes : keyframes.slice(0, keyframes.indexOf(regression));
  const openings = openingsOf(ordered);
  const skip = firstSkip(openings, startSequenceNumber);
  const failure = failureOf(skip, regression);
  const failing = skip?.opening ?? regression;
  const opened = failing === null ? openings : openings.filter((opening) => opening.position < failing.position);
  const segments = opened.map((opening, order) => {
    const next = opened[order + 1] ?? failing;
    return {
      number: opening.number,
      firstVideoPresentationTime: opening.presentationTime,
      openedAtFragment: opening.index,
      fragments: range(order === 0 ? 0 : opening.index, fragmentEnd(next, fragments.length)),
      videoSamples: { from: order === 0 ? 0 : opening.position, to: next?.position ?? videoSampleCount(fragments) },
    };
  });
  const closed = regression !== null && skip === null ? segments.slice(0, -1) : segments;
  return {
    delivered: closed.filter((segment) => segment.number >= startSequenceNumber),
    preroll: closed.filter((segment) => segment.number < startSequenceNumber),
    failure,
  };
}
