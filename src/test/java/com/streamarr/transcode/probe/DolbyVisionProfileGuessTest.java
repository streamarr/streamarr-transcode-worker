package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Dolby Vision Profile Guess Tests")
class DolbyVisionProfileGuessTest {

  @ParameterizedTest(
      name =
          "vdr_rpu_profile {0}, full range {1}, resampling filter {2}, disable residual {3},"
              + " vdr_bit_depth {4}")
  @CsvSource({
    "0, true, false, true, 12, 5",
    "1, false, true, false, 12, 7",
    "1, false, true, false, 10, 4",
    "1, false, false, true, 12, 8",
    "1, true, true, true, 12, 8",
    "1, false, false, false, 10, 8"
  })
  @DisplayName("Should guess the profile as FFmpeg does when an HEVC RPU header names one")
  void shouldGuessTheProfileAsFfmpegDoesWhenAnHevcRpuHeaderNamesOne(
      int vdrRpuProfile,
      boolean fullRange,
      boolean resamplingFilter,
      boolean disableResidual,
      int vdrBitDepth,
      int profile) {
    var header =
        DolbyVisionRpuHeader.newBuilder()
            .setVdrRpuProfile(vdrRpuProfile)
            .setBaseLayerFullRange(fullRange)
            .setEnhancementLayerSpatialResamplingFilter(resamplingFilter)
            .setDisableResidual(disableResidual)
            .setVdrBitDepth(vdrBitDepth)
            .build();

    assertThat(StreamCodec.HEVC.guessDolbyVisionProfile(header)).hasValue(profile);
  }

  @ParameterizedTest(name = "vdr_rpu_profile {0}, full range {1}")
  @CsvSource({"0, false", "2, true", "15, false"})
  @DisplayName("Should guess no profile when an HEVC RPU header names none")
  void shouldGuessNoProfileWhenAnHevcRpuHeaderNamesNone(int vdrRpuProfile, boolean fullRange) {
    var header =
        DolbyVisionRpuHeader.newBuilder()
            .setVdrRpuProfile(vdrRpuProfile)
            .setBaseLayerFullRange(fullRange)
            .build();

    assertThat(StreamCodec.HEVC.guessDolbyVisionProfile(header)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"av1, 10", "h264, 9"})
  @DisplayName("Should guess the codec's only profile whatever the header holds")
  void shouldGuessTheCodecsOnlyProfileWhateverTheHeaderHolds(String codec, int profile) {
    var profileFiveFields =
        DolbyVisionRpuHeader.newBuilder().setVdrRpuProfile(0).setBaseLayerFullRange(true).build();

    assertThat(StreamCodec.named(codec).guessDolbyVisionProfile(profileFiveFields))
        .hasValue(profile);
  }

  @Test
  @DisplayName("Should guess no profile when the codec carries no Dolby Vision")
  void shouldGuessNoProfileWhenTheCodecCarriesNoDolbyVision() {
    assertThat(
            StreamCodec.named("vp9")
                .guessDolbyVisionProfile(DolbyVisionRpuHeader.getDefaultInstance()))
        .isEqualTo(OptionalInt.empty());
  }
}
