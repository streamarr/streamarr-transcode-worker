package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.transcode.engine.ProductionTiming;
import com.streamarr.transcode.fixtures.SteppedClock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Production Speed Window Tests")
class ProductionSpeedWindowTest {

  private final SteppedClock clock = new SteppedClock();
  private final ProductionSpeedWindow window = new ProductionSpeedWindow(clock);

  @Test
  @DisplayName("Should judge nothing when the attempt has produced for less than 15 s")
  void shouldJudgeNothingWhenTheAttemptHasProducedForLessThan15S() {
    produce(4, 3, 3);

    assertThat(window.judgedSpeed()).isEmpty();
  }

  @Test
  @DisplayName("Should judge nothing when the attempt has produced one media segment")
  void shouldJudgeNothingWhenTheAttemptHasProducedOneMediaSegment() {
    produce(1, 30, 20);

    assertThat(window.judgedSpeed()).isEmpty();
  }

  @Test
  @DisplayName("Should weight the speed by media seconds over the segments of the last 15 s")
  void shouldWeightTheSpeedByMediaSecondsOverTheSegmentsOfTheLast15S() {
    produce(5, 3, 3);
    assertThat(window.judgedSpeed()).hasValue(1.0);

    produce(10, 3, 1);

    assertThat(window.judgedSpeed())
        .as("Two slow segments finished within the window, beside ten fast ones")
        .hasValueCloseTo(36.0 / 16.0, within(1e-9));
  }

  @Test
  @DisplayName(
      "Should average the last 2 media segments when merged-slot segments outlast the window")
  void shouldAverageTheLast2MediaSegmentsWhenMergedSlotSegmentsOutlastTheWindow() {
    produce(1, 30, 20);
    produce(1, 10, 20);
    produce(1, 30, 20);

    assertThat(window.judgedSpeed()).hasValueCloseTo(40.0 / 40.0, within(1e-9));
  }

  @Test
  @DisplayName(
      "Should judge the attempt as fast when its recent media segments took no production time")
  void shouldJudgeTheAttemptAsFastWhenItsRecentMediaSegmentsTookNoProductionTime() {
    produce(1, 30, 20);
    clock.advance(Duration.ofSeconds(16));

    produce(2, 3, 0);

    assertThat(window.judgedSpeed()).hasValue(Double.POSITIVE_INFINITY);
  }

  private void produce(int segments, int mediaSeconds, int productionSeconds) {
    for (var segment = 0; segment < segments; segment++) {
      clock.advance(Duration.ofSeconds(productionSeconds));
      window.add(
          new ProductionTiming(
              Duration.ofSeconds(mediaSeconds), Duration.ofSeconds(productionSeconds)));
    }
  }
}
