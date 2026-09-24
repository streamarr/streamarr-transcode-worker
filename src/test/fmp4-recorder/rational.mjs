// Exact rational numbers over BigInt, for media-time arithmetic that must never round.

function gcd(a, b) {
  let x = a < 0n ? -a : a;
  let y = b < 0n ? -b : b;
  while (y !== 0n) {
    [x, y] = [y, x % y];
  }
  return x;
}

function bigint(value) {
  if (typeof value === 'bigint') {
    return value;
  }
  if (!Number.isSafeInteger(value)) {
    throw new RangeError(`not an exact integer: ${value}`);
  }
  return BigInt(value);
}

/** Floor division of integers, as Python's // and Java's Math.floorDiv. */
export function floorDiv(dividend, divisor) {
  const a = bigint(dividend);
  const b = bigint(divisor);
  if (b === 0n) {
    throw new RangeError('division by zero');
  }
  const quotient = a / b;
  return (a % b !== 0n) && ((a < 0n) !== (b < 0n)) ? quotient - 1n : quotient;
}

export class Rational {
  constructor(numerator, denominator = 1n) {
    let n = bigint(numerator);
    let d = bigint(denominator);
    if (d === 0n) {
      throw new RangeError('zero denominator');
    }
    if (d < 0n) {
      n = -n;
      d = -d;
    }
    const divisor = gcd(n, d) || 1n;
    this.numerator = n / divisor;
    this.denominator = d / divisor;
    Object.freeze(this);
  }

  static of(value, denominator = 1n) {
    return value instanceof Rational ? value : new Rational(value, denominator);
  }

  /** Parses a decimal such as ffprobe's "11.978667" or "-1.5e-3" exactly. */
  static parseDecimal(text) {
    const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(String(text).trim());
    if (!match || (match[2] === '' && (match[3] ?? '') === '')) {
      throw new SyntaxError(`not a decimal: ${text}`);
    }
    const [, sign, whole, fraction = '', exponent = '0'] = match;
    const digits = BigInt((whole || '0') + fraction) * (sign === '-' ? -1n : 1n);
    const scale = BigInt(exponent) - BigInt(fraction.length);
    if (scale >= 0n) {
      return new Rational(digits * 10n ** scale);
    }
    return new Rational(digits, 10n ** -scale);
  }

  plus(other) {
    const o = Rational.of(other);
    return new Rational(
      this.numerator * o.denominator + o.numerator * this.denominator,
      this.denominator * o.denominator,
    );
  }

  minus(other) {
    const o = Rational.of(other);
    return this.plus(new Rational(-o.numerator, o.denominator));
  }

  times(other) {
    const o = Rational.of(other);
    return new Rational(this.numerator * o.numerator, this.denominator * o.denominator);
  }

  dividedBy(other) {
    const o = Rational.of(other);
    return new Rational(this.numerator * o.denominator, this.denominator * o.numerator);
  }

  compare(other) {
    const o = Rational.of(other);
    const difference = this.numerator * o.denominator - o.numerator * this.denominator;
    if (difference < 0n) {
      return -1;
    }
    if (difference > 0n) {
      return 1;
    }
    return 0;
  }

  equals(other) {
    return this.compare(other) === 0;
  }

  /** The integer part, rounded toward zero (Python's int()). */
  truncate() {
    return this.numerator / this.denominator;
  }

  /** The nearest integer, ties to the even one (Python's round() of a Fraction). */
  roundHalfEven() {
    const floor = floorDiv(this.numerator, this.denominator);
    const remainder = this.minus(floor).compare(new Rational(1n, 2n));
    if (remainder < 0 || (remainder === 0 && floor % 2n === 0n)) {
      return floor;
    }
    return floor + 1n;
  }

  /** The nearest double; exact inputs only, so that no conversion rounds twice. */
  toNumber() {
    const n = Number(this.numerator);
    const d = Number(this.denominator);
    if (!Number.isSafeInteger(n) || !Number.isSafeInteger(d)) {
      throw new RangeError(`no exact double conversion for ${this}`);
    }
    return n / d;
  }

  toString() {
    return this.denominator === 1n ? `${this.numerator}` : `${this.numerator}/${this.denominator}`;
  }
}
