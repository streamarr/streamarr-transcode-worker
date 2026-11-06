package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Maximum Frame Rate Tests")
class MaximumFrameRateTest {

  @Test
  @DisplayName("Should measure nothing when the stream has fewer than two frames")
  void shouldMeasureNothingWhenTheStreamHasFewerThanTwoFrames() {
    assertThat(MaximumFrameRate.measured(new long[] {0}, 1_000)).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when every frame presents at once")
  void shouldMeasureNothingWhenEveryFramePresentsAtOnce() {
    assertThat(MaximumFrameRate.measured(new long[] {500, 500, 500}, 1_000)).isEmpty();
  }

  @Test
  @DisplayName("Should report the rate of a single fast interval when the times are exact")
  void shouldReportTheRateOfASingleFastIntervalWhenTheTimesAreExact() {
    // 25 fps in 90 kHz ticks, with one frame that lasts 20 ms.
    var measured = MaximumFrameRate.measured(new long[] {0, 3_600, 5_400, 9_000}, 90_000);

    assertThat(measured).contains(ExactFraction.of(50, 1));
  }

  @Test
  @DisplayName("Should report the rate of the times themselves when no listed rate fits them")
  void shouldReportTheRateOfTheTimesThemselvesWhenNoListedRateFitsThem() {
    // 120 fps in 1 ms ticks: intervals of 8, 8 and 9 ms. 23 intervals that span 191 ms prove the
    // most. No listed rate lies between 23 frames in 192 ms and 23 in 190 ms, so the maximum is 23
    // frames in 191 ms, 120.4 fps.
    var times = new long[25];
    for (var frame = 1; frame < times.length; frame++) {
      times[frame] = times[frame - 1] + (frame % 3 == 0 ? 9 : 8);
    }

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(measured).contains(ExactFraction.of(23_000, 191));
  }

  @Test
  @DisplayName("Should report 60 fps when one frame lasts a single tick of 1/60 s")
  void shouldReport60FpsWhenOneFrameLastsASingleTickOf160S() {
    // 30 fps with one frame of a single tick: that interval allows any rate from 30 fps up.
    var measured = MaximumFrameRate.measured(new long[] {0, 2, 3, 5}, 60);

    assertThat(measured).contains(ExactFraction.of(60, 1));
  }

  @Test
  @DisplayName("Should report a burst of eight fast frames when 1 ms ticks round each time")
  void shouldReportABurstOfEightFastFramesWhen1MsTicksRoundEachTime() {
    // 25 fps with eight intervals at 50 fps: averaged over 24 intervals, the burst would read 30.
    var times = new long[41];
    for (var frame = 1; frame < times.length; frame++) {
      times[frame] = times[frame - 1] + (frame > 16 && frame <= 24 ? 20 : 40);
    }

    var measured = MaximumFrameRate.measured(times, 1_000);

    assertThat(measured).contains(ExactFraction.of(50, 1));
  }

  @Test
  @DisplayName("Should order the presentation times when the samples come in decode order")
  void shouldOrderThePresentationTimesWhenTheSamplesComeInDecodeOrder() {
    // An I-frame, a P-frame and two B-frames that present between them, every 20 ms.
    var measured = MaximumFrameRate.measured(new long[] {0, 60, 20, 40, 80}, 1_000);

    assertThat(measured).contains(ExactFraction.of(50, 1));
  }

  @Test
  @DisplayName("Should report no maximum when a term of the measured rate exceeds 32 bits")
  void shouldReportNoMaximumWhenATermOfTheMeasuredRateExceeds32Bits() {
    // Two frames one tick apart in a timescale of 2^32 - 1 ticks a second.
    var measured = MaximumFrameRate.measured(new long[] {0, 1}, 4_294_967_295L);

    var result = MaximumFrameRate.withMeasuredRate(probedVideo(), measured);

    assertThat(result.getMedia().getStreams(0).hasMaximumFrameRate()).isFalse();
  }

  @Test
  @DisplayName("Should report the measured rate when the chosen stream has no declared maximum")
  void shouldReportTheMeasuredRateWhenTheChosenStreamHasNoDeclaredMaximum() {
    var result =
        MaximumFrameRate.withMeasuredRate(probedVideo(), Optional.of(ExactFraction.of(60, 1)));

    assertThat(result.getMedia().getStreams(0).getMaximumFrameRate())
        .isEqualTo(Fraction.newBuilder().setNumerator(60).setDenominator(1).build());
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
