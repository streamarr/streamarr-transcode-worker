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
  @DisplayName("Should average every interval when the stream has fewer than 24 of them")
  void shouldAverageEveryIntervalWhenTheStreamHasFewerThan24OfThem() {
    // Four intervals of 30 and 50 ms: 160 ms in all.
    var measured = MaximumFrameRate.measured(new long[] {0, 30, 80, 110, 160}, 1_000);

    assertThat(measured).contains(ExactFraction.of(25, 1));
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
