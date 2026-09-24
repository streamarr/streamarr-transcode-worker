import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Rational, floorDiv } from './rational.mjs';

describe('exact rationals', () => {
  it('parses a decimal exactly', () => {
    assert.equal(String(Rational.parseDecimal('11.978667')), '11978667/1000000');
    assert.equal(String(Rational.parseDecimal('-1.5e-3')), '-3/2000');
    assert.equal(String(Rational.parseDecimal('2E2')), '200');
    assert.equal(String(Rational.parseDecimal('.5')), '1/2');
  });

  it('rejects text that is not a decimal', () => {
    for (const text of ['', '.', 'N/A', '1/2']) {
      assert.throws(() => Rational.parseDecimal(text), SyntaxError, text);
    }
  });

  it('keeps sums, differences, products and quotients exact', () => {
    const frame = new Rational(1001n, 24000n);

    assert.equal(String(frame.times(144)), '3003/500');
    assert.equal(String(frame.plus(frame).minus(new Rational(1n, 24n))), '167/4000');
    assert.equal(String(frame.dividedBy(new Rational(-1001n, 12000n))), '-1/2');
  });

  it('floors toward negative infinity', () => {
    assert.equal(floorDiv(7n, 2n), 3n);
    assert.equal(floorDiv(-7n, 2n), -4n);
    assert.equal(floorDiv(-6n, 2n), -3n);
    assert.equal(floorDiv(7, -2), -4n);
    assert.throws(() => floorDiv(1n, 0n), RangeError);
  });

  it('truncates toward zero', () => {
    assert.equal(new Rational(-7n, 2n).truncate(), -3n);
    assert.equal(new Rational(7n, 2n).truncate(), 3n);
  });

  it('rounds a tie to the even integer', () => {
    assert.deepEqual(
      ['5/2', '7/2', '-5/2', '-7/2', '13/5', '12/5'].map((text) => {
        const [n, d] = text.split('/').map(BigInt);
        return new Rational(n, d).roundHalfEven();
      }),
      [2n, 4n, -2n, -4n, 3n, 2n],
    );
  });

  it('compares by value', () => {
    assert.equal(new Rational(1n, 3n).compare(new Rational(2n, 6n)), 0);
    assert.equal(new Rational(1n, 3n).compare(0), 1);
    assert.equal(new Rational(-1n, 3n).compare(0), -1);
    assert.ok(new Rational(4n, 2n).equals(2n));
  });

  it('converts to a double only when the conversion is exact', () => {
    assert.equal(new Rational(1n, 4n).toNumber(), 0.25);
    assert.throws(() => new Rational(1n << 60n, 3n).toNumber(), RangeError);
  });

  it('rejects inexact integers and zero denominators', () => {
    assert.throws(() => new Rational(0.5), RangeError);
    assert.throws(() => new Rational(1n, 0n), RangeError);
  });
});
