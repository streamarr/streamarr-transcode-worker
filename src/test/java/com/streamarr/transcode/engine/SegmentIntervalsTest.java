package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
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
  private static final long AUDIO_TIMESCALE = 48_000;
  private static final long NANOSECONDS = 1_000_000_000;
  // Keyframes at 3.5 s, 6.5 s and 9.5 s in an MPEG-TS source's 1/90000 time base.
  private static final ExactSegmentStarts MPEG_TS_KEYFRAMES_AT_HALF_SECONDS =
      new ExactSegmentStarts(List.of(0L, 315_000L, 585_000L, 855_000L), 1, 90_000);

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
    assertThat(intervals.endOf(3, MILLISECONDS))
        .as("without an advertised end, runs to the end of the media")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should end the last listed segment's audio at the advertised end and number later audio"
          + " after it when the job carries an advertised end")
  void shouldEndTheLastListedSegmentsAudioAtTheAdvertisedEndWhenTheJobCarriesOne() {
    var intervals =
        SegmentIntervals.onSegmentList(3, TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS)
            .withLastListedSegmentEndingAt(Duration.ofMillis(34_500));

    assertThat(intervals.endOf(3, MILLISECONDS)).hasValue(34_500);
    assertThat(intervals.numberOfAudioSampleAt(34_499, MILLISECONDS)).isEqualTo(3);
    assertThat(intervals.numberOfAudioSampleAt(34_500, MILLISECONDS))
        .as("audio after the last listed segment")
        .isEqualTo(4);
    assertThat(intervals.endOf(4, MILLISECONDS)).as("past the list").isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep a keyframe past the advertised end in the last listed segment when the job"
          + " carries an advertised end")
  void shouldKeepAKeyframePastTheAdvertisedEndInTheLastListedSegmentWhenTheJobCarriesOne() {
    var intervals =
        SegmentIntervals.onSegmentList(3, TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS)
            .withLastListedSegmentEndingAt(Duration.ofMillis(34_500));

    assertThat(intervals.numberAt(34_500, MILLISECONDS)).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should end the last listed segment at the first tick at or after the advertised end when"
          + " that end falls between two ticks")
  void shouldEndTheLastListedSegmentAtTheFirstTickAtOrAfterTheAdvertisedEndBetweenTwoTicks() {
    var intervals =
        SegmentIntervals.onSegmentList(3, TEN_SECOND_KEYFRAMES_ON_THREE_SECOND_SLOTS)
            .withLastListedSegmentEndingAt(Duration.ofMillis(34_500).plusNanos(1));

    assertThat(intervals.endOf(3, MILLISECONDS)).hasValue(34_501);
    assertThat(intervals.numberOfAudioSampleAt(34_500, MILLISECONDS)).isEqualTo(3);
    assertThat(intervals.numberOfAudioSampleAt(34_501, MILLISECONDS)).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "Should keep each segment of the plain grid one period long when the job carries an"
          + " advertised end")
  void shouldKeepEachSegmentOfThePlainGridOnePeriodLongWhenTheJobCarriesAnAdvertisedEnd() {
    var intervals =
        SegmentIntervals.onPlainGrid(3).withLastListedSegmentEndingAt(Duration.ofMillis(34_500));

    assertThat(intervals.endOf(11, MILLISECONDS)).hasValue(36_000);
    assertThat(intervals.numberOfAudioSampleAt(34_500, MILLISECONDS)).isEqualTo(11);
  }

  @Test
  @DisplayName("Should use the plain grid when the segment list is empty")
  void shouldUseThePlainGridWhenTheSegmentListIsEmpty() {
    var intervals = SegmentIntervals.onSegmentList(3, List.of());

    assertThat(intervals.numberAt(30_000, MILLISECONDS)).isEqualTo(10);
    assertThat(intervals.startOf(10, MILLISECONDS)).isEqualTo(30_000);
    assertThat(intervals.endOf(10, MILLISECONDS)).hasValue(33_000);
  }

  @ParameterizedTest(name = "audio sample at {0} of 48 kHz is in segment {1}")
  @CsvSource({"0, 0", "167999, 0", "168000, 1", "311999, 1", "312000, 2", "456000, 3", "-1, -1"})
  @DisplayName(
      "Should open each segment at the audio sample on its exact start when the starts are MPEG-TS"
          + " ticks that land on a 48 kHz sample")
  void shouldOpenEachSegmentAtTheAudioSampleOnItsExactStartWhenTheStartsLandOnASample(
      long presentationTime, long number) {
    var intervals = SegmentIntervals.onExactStarts(3, MPEG_TS_KEYFRAMES_AT_HALF_SECONDS);

    assertThat(intervals.numberAt(presentationTime, AUDIO_TIMESCALE)).isEqualTo(number);
  }

  @Test
  @DisplayName(
      "Should place a time in the segment it reaches when the exact start falls between two ticks"
          + " of the time's timescale")
  void shouldPlaceATimeInTheSegmentItReachesWhenTheExactStartFallsBetweenTwoTicks() {
    // 315,001 / 90,000 s is 168,000.53 ticks at 48 kHz and 3,500,011,111.1 ns.
    var intervals =
        SegmentIntervals.onExactStarts(3, new ExactSegmentStarts(List.of(0L, 315_001L), 1, 90_000));

    assertThat(intervals.numberAt(168_000, AUDIO_TIMESCALE)).isZero();
    assertThat(intervals.numberAt(168_001, AUDIO_TIMESCALE)).isEqualTo(1);
    assertThat(intervals.endOf(0, AUDIO_TIMESCALE)).as("its first tick").hasValue(168_001);
    assertThat(intervals.numberAt(3_500_011_111L, NANOSECONDS)).isZero();
    assertThat(intervals.numberAt(3_500_011_112L, NANOSECONDS)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should open a segment at the audio sample on its exact start when the start is an AVI tick"
          + " of a fractional time base")
  void shouldOpenASegmentAtTheAudioSampleOnItsExactStartWhenTheStartIsAFractionalAviTick() {
    // 85 ticks of 1001/24000 s are 3.5452916... s, exactly 170,170 ticks at 48 kHz.
    var intervals =
        SegmentIntervals.onExactStarts(3, new ExactSegmentStarts(List.of(0L, 85L), 1001, 24_000));

    assertThat(intervals.numberAt(170_169, AUDIO_TIMESCALE)).isZero();
    assertThat(intervals.numberAt(170_170, AUDIO_TIMESCALE)).isEqualTo(1);
    assertThat(intervals.endOf(0, AUDIO_TIMESCALE)).hasValue(170_170);
  }

  @Test
  @DisplayName(
      "Should end the last exact segment's audio at the advertised end when the job carries one")
  void shouldEndTheLastExactSegmentsAudioAtTheAdvertisedEndWhenTheJobCarriesOne() {
    var intervals =
        SegmentIntervals.onExactStarts(3, MPEG_TS_KEYFRAMES_AT_HALF_SECONDS)
            .withLastListedSegmentEndingAt(Duration.ofMillis(10_250));

    assertThat(intervals.endOf(2, AUDIO_TIMESCALE)).hasValue(456_000);
    assertThat(intervals.endOf(3, AUDIO_TIMESCALE)).hasValue(492_000);
    assertThat(intervals.numberOfAudioSampleAt(491_999, AUDIO_TIMESCALE)).isEqualTo(3);
    assertThat(intervals.numberOfAudioSampleAt(492_000, AUDIO_TIMESCALE)).isEqualTo(4);
  }

  @ParameterizedTest(name = "{0} ticks of 1/90000 s: at {1}, just before {2}")
  @CsvSource({
    "-1, -1, -1",
    "0, 0, -1",
    "314999, 0, 0",
    "315000, 1, 0",
    "315001, 1, 1",
    "855000, 3, 2",
    "9000000, 3, 3"
  })
  @DisplayName(
      "Should number an exact time, and the instants just before it, by the exact starts when the"
          + " job carries exact starts")
  void shouldNumberAnExactTimeAndTheInstantsJustBeforeItByTheExactStarts(
      long ticks, long numberAt, long numberJustBefore) {
    var intervals = SegmentIntervals.onExactStarts(3, MPEG_TS_KEYFRAMES_AT_HALF_SECONDS);
    var time = new MediaTime(ticks, new TimeBase(1, 90_000));

    assertThat(intervals.numberAt(time)).isEqualTo(numberAt);
    assertThat(intervals.numberJustBefore(time)).isEqualTo(numberJustBefore);
  }

  @ParameterizedTest(name = "{0} ms: at {1}, just before {2}")
  @CsvSource({"-6001, -2, -2", "-6000, -1, -2", "-1, -1, -1", "0, 0, -1", "6000, 1, 0"})
  @DisplayName(
      "Should number an exact time, and the instants just before it, by whole periods when the grid"
          + " is plain")
  void shouldNumberAnExactTimeAndTheInstantsJustBeforeItByWholePeriodsWhenTheGridIsPlain(
      long milliseconds, long numberAt, long numberJustBefore) {
    var intervals = SegmentIntervals.onPlainGrid(6);
    var time = MediaTime.of(Duration.ofMillis(milliseconds));

    assertThat(intervals.numberAt(time)).isEqualTo(numberAt);
    assertThat(intervals.numberJustBefore(time)).isEqualTo(numberJustBefore);
  }

  @Test
  @DisplayName(
      "Should end each exact segment at the next one's start, and the last at the advertised end,"
          + " when the job carries exact starts")
  void shouldEndEachExactSegmentAtTheNextOnesStartAndTheLastAtTheAdvertisedEnd() {
    var intervals = SegmentIntervals.onExactStarts(3, MPEG_TS_KEYFRAMES_AT_HALF_SECONDS);

    assertThat(intervals.endTimeOf(0)).contains(new MediaTime(315_000, new TimeBase(1, 90_000)));
    assertThat(intervals.endTimeOf(3)).as("without an advertised end").isEmpty();
    assertThat(intervals.withLastListedSegmentEndingAt(Duration.ofMillis(10_250)).endTimeOf(3))
        .contains(MediaTime.of(Duration.ofMillis(10_250)));
    assertThat(intervals.endTimeOf(4)).as("past the list").isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail instead of wrapping when comparing an exact start with a time overflows 64 bits")
  void shouldFailInsteadOfWrappingWhenComparingAnExactStartWithATimeOverflowsSixtyFourBits() {
    var intervals =
        SegmentIntervals.onExactStarts(3, new ExactSegmentStarts(List.of(0L, 1L << 40), 1, 90_000));

    assertThatExceptionOfType(ArithmeticException.class)
        .isThrownBy(() -> intervals.numberAt(1, 1L << 32));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -6})
  @DisplayName("Should reject a plain grid when its period is not positive")
  void shouldRejectAPlainGridWhenItsPeriodIsNotPositive(int periodSeconds) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SegmentIntervals.onPlainGrid(periodSeconds));
  }
}
