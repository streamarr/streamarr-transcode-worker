package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.Fraction;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An exact fraction in lowest terms with a positive denominator, for frame rates and time bases.
 */
record ExactFraction(BigInteger numerator, BigInteger denominator)
    implements Comparable<ExactFraction> {

  private static final Pattern TEXT = Pattern.compile("(-?\\d+)(?:/(-?\\d+))?");
  private static final BigInteger LARGEST_UINT32 = BigInteger.valueOf(0xFFFF_FFFFL);

  static final ExactFraction ZERO = of(0, 1);

  ExactFraction {
    if (denominator.signum() == 0) {
      throw new ArithmeticException("a fraction cannot have a zero denominator");
    }

    var divisor = numerator.gcd(denominator);
    if (denominator.signum() < 0) {
      divisor = divisor.negate();
    }

    if (divisor.signum() != 0) {
      numerator = numerator.divide(divisor);
      denominator = denominator.divide(divisor);
    }
  }

  static ExactFraction of(long numerator, long denominator) {
    return new ExactFraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
  }

  static ExactFraction of(long value) {
    return of(value, 1);
  }

  /** A fraction that ffprobe prints, such as 24000/1001 or 60; empty for 0/0 or another x/0. */
  static Optional<ExactFraction> parse(String text) {
    var match = TEXT.matcher(text.strip());
    if (!match.matches()) {
      return Optional.empty();
    }

    var denominator = match.group(2) == null ? BigInteger.ONE : new BigInteger(match.group(2));
    if (denominator.signum() == 0) {
      return Optional.empty();
    }

    return Optional.of(new ExactFraction(new BigInteger(match.group(1)), denominator));
  }

  ExactFraction plus(ExactFraction other) {
    return new ExactFraction(
        numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator));
  }

  ExactFraction minus(ExactFraction other) {
    return plus(other.negate());
  }

  ExactFraction times(ExactFraction other) {
    return new ExactFraction(
        numerator.multiply(other.numerator), denominator.multiply(other.denominator));
  }

  ExactFraction dividedBy(ExactFraction other) {
    return new ExactFraction(
        numerator.multiply(other.denominator), denominator.multiply(other.numerator));
  }

  ExactFraction reciprocal() {
    return new ExactFraction(denominator, numerator);
  }

  ExactFraction negate() {
    return new ExactFraction(numerator.negate(), denominator);
  }

  ExactFraction abs() {
    return new ExactFraction(numerator.abs(), denominator);
  }

  boolean isInteger() {
    return denominator.equals(BigInteger.ONE);
  }

  int signum() {
    return numerator.signum();
  }

  /** The nearest integer; a half rounds to the even integer. */
  BigInteger roundHalfEven() {
    return new BigDecimal(numerator)
        .divide(new BigDecimal(denominator), 0, RoundingMode.HALF_EVEN)
        .toBigIntegerExact();
  }

  double doubleValue() {
    return new BigDecimal(numerator)
        .divide(new BigDecimal(denominator), MathContext.DECIMAL64)
        .doubleValue();
  }

  /**
   * The fraction as the contract's Fraction; empty when a term does not fit an unsigned 32 bits.
   */
  Optional<Fraction> toProto() {
    if (numerator.signum() <= 0
        || numerator.compareTo(LARGEST_UINT32) > 0
        || denominator.compareTo(LARGEST_UINT32) > 0) {
      return Optional.empty();
    }

    return Optional.of(
        Fraction.newBuilder()
            .setNumerator(numerator.intValue())
            .setDenominator(denominator.intValue())
            .build());
  }

  @Override
  public int compareTo(ExactFraction other) {
    return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
  }

  boolean isGreaterThan(ExactFraction other) {
    return compareTo(other) > 0;
  }

  boolean isLessThan(ExactFraction other) {
    return compareTo(other) < 0;
  }

  static ExactFraction min(ExactFraction first, ExactFraction second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  static ExactFraction max(ExactFraction first, ExactFraction second) {
    return first.compareTo(second) >= 0 ? first : second;
  }
}
