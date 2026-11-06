package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Media Time Tests")
class MediaTimeTest {

  private static final TimeBase MPEG_TS = new TimeBase(1, 90_000);
  private static final TimeBase AVI = new TimeBase(1001, 24_000);

  @Test
  @DisplayName(
      "Should equal and hash alike another media time when both name the same instant in different"
          + " time bases")
  void shouldEqualAndHashAlikeAnotherMediaTimeWhenBothNameTheSameInstantInDifferentTimeBases() {
    // 340 ticks of 1001/24000 s are 14.1808333... s, exactly 1,276,275 MPEG-TS ticks.
    var avi = new MediaTime(340, AVI);
    var mpegTs = new MediaTime(1_276_275, MPEG_TS);

    assertThat(avi)
        .isEqualTo(mpegTs)
        .hasSameHashCodeAs(mpegTs)
        .isNotEqualTo(new MediaTime(1_276_276, MPEG_TS));
  }

  @Test
  @DisplayName("Should order media times by the instants they name when their time bases differ")
  void shouldOrderMediaTimesByTheInstantsTheyNameWhenTheirTimeBasesDiffer() {
    var start = new MediaTime(315_001, MPEG_TS);

    assertThat(start)
        .isGreaterThan(MediaTime.of(Duration.ofNanos(3_500_011_111L)))
        .isLessThan(MediaTime.of(Duration.ofNanos(3_500_011_112L)));
  }

  @Test
  @DisplayName("Should give the time in whole microseconds only when it falls on a microsecond")
  void shouldGiveTheTimeInWholeMicrosecondsOnlyWhenItFallsOnAMicrosecond() {
    assertThat(new MediaTime(315_000, MPEG_TS).wholeMicroseconds()).hasValue(3_500_000);
    assertThat(new MediaTime(315_001, MPEG_TS).wholeMicroseconds())
        .as("3,500,011.1 microseconds")
        .isEmpty();
  }

  @ParameterizedTest(name = "{0} ticks of 1/90000 s: floor {1}, ceiling {2} slots of 3 s")
  @CsvSource({
    "-270001, -2, -1",
    "-270000, -1, -1",
    "-1, -1, 0",
    "0, 0, 0",
    "269999, 0, 1",
    "270000, 1, 1"
  })
  @DisplayName(
      "Should round a time down and up to whole ticks of another time base when it lies on either"
          + " side of zero")
  void shouldRoundATimeDownAndUpToWholeTicksOfAnotherTimeBaseOnEitherSideOfZero(
      long ticks, long floor, long ceiling) {
    var time = new MediaTime(ticks, MPEG_TS);
    var slots = new TimeBase(3, 1);

    assertThat(time.floorIn(slots)).isEqualTo(floor);
    assertThat(time.ceilIn(slots)).isEqualTo(ceiling);
  }

  @Test
  @DisplayName("Should truncate a negative time toward zero when converting it to nanoseconds")
  void shouldTruncateANegativeTimeTowardZeroWhenConvertingItToNanoseconds() {
    assertThat(new MediaTime(-1, MPEG_TS).truncatedToNanoseconds())
        .isEqualTo(Duration.ofNanos(-11_111));
    assertThat(new MediaTime(1, MPEG_TS).truncatedToNanoseconds())
        .isEqualTo(Duration.ofNanos(11_111));
  }
}
