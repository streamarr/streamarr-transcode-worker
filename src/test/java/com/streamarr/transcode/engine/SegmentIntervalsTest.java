package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Segment Intervals Tests")
class SegmentIntervalsTest {

  private static final long MILLISECONDS = 1000;
  // Keyframes 10 s apart on 3 s slots: the slots without one merge into the segment before them.
  private static final List<Long> TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS =
      List.of(0L, 3L, 6L, 10L);

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

    assertThat(intervals.endOf(10, MILLISECONDS)).hasValue(66_000);
    assertThat(intervals.numberAt(66_000, MILLISECONDS)).isEqualTo(11);
  }

  @ParameterizedTest(name = "{0} ms is in segment {1}")
  @CsvSource({
    "0, 0",
    "8999, 0",
    "9000, 1",
    "17999, 1",
    "18000, 2",
    "29999, 2",
    "30000, 3",
    "3600000, 3",
    "-1, -1"
  })
  @DisplayName(
      "Should place a presentation time in the segment whose listed interval holds it when slots"
          + " merge")
  void shouldPlaceAPresentationTimeInTheSegmentWhoseListedIntervalHoldsItWhenSlotsMerge(
      long milliseconds, long number) {
    var intervals = SegmentIntervals.onSegmentList(3, TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS);

    assertThat(intervals.numberAt(milliseconds, MILLISECONDS)).isEqualTo(number);
  }

  @Test
  @DisplayName(
      "Should start and end each listed segment at its slots and leave the last one open when"
          + " slots merge")
  void shouldStartAndEndEachListedSegmentAtItsSlotsAndLeaveTheLastOneOpenWhenSlotsMerge() {
    var intervals = SegmentIntervals.onSegmentList(3, TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS);

    assertThat(intervals.startOf(2, MILLISECONDS)).isEqualTo(18_000);
    assertThat(intervals.endOf(2, MILLISECONDS)).hasValue(30_000);
    assertThat(intervals.startOf(3, MILLISECONDS)).isEqualTo(30_000);
    assertThat(intervals.endOf(3, MILLISECONDS)).as("runs to the end of the media").isEmpty();
  }

  @Test
  @DisplayName("Should use the plain grid when the segment list is empty")
  void shouldUseThePlainGridWhenTheSegmentListIsEmpty() {
    var intervals = SegmentIntervals.onSegmentList(3, List.of());

    assertThat(intervals.numberAt(30_000, MILLISECONDS)).isEqualTo(10);
    assertThat(intervals.startOf(10, MILLISECONDS)).isEqualTo(30_000);
    assertThat(intervals.endOf(10, MILLISECONDS)).hasValue(33_000);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -6})
  @DisplayName("Should reject a plain grid when its period is not positive")
  void shouldRejectAPlainGridWhenItsPeriodIsNotPositive(int periodSeconds) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SegmentIntervals.onPlainGrid(periodSeconds));
  }
}
