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
  it('opens a segment at a keyframe inside the next interval and keeps what follows it', () => {
    assert.deepEqual(grouped([K(0), N(1001), K(6006), N(7007), A()]).delivered, [
      [0, [0, 1]],
      [1, [2, 3, 4]],
    ]);
  });

  it('keeps a second keyframe in the segment whose interval holds it', () => {
    assert.deepEqual(grouped([K(0), K(5964), K(6006), K(11970)]).delivered, [
      [0, [0, 1]],
      [1, [2, 3]],
    ]);
  });

  it('opens a segment exactly on its boundary', () => {
    assert.deepEqual(grouped([K(0), K(5999), K(6000)]).delivered, [
      [0, [0, 1]],
      [1, [2]],
    ]);
  });

  it('keeps a non-sync fragment that starts past the interval in the open segment', () => {
    assert.deepEqual(grouped([K(0), N(7000), K(7500)]).delivered, [
      [0, [0, 1]],
      [1, [2]],
    ]);
  });

  it('puts fragments that arrive before any keyframe into the first segment', () => {
    assert.deepEqual(grouped([A(), N(0), K(40)]).delivered, [[0, [0, 1, 2]]]);
  });

  it('delivers nothing when no keyframe ever opens a segment', () => {
    assert.deepEqual(grouped([A(), N(0)]), { delivered: [], preroll: [], failure: null });
  });

  it('discards preroll segments below the start sequence number with the fragments that follow them', () => {
    const result = grouped([A(), K(27000), N(28000), K(30030), N(31031)], 5);

    assert.deepEqual(result.preroll, [[4, [0, 1, 2]]]);
    assert.deepEqual(result.delivered, [[5, [3, 4]]]);
  });

  it('skips preroll numbers below the start sequence number without failing', () => {
    const result = grouped([K(9000), K(21000), K(30000)], 5);

    assert.deepEqual(result.preroll, [
      [1, [0]],
      [3, [1]],
    ]);
    assert.deepEqual(result.delivered, [[5, [2]]]);
    assert.equal(result.failure, null);
  });

  it('places a keyframe with a negative presentation time below segment zero', () => {
    const result = grouped([K(-6001), K(-1), K(0)]);

    assert.deepEqual(result.preroll, [
      [-2, [0]],
      [-1, [1]],
    ]);
    assert.deepEqual(result.delivered, [[0, [2]]]);
  });

  it('fails at the keyframe that skips a segment number and delivers no segment still open', () => {
    const result = grouped([K(0), N(1000), K(6000), N(7000), K(18000), N(19000)]);

    assert.deepEqual(result.delivered, [[0, [0, 1]]]);
    assert.deepEqual(result.failure, {
      reason: 'SKIPPED_SEGMENT_NUMBER',
      fragmentIndex: 4,
      expectedNumber: 2,
      actualNumber: 3,
      presentationTime: 18000n,
    });
  });

  it('fails when the first keyframe lies past the start sequence number', () => {
    const result = grouped([A(), K(36000)], 5);

    assert.deepEqual(result.delivered, []);
    assert.equal(result.failure.fragmentIndex, 1);
    assert.equal(result.failure.expectedNumber, 5);
  });

  it('fails when the keyframe after the preroll lies past the start sequence number', () => {
    const result = grouped([K(27000), K(36000)], 5);

    assert.deepEqual(result.preroll, []);
    assert.equal(result.failure.reason, 'SKIPPED_SEGMENT_NUMBER');
    assert.equal(result.failure.actualNumber, 6);
  });

  it('fails when a keyframe starts before the previous keyframe', () => {
    const result = grouped([K(0), K(6000), N(7000), K(5999), K(12000)]);

    assert.deepEqual(result.delivered, [[0, [0]]]);
    assert.deepEqual(result.failure, {
      reason: 'PRESENTATION_TIME_REGRESSED',
      fragmentIndex: 3,
      presentationTime: 5999n,
    });
  });

  it('reads each keyframe in its own timescale', () => {
    const at = (ticks, timescale) => ({ video: { presentationTime: ticks, timescale, sync: true } });

    assert.deepEqual(grouped([at(0n, 24000), at(143143n, 24000), at(144144n, 24000), at(540000n, 90000)]).delivered, [
      [0, [0, 1]],
      [1, [2, 3]],
    ]);
  });
});
