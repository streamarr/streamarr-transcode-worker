import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { groupOnGrid } from './grid.mjs';

const PERIOD = 6;

// Fragments in milliseconds: K = keyframe first, N = non-sync first, A = no video.
const K = (milliseconds) => ({ video: { presentationTime: BigInt(milliseconds), timescale: 1000, sync: true } });
const N = (milliseconds) => ({ video: { presentationTime: BigInt(milliseconds), timescale: 1000, sync: false } });
const A = () => ({ video: null });

function numbersAndFragments(segments) {
  return segments.map((segment) => [segment.number, segment.fragments]);
}

function grouped(fragments, startSequenceNumber = 0) {
  const result = groupOnGrid(fragments, { period: PERIOD, startSequenceNumber });
  return {
    delivered: numbersAndFragments(result.delivered),
    preroll: numbersAndFragments(result.preroll),
    failure: result.failure,
  };
}

describe('grid model', () => {
  it('Should open a segment and keep what follows it when a keyframe lies inside the next interval', () => {
    assert.deepEqual(grouped([K(0), N(1001), K(6006), N(7007), A()]).delivered, [
      [0, [0, 1]],
      [1, [2, 3, 4]],
    ]);
  });

  it('Should keep a second keyframe in the open segment when its interval holds it', () => {
    assert.deepEqual(grouped([K(0), K(5964), K(6006), K(11970)]).delivered, [
      [0, [0, 1]],
      [1, [2, 3]],
    ]);
  });

  it('Should open a segment when a keyframe lies exactly on its boundary', () => {
    assert.deepEqual(grouped([K(0), K(5999), K(6000)]).delivered, [
      [0, [0, 1]],
      [1, [2]],
    ]);
  });

  it('Should keep a non-sync fragment in the open segment when it starts past the interval', () => {
    assert.deepEqual(grouped([K(0), N(7000), K(7500)]).delivered, [
      [0, [0, 1]],
      [1, [2]],
    ]);
  });

  it('Should put fragments into the first segment when they arrive before any keyframe', () => {
    assert.deepEqual(grouped([A(), N(0), K(40)]).delivered, [[0, [0, 1, 2]]]);
  });

  it('Should deliver nothing when no keyframe ever opens a segment', () => {
    assert.deepEqual(grouped([A(), N(0)]), { delivered: [], preroll: [], failure: null });
  });

  it('Should discard a segment with the fragments that follow it when it lies below the start sequence number', () => {
    const result = grouped([A(), K(27000), N(28000), K(30030), N(31031)], 5);

    assert.deepEqual(result.preroll, [[4, [0, 1, 2]]]);
    assert.deepEqual(result.delivered, [[5, [3, 4]]]);
  });

  it('Should skip preroll numbers without failing when they lie below the start sequence number', () => {
    const result = grouped([K(9000), K(21000), K(30000)], 5);

    assert.deepEqual(result.preroll, [
      [1, [0]],
      [3, [1]],
    ]);
    assert.deepEqual(result.delivered, [[5, [2]]]);
    assert.equal(result.failure, null);
  });

  it('Should place a keyframe below segment zero when its presentation time is negative', () => {
    const result = grouped([K(-6001), K(-1), K(0)]);

    assert.deepEqual(result.preroll, [
      [-2, [0]],
      [-1, [1]],
    ]);
    assert.deepEqual(result.delivered, [[0, [2]]]);
  });

  it('Should deliver the segment a keyframe closed, then fail, when the keyframe skips a segment number', () => {
    const result = grouped([K(0), N(1000), K(6000), N(7000), K(18000), N(19000)]);

    assert.deepEqual(result.delivered, [
      [0, [0, 1]],
      [1, [2, 3]],
    ]);
    assert.deepEqual(result.failure, {
      reason: 'SKIPPED_SEGMENT_NUMBER',
      fragmentIndex: 4,
      expectedNumber: 2,
      actualNumber: 3,
      presentationTime: 18000n,
    });
  });

  it('Should fail when the first keyframe lies past the start sequence number', () => {
    const result = grouped([A(), K(36000)], 5);

    assert.deepEqual(result.delivered, []);
    assert.equal(result.failure.fragmentIndex, 1);
    assert.equal(result.failure.expectedNumber, 5);
  });

  it('Should discard the preroll a skipping keyframe closed when the keyframe lies past the start sequence number', () => {
    const result = grouped([K(27000), N(28000), K(36000)], 5);

    assert.deepEqual(result.preroll, [[4, [0, 1]]]);
    assert.deepEqual(result.delivered, []);
    assert.equal(result.failure.reason, 'SKIPPED_SEGMENT_NUMBER');
    assert.equal(result.failure.actualNumber, 6);
  });

  it('Should deliver nothing when the first keyframe skips the start sequence number', () => {
    const result = grouped([A(), N(35000), K(36000)], 5);

    assert.deepEqual(result, {
      delivered: [],
      preroll: [],
      failure: { reason: 'SKIPPED_SEGMENT_NUMBER', fragmentIndex: 2, expectedNumber: 5, actualNumber: 6, presentationTime: 36000n },
    });
  });

  it('Should fail and deliver no segment still open when a keyframe starts before the previous keyframe', () => {
    const result = grouped([K(0), K(6000), N(7000), K(5999), K(12000)]);

    assert.deepEqual(result.delivered, [[0, [0]]]);
    assert.deepEqual(result.failure, {
      reason: 'PRESENTATION_TIME_REGRESSED',
      fragmentIndex: 3,
      presentationTime: 5999n,
    });
  });

  it('Should read each keyframe in its own timescale when timescales differ', () => {
    const at = (ticks, timescale) => ({ video: { presentationTime: ticks, timescale, sync: true } });

    assert.deepEqual(grouped([at(0n, 24000), at(143143n, 24000), at(144144n, 24000), at(540000n, 90000)]).delivered, [
      [0, [0, 1]],
      [1, [2, 3]],
    ]);
  });
});
