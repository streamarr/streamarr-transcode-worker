package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
class SegmentIntervalsTest {

  private static final long MILLISECONDS = 1000;

  @ParameterizedTest(name = "{0} ms is in segment {1}")
  @CsvSource({"0, 0", "5999, 0", "6000, 1", "-1, -1", "-6000, -1", "-6001, -2"})
  @DisplayName(
      "Should place a presentation time in the segment whose interval holds it when the grid is"
          + " plain")
  void shouldPlaceAPresentationTimeInTheSegmentWhoseIntervalHoldsItWhenTheGridIsPlain(
      long milliseconds, long number) {
    var intervals = SegmentIntervals.onPlainGrid(6);

    assertThat(intervals.numberAt(milliseconds, MILLISECONDS)).isEqualTo(number);
  }

  @Test
  @DisplayName("Should end a segment's interval where the next one starts when the grid is plain")
  void shouldEndASegmentsIntervalWhereTheNextOneStartsWhenTheGridIsPlain() {
    var intervals = SegmentIntervals.onPlainGrid(6);

    assertThat(intervals.endOf(10, MILLISECONDS)).isEqualTo(66_000);
    assertThat(intervals.numberAt(intervals.endOf(10, MILLISECONDS), MILLISECONDS)).isEqualTo(11);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -6})
  @DisplayName("Should reject a plain grid when its period is not positive")
  void shouldRejectAPlainGridWhenItsPeriodIsNotPositive(int periodSeconds) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SegmentIntervals.onPlainGrid(periodSeconds));
  }
}
