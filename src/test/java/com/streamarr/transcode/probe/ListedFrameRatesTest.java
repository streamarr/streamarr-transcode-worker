package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Listed Frame Rates Tests")
class ListedFrameRatesTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "120, 60",
    "120000/1001, 60000/1001",
    "100, 50",
    "90, 30",
    "72, 24",
    "240, 60",
    "144, 48",
    "80, 60",
    "6031/100, 60",
  })
  @DisplayName("Should divide a rate above 60.3 evenly when a listed rate divides it")
  void shouldDivideARateAbove603EvenlyWhenAListedRateDividesIt(String rate, String listed) {
    // The rule's examples. 80 and 60.31 fit no divisor from 2 to 4 within 0.5 %, so they take 60.
    assertThat(ListedFrameRates.snap(fraction(rate))).isEqualTo(fraction(listed));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "9, 10",
    "1/2, 10",
    "6001/100, 60",
    "603/10, 60",
    "239/10, 24000/1001",
    "49/4, 25/2",
    "24, 24",
  })
  @DisplayName("Should take the nearest listed rate when the rate lies from 10 to 60.3")
  void shouldTakeTheNearestListedRateWhenTheRateLiesFrom10To603(String rate, String listed) {
    // Below 10 becomes 10, and from 60 to 60.3 counts as 60. 12.25 lies halfway between 12 and
    // 12.5, and a tie takes the higher rate.
    assertThat(ListedFrameRates.snap(fraction(rate))).isEqualTo(fraction(listed));
  }

  private static ExactFraction fraction(String text) {
    return ExactFraction.parse(text).orElseThrow();
  }
}
