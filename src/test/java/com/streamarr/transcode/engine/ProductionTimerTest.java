package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fixtures.SteppedClock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Production Timer Tests")
class ProductionTimerTest {

  private final SteppedClock clock = new SteppedClock();
  private final ProductionTimer timer = new ProductionTimer(clock);

  @Test
  @DisplayName("Should leave out overlapping pauses once when the reader and the worker both pause")
  void shouldLeaveOutOverlappingPausesOnceWhenTheReaderAndTheWorkerBothPause() {
    clock.advance(Duration.ofSeconds(1));
    timer.pause();
    clock.advance(Duration.ofSeconds(2));
    timer.pause();
    clock.advance(Duration.ofSeconds(3));
    timer.resume();
    clock.advance(Duration.ofSeconds(4));
    timer.resume();
    clock.advance(Duration.ofSeconds(5));

    assertThat(timer.lap()).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName(
      "Should leave a pause that lasts through a lap out of both laps when the reader laps on"
          + " output written before the pause")
  void
      shouldLeaveAPauseThatLastsThroughALapOutOfBothLapsWhenTheReaderLapsOnOutputWrittenBeforeIt() {
    clock.advance(Duration.ofSeconds(1));
    timer.pause();
    clock.advance(Duration.ofSeconds(2));

    var first = timer.lap();
    clock.advance(Duration.ofSeconds(3));
    timer.resume();
    clock.advance(Duration.ofSeconds(4));

    assertThat(first).isEqualTo(Duration.ofSeconds(1));
    assertThat(timer.lap()).isEqualTo(Duration.ofSeconds(4));
  }
}
