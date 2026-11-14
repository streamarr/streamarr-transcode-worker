package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.MaximumFrameRate.MeasuredFrameRate;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Maximum Frame Rate Tests")
class MaximumFrameRateTest {

  @Test
  @DisplayName("Should measure nothing when the stream has fewer than 25 pictures")
  void shouldMeasureNothingWhenTheStreamHasFewerThan25Pictures() {
    assertThat(MaximumFrameRate.measured(LongStream.range(0, 24).toArray(), 1_000)).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when every frame presents at once")
  void shouldMeasureNothingWhenEveryFramePresentsAtOnce() {
    assertThat(MaximumFrameRate.measured(new long[25], 1_000)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"0,23,25", "24,23,25", "49,23,25", "0,24,50", "24,24,50", "48,24,50"})
  @DisplayName("Should require 24 consecutive faster intervals when a burst appears anywhere")
  void shouldRequire24ConsecutiveFasterIntervalsWhenABurstAppearsAnywhere(
      int firstFastInterval, int fastIntervals, int expectedRate) {
    var times = new long[73];
    for (var interval = 0; interval < times.length - 1; interval++) {
      var fast = interval >= firstFastInterval && interval < firstFastInterval + fastIntervals;
      times[interval + 1] = times[interval] + (fast ? 20 : 40);
    }

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(ExactFraction.of(expectedRate));
  }

  @Test
  @DisplayName("Should report the rate of the times themselves when no listed rate fits them")
  void shouldReportTheRateOfTheTimesThemselvesWhenNoListedRateFitsThem() {
    // The longest interval is 9 ms; no listed rate fits its one-tick precision.
    var times = new long[25];
    for (var frame = 1; frame < times.length; frame++) {
      times[frame] = times[frame - 1] + (frame % 3 == 0 ? 9 : 8);
    }

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(ExactFraction.of(1_000, 9));
  }

  @Test
  @DisplayName("Should report 60 fps when 24 consecutive intervals each last one tick of 1/60 s")
  void shouldReport60FpsWhen24ConsecutiveIntervalsEachLastOneTickOf160S() {
    var measured = MaximumFrameRate.measured(LongStream.range(0, 25).toArray(), 60);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(ExactFraction.of(60, 1));
  }

  @Test
  @DisplayName("Should keep the slower rate when only eight consecutive intervals are faster")
  void shouldKeepTheSlowerRateWhenOnlyEightConsecutiveIntervalsAreFaster() {
    var times = new long[41];
    for (var frame = 1; frame < times.length; frame++) {
      times[frame] = times[frame - 1] + (frame > 16 && frame <= 24 ? 20 : 40);
    }

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(ExactFraction.of(25, 1));
  }

  @Test
  @DisplayName("Should order the presentation times when the samples come in decode order")
  void shouldOrderThePresentationTimesWhenTheSamplesComeInDecodeOrder() {
    var times = LongStream.range(0, 25).map(frame -> frame * 20).toArray();
    times[1] = 60;
    times[2] = 20;
    times[3] = 40;
    var original = times.clone();

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(times).containsExactly(original);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(ExactFraction.of(50, 1));
  }

  @Test
  @DisplayName("Should report no maximum when a term of the measured rate exceeds 32 bits")
  void shouldReportNoMaximumWhenATermOfTheMeasuredRateExceeds32Bits() {
    // 25 pictures one tick apart in a timescale of 2^32 - 1 ticks a second.
    var measured = MaximumFrameRate.measured(LongStream.range(0, 25).toArray(), 4_294_967_295L);

    var result = MaximumFrameRate.withMeasuredRate(probedVideo(), measured);

    assertThat(result.getMedia().getStreams(0).hasMaximumFrameRate()).isFalse();
  }

  @Test
  @DisplayName("Should report the measured rate when the chosen stream has no declared maximum")
  void shouldReportTheMeasuredRateWhenTheChosenStreamHasNoDeclaredMaximum() {
    var measured =
        MaximumFrameRate.measured(
            LongStream.range(0, 25).map(frame -> frame * 1_500).toArray(), 90_000);

    var result = MaximumFrameRate.withMeasuredRate(probedVideo(), measured);

    assertThat(result.getMedia().getStreams(0).getMaximumFrameRate())
        .isEqualTo(Fraction.newBuilder().setNumerator(60).setDenominator(1).build());
  }

  @ParameterizedTest
  @MethodSource("listedRates")
  @DisplayName("Should report a listed rate when a long stream has exact constant intervals")
  void shouldReportAListedRateWhenALongStreamHasExactConstantIntervals(ExactFraction rate) {
    var timescale = rate.numerator().longValueExact();
    var interval = rate.denominator().longValueExact();
    var times = LongStream.range(0, 1_001).map(frame -> frame * interval).toArray();

    var measured = MaximumFrameRate.measured(times, timescale);

    assertThat(measured.map(MeasuredFrameRate::reported)).contains(rate);
  }

  private static Stream<ExactFraction> listedRates() {
    return ListedFrameRates.RATES.stream();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 23})
  @DisplayName("Should keep the slower rate when duplicate presentation times interrupt a stream")
  void shouldKeepTheSlowerRateWhenDuplicatePresentationTimesInterruptAStream(int duplicates) {
    var times = new long[73];
    for (var interval = 0; interval < times.length - 1; interval++) {
      times[interval + 1] = times[interval] + (interval < duplicates ? 0 : 40);
    }

    assertThat(MaximumFrameRate.measured(times, 1_000).map(MeasuredFrameRate::reported))
        .contains(ExactFraction.of(25));
  }

  private static ProbeAttemptResult probedVideo() {
    return ProbeAttemptResult.newBuilder()
        .setMedia(
            ProbeMediaInfo.newBuilder()
                .setChosenVideoStreamIndex(0)
                .setFrameRateRule(FrameRateRuleResult.getDefaultInstance())
                .addStreams(ProbeStreamInfo.newBuilder().setIndex(0)))
        .build();
  }
}
