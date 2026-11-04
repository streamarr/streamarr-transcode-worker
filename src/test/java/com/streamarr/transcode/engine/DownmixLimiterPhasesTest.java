package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Downmix Limiter Phases Tests")
class DownmixLimiterPhasesTest {

  // The SHA-256 of the eleven vectors in resource order, each tap as little-endian binary64.
  private static final String MEASURED_VECTORS_SHA_256 =
      "b1cf1860de2aa07ac7d3577d83ab4525436abdaa1dc05f239cf45793c1d85166";
  private static final int KAISER_TAPS = 128;
  private static final int ANNEX_TWO_ARRAY_TAPS = 70;
  private static final int ANNEX_TWO_OFFSET = 58;

  @Test
  @DisplayName("Should hold the measured vectors when the resource is read")
  void shouldHoldTheMeasuredVectorsWhenTheResourceIsRead() throws Exception {
    var vectors = DownmixLimiterPhases.vectors();

    assertThat(vectors.keySet())
        .containsExactly("k1", "k2", "k3", "k4", "k5", "k6", "k7", "bs0", "bs1", "bs2", "bs3");
    assertThat(vectors.values().stream().mapToInt(taps -> taps.length))
        .containsExactly(128, 128, 128, 128, 128, 128, 128, 70, 70, 70, 70);
    var bytes = ByteBuffer.allocate(1_176 * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    vectors.values().forEach(taps -> Arrays.stream(taps).forEach(bytes::putDouble));
    var digest = MessageDigest.getInstance("SHA-256").digest(bytes.array());
    assertThat(HexFormat.of().formatHex(digest)).isEqualTo(MEASURED_VECTORS_SHA_256);
  }

  @Test
  @DisplayName("Should pass DC at unity and delay by 64 minus an eighth per phase when Kaiser")
  void shouldPassDcAtUnityAndDelayBy64MinusAnEighthPerPhaseWhenKaiser() {
    var vectors = DownmixLimiterPhases.vectors();

    for (var phase = 1; phase <= 7; phase++) {
      var taps = vectors.get("k" + phase);
      assertThat(taps).hasSize(KAISER_TAPS);
      assertThat(Arrays.stream(taps).sum())
          .as("DC gain of k%d", phase)
          .isCloseTo(1.0, within(1e-12));
      assertThat(groupDelay(taps, 0.01))
          .as("group delay of k%d", phase)
          .isCloseTo(64 - phase / 8.0, within(1e-3));
    }
  }

  @Test
  @DisplayName("Should hold Table 2's taps after 58 zeros when the array is an Annex 2 phase")
  void shouldHoldTable2sTapsAfter58ZerosWhenTheArrayIsAnAnnex2Phase() {
    var vectors = DownmixLimiterPhases.vectors();

    for (var phase = 0; phase < 4; phase++) {
      var taps = vectors.get("bs" + phase);
      assertThat(taps).hasSize(ANNEX_TWO_ARRAY_TAPS);
      assertThat(Arrays.copyOfRange(taps, 0, ANNEX_TWO_OFFSET)).containsOnly(0.0);
      assertThat(Arrays.copyOfRange(taps, ANNEX_TWO_OFFSET, ANNEX_TWO_ARRAY_TAPS))
          .containsExactly(AnnexTwoMeter.PHASES[phase]);
    }
  }

  @Test
  @DisplayName("Should give the first stage the eleven vectors in resource order")
  void shouldGiveTheFirstStageTheElevenVectorsInResourceOrder() {
    var vectors = DownmixLimiterPhases.vectors().values().stream().toList();

    var firstStage = DownmixLimiterPhases.firstStage();

    assertThat(firstStage).hasSize(11);
    IntStream.range(0, 11)
        .forEach(index -> assertThat(firstStage.get(index)).containsExactly(vectors.get(index)));
  }

  @Test
  @DisplayName("Should give the second stage Table 2's four phases as published")
  void shouldGiveTheSecondStageTable2sFourPhasesAsPublished() {
    var secondStage = DownmixLimiterPhases.secondStage();

    assertThat(secondStage).hasSize(4);
    IntStream.range(0, 4)
        .forEach(
            phase ->
                assertThat(secondStage.get(phase)).containsExactly(AnnexTwoMeter.PHASES[phase]));
  }

  // The group delay at a fraction of the sample rate: Re(Σ n h[n] e^-jwn / Σ h[n] e^-jwn).
  private static double groupDelay(double[] taps, double frequency) {
    var omega = 2 * Math.PI * frequency;
    double re = 0;
    double im = 0;
    double weightedRe = 0;
    double weightedIm = 0;
    for (var n = 0; n < taps.length; n++) {
      var cos = Math.cos(omega * n);
      var sin = -Math.sin(omega * n);
      re += taps[n] * cos;
      im += taps[n] * sin;
      weightedRe += n * taps[n] * cos;
      weightedIm += n * taps[n] * sin;
    }

    return (weightedRe * re + weightedIm * im) / (re * re + im * im);
  }
}
