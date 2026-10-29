package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The compatible base of a stream that no configuration record names a Dolby Vision profile for,
 * which the first RPU header's profile decides as FFmpeg 8.1 guesses it. Only a guess of profile 5,
 * or of profile 10 from profile 5's fields, leaves a stream without a compatible base: every other
 * guess, and no guess, keeps the base of the signaled transfer.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor RPU Profile Guess Tests")
class FfprobeExecutorRpuProfileGuessTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("headersWithoutAProfileThatLacksABase")
  @DisplayName(
      "Should keep the signaled transfer's base when the RPU header names no profile that lacks a"
          + " compatible base")
  void shouldKeepTheSignaledTransfersBaseWhenTheRpuHeaderNamesNoProfileThatLacksACompatibleBase(
      GuessCase guess) {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        output(
                            "\"codec_name\":\"%s\",\"color_transfer\":\"bt709\""
                                .formatted(guess.codecName())))
                    .firstFrames(frames(rpuSideData(guess.header())))
                    .build())
            .getStreams(0);

    assertThat(video.getDynamicRange().getCompatibleBase())
        .isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
  }

  static Stream<Named<GuessCase>> headersWithoutAProfileThatLacksABase() {
    var profile5Fields =
        DolbyVisionRpuHeader.newBuilder()
            .setVdrRpuProfile(0)
            .setBaseLayerFullRange(true)
            .setDisableResidual(true)
            .setVdrBitDepth(12);
    return Stream.of(
        Named.of(
            "HEVC, vdr_rpu_profile 0 with a limited-range base layer",
            GuessCase.builder()
                .codecName("hevc")
                .header(profile5Fields.clone().setBaseLayerFullRange(false).build())
                .build()),
        Named.of(
            "HEVC, vdr_rpu_profile 2",
            GuessCase.builder()
                .codecName("hevc")
                .header(profile5Fields.clone().setVdrRpuProfile(2).build())
                .build()),
        Named.of(
            "a codec that carries no Dolby Vision, profile 5's fields",
            GuessCase.builder().codecName("vp9").header(profile5Fields.build()).build()));
  }

  /** The side data of a frame that carries the RPU with the header, as ffprobe prints it. */
  private static String rpuSideData(DolbyVisionRpuHeader header) {
    return """
        {"side_data_type":"Dolby Vision RPU Data"},
        {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":%d,
         "bl_video_full_range_flag":%d,"vdr_bit_depth":%d,"el_spatial_resampling_filter_flag":%d,
         "disable_residual_flag":%d}"""
        .formatted(
            header.getVdrRpuProfile(),
            header.getBaseLayerFullRange() ? 1 : 0,
            header.getVdrBitDepth(),
            header.getEnhancementLayerSpatialResamplingFilter() ? 1 : 0,
            header.getDisableResidual() ? 1 : 0);
  }

  @Builder
  private record GuessCase(String codecName, DolbyVisionRpuHeader header) {}
}
