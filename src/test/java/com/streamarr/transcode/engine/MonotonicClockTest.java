package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fixtures.SteppedClock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Monotonic Clock Tests")
class MonotonicClockTest {

  @Test
  @DisplayName("Should never read an earlier time when the system clock is read again")
  void shouldNeverReadAnEarlierTimeWhenTheSystemClockIsReadAgain() {
    var clock = MonotonicClock.system();
    var first = clock.nanoTime();

    assertThat(clock.since(first)).isGreaterThanOrEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("Should measure the time since a reading when the clock advances")
  void shouldMeasureTheTimeSinceAReadingWhenTheClockAdvances() {
    var clock = new SteppedClock();
    var start = clock.nanoTime();

    clock.advance(Duration.ofSeconds(7));

    assertThat(clock.since(start)).isEqualTo(Duration.ofSeconds(7));
  }
}
