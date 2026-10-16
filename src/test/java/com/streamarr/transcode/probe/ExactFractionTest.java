package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Exact Fraction Tests")
class ExactFractionTest {

  @Test
  @DisplayName("Should keep the denominator positive when a fraction is reduced")
  void shouldKeepTheDenominatorPositiveWhenAFractionIsReduced() {
    assertThat(ExactFraction.of(6, -4)).isEqualTo(ExactFraction.of(-3, 2));
  }

  @Test
  @DisplayName("Should refuse a zero denominator when a fraction is built")
  void shouldRefuseAZeroDenominatorWhenAFractionIsBuilt() {
    assertThatThrownBy(() -> ExactFraction.of(1, 0)).isInstanceOf(ArithmeticException.class);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"24000/1001, 24000, 1001", "60, 60, 1", "50/2, 25, 1"})
  @DisplayName("Should read the fraction in lowest terms when ffprobe prints a rate")
  void shouldReadTheFractionInLowestTermsWhenFfprobePrintsARate(
      String text, long numerator, long denominator) {
    assertThat(ExactFraction.parse(text)).contains(ExactFraction.of(numerator, denominator));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"0/0", "1/0", "N/A", "", "1.5"})
  @DisplayName("Should read no fraction when ffprobe prints no usable rate")
  void shouldReadNoFractionWhenFfprobePrintsNoUsableRate(String text) {
    assertThat(ExactFraction.parse(text)).isEmpty();
  }

  @Test
  @DisplayName("Should write the largest unsigned 32-bit terms when the contract carries them")
  void shouldWriteTheLargestUnsigned32BitTermsWhenTheContractCarriesThem() {
    var fraction = ExactFraction.of(4_294_967_295L, 4_294_967_294L).toProto().orElseThrow();

    assertThat(Integer.toUnsignedLong(fraction.getNumerator())).isEqualTo(4_294_967_295L);
    assertThat(Integer.toUnsignedLong(fraction.getDenominator())).isEqualTo(4_294_967_294L);
  }

  @ParameterizedTest(name = "{0}/{1}")
  @CsvSource({"0, 1", "-1, 1", "4294967296, 1", "1, 4294967297"})
  @DisplayName("Should write no contract fraction when a term does not fit an unsigned 32 bits")
  void shouldWriteNoContractFractionWhenATermDoesNotFitAnUnsigned32Bits(
      long numerator, long denominator) {
    var fraction =
        new ExactFraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));

    assertThat(fraction.toProto()).isEmpty();
  }
}
