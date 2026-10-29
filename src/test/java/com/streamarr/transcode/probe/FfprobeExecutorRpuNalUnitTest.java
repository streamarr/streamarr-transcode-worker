package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which NAL units of a stream's first packets the probe counts as RPUs and as slices. An RPU is a
 * NAL unit of the codec's RPU type whose payload starts with rpu_nal_prefix 25: type 28 for H.264,
 * and type 62 on layer 0 with TemporalId 0 for HEVC. A slice has type 1 to 5 for H.264.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor RPU NAL Unit Tests")
class FfprobeExecutorRpuNalUnitTest {

  @Nested
  @DisplayName("HEVC")
  class HevcTests {

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when every type 62 NAL unit's payload starts with a prefix"
            + " other than 25")
    void shouldRuleDolbyVisionOutWhenEveryType62NalUnitsPayloadStartsWithAPrefixOtherThan25() {
      var otherPrefix = "7c 01 18 08 09 00 40 61 b6 50 6f 00 3f";

      var video = probedWithEachSampleHolding(otherPrefix);

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName("Should rule Dolby Vision out when every type 62 NAL unit ends after its header")
    void shouldRuleDolbyVisionOutWhenEveryType62NalUnitEndsAfterItsHeader() {
      var video = probedWithEachSampleHolding("7c 01");

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    private static ProbeStreamInfo probedWithEachSampleHolding(String unit) {
      return HevcSamples.probed(
          HevcSamples.RECORD,
          lengthPrefixed(HevcSamples.IDR_SLICE, unit),
          lengthPrefixed(HevcSamples.SLICE, unit),
          lengthPrefixed(HevcSamples.SLICE, unit));
    }
  }

  @Nested
  @DisplayName("H.264")
  class H264Tests {

    private static final String RPU = "1c 19 08 09 08 40 61 59 41 bc 03 fe";

    @ParameterizedTest(name = "nal_ref_idc {0}")
    @CsvSource({"1, 3c", "2, 5c", "3, 7c"})
    @DisplayName(
        "Should report the RPU present when a type 28 NAL unit with prefix 25 is a reference")
    void shouldReportTheRpuPresentWhenAType28NalUnitWithPrefix25IsAReference(
        int nalRefIdc, String header) {
      var rpu = RPU.replaceFirst("^1c", header);

      var video =
          AvcSamples.probed(
              AvcSamples.RECORD,
              lengthPrefixed(AvcSamples.IDR_SLICE, rpu),
              lengthPrefixed(AvcSamples.SLICE, rpu),
              lengthPrefixed(AvcSamples.SLICE, rpu));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .as("nal_ref_idc %d", nalRefIdc)
          .isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when a NAL unit of type 0 comes ahead of the"
            + " RPU")
    void shouldReportThatEveryRpuStartsItsSampleWhenANalUnitOfType0ComesAheadOfTheRpu() {
      // Type 0 is unspecified, and no slice: FFmpeg's H.264 decoder ignores it.
      var unspecified = "00 9a 24";

      var video =
          AvcSamples.probed(
              AvcSamples.RECORD,
              lengthPrefixed(AvcSamples.IDR_SLICE),
              lengthPrefixed(unspecified, RPU, AvcSamples.SLICE),
              lengthPrefixed(unspecified, RPU, AvcSamples.SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }
  }
}
