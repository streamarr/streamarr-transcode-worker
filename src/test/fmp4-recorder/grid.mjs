// ADR 0037's segment rule, written as a partition of the whole fragment sequence rather than as a
// fragment-by-fragment state machine, so that it checks the worker's grouper instead of restating
// it:
//
//   * a keyframe fragment (its first video sample is a sync sample) lies in interval
//     floor(presentationTime / (period * timescale)) of the zero-based grid;
//   * a segment opens at every keyframe fragment whose interval differs from the previous keyframe
//     fragment's, and holds every fragment up to the next such opening; fragments before the first
//     opening belong to the first segment;
//   * segments numbered below the start sequence number are preroll and are discarded;
//   * numbers are contiguous from the start sequence number: the first opening must be the start
//     number or below it, and each later one at most one past the previous opening or the start
//     number, whichever is larger; the first opening beyond that skips a segment number;
//   * media time never moves backwards from one keyframe fragment to the next.
//
// A failure ends the grouping at the failing fragment: nothing from that fragment on is grouped.
// A keyframe that skips a segment number still marks where the open segment ends, so that segment
// is complete and is delivered (or discarded, when it is preroll) before the failure. A keyframe
// that moves media time backwards leaves the open segment's end unknown, so it is not delivered.

import { floorDiv } from './rational.mjs';

function keyframesOf(fragments, period) {
  return fragments.flatMap((fragment, index) => {
    const video = fragment.video;
    if (video === null || !video.sync) {
      return [];
    }
    const number = Number(floorDiv(video.presentationTime, BigInt(period) * BigInt(video.timescale)));
    return [{ index, presentationTime: video.presentationTime, number }];
  });
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

function range(from, to) {
  return Array.from({ length: to - from }, (_, offset) => from + offset);
}

/**
 * Groups fragments ({ video: null | { presentationTime: bigint, timescale, sync } }) in arrival
 * order. Returns the delivered segments, the discarded preroll and the failure, if any; a segment
 * is { number, firstVideoPresentationTime, fragments: [fragment indexes] }.
 */
export function groupOnGrid(fragments, { period, startSequenceNumber }) {
  const keyframes = keyframesOf(fragments, period);
  const regression = firstRegression(keyframes);
  const ordered = regression === null ? keyframes : keyframes.slice(0, keyframes.indexOf(regression));
  const openings = openingsOf(ordered);
  const skip = firstSkip(openings, startSequenceNumber);
  const failure = failureOf(skip, regression);
  const end = failure === null ? fragments.length : failure.fragmentIndex;
  const opened = openings.filter((opening) => opening.index < end);
  const segments = opened.map((opening, position) => ({
    number: opening.number,
    firstVideoPresentationTime: opening.presentationTime,
    fragments: range(position === 0 ? 0 : opening.index, opened[position + 1]?.index ?? end),
  }));
  const closed = regression !== null && skip === null ? segments.slice(0, -1) : segments;
  return {
    delivered: closed.filter((segment) => segment.number >= startSequenceNumber),
    preroll: closed.filter((segment) => segment.number < startSequenceNumber),
    failure,
  };
}
