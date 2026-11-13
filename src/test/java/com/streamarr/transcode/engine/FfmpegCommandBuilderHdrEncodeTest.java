package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// streamarr/streamarr-transcode-worker#27: what the command builder needs from a 10-bit or HDR
// encode beyond what the worker session tests show.
@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder HDR Encode Tests")
class FfmpegCommandBuilderHdrEncodeTest {

  private static final StaticHdrMetadata STATIC_HDR_METADATA =
      new StaticHdrMetadata(
          PlannedCopyOutput.MasteringDisplay.builder()
              .red(new PlannedCopyOutput.Chromaticity(34000, 16000))
              .green(new PlannedCopyOutput.Chromaticity(13250, 34500))
              .blue(new PlannedCopyOutput.Chromaticity(7500, 3000))
              .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
              .maxLuminance(10_000_000)
              .minLuminance(1)
              .build(),
          new PlannedCopyOutput.ContentLightLevel(0, 0));

  private final FfmpegCommandBuilder builder =
      new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1));

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoDynamicRange.CompatibleBase.class,
      names = {"PQ", "HLG"})
  @DisplayName(
      "Should refuse an 8-bit encode when its output is PQ or HLG, which tonemapx aborts FFmpeg"
          + " for")
  void shouldRefuseAn8BitEncodeWhenItsOutputIsPqOrHlg(VideoDynamicRange.CompatibleBase base) {
    var encode =
        hevcMain10Encode(range(base))
            .staticHdrMetadata(
                base == VideoDynamicRange.CompatibleBase.PQ
                    ? Optional.of(STATIC_HDR_METADATA)
                    : Optional.empty())
            .outputPictureFormat(PictureFormat.EIGHT_BIT_420);

    assertThatThrownBy(encode::build).isInstanceOf(IllegalArgumentException.class);
  }

  // Only the conversion of an SDR or untagged source to BT.709 reads the assumed matrix (ADR 0013).
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoDynamicRange.CompatibleBase.class,
      names = {"PQ", "HLG", "NONE"})
  @DisplayName(
      "Should refuse an encode when it assumes a matrix for a source whose compatible base is PQ,"
          + " HLG or none")
  void shouldRefuseAnEncodeWhenItAssumesAMatrixForASourceWhoseCompatibleBaseIsPqHlgOrNone(
      VideoDynamicRange.CompatibleBase base) {
    var encode =
        hevcMain10Encode(VideoDynamicRange.sdr())
            .sourceDynamicRange(range(base))
            .assumedSourceMatrix(Optional.of(AssumedSourceMatrix.BT709));

    assertThatThrownBy(encode::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a PQ encode when it carries no static HDR metadata")
  void shouldRefuseAPqEncodeWhenItCarriesNoStaticHdrMetadata() {
    var encode = hevcMain10Encode(range(VideoDynamicRange.CompatibleBase.PQ));

    assertThatThrownBy(encode::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "Should hand a hardware encoder biplanar p010 after the planar reshape when a profile 5"
          + " source encodes to PQ")
  void shouldHandAHardwareEncoderBiplanarP010AfterThePlanarReshapeWhenAProfile5SourceEncodesToPq() {
    var encode =
        hevcMain10Encode(range(VideoDynamicRange.CompatibleBase.PQ))
            .sourceDynamicRange(
                new VideoDynamicRange(
                    VideoDynamicRange.CompatibleBase.NONE,
                    VideoDynamicRange.Presence.ABSENT,
                    new VideoDynamicRange.DolbyVision.Present(
                        Optional.empty(),
                        VideoDynamicRange.Presence.PRESENT,
                        VideoDynamicRange.DolbyVisionRpuPlacement.AFTER_OWN_PICTURE)))
            .staticHdrMetadata(Optional.of(STATIC_HDR_METADATA))
            .build();

    var command = commandOf(encode, "hevc_vaapi");

    assertThat(command.get(command.indexOf("-vf") + 1))
        .contains(
            "format=yuv420p10le,tonemapx=t=smpte2084:p=bt2020:m=bt2020:r=tv:format=yuv420p10le,"
                + "format=p010le,");
  }

  private List<String> commandOf(VideoDecision.Encode encode, String encoder) {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
            .videoCodecFamily("hevc")
            .videoDecision(encode)
            .audioDecision(
                AudioDecision.builder()
                    .mode(AudioMode.COPY)
                    .codec("aac")
                    .channels(2)
                    .bitrate(0L)
                    .build())
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .targetSegmentDuration(6)
            .outputFrameRate(Optional.of(new OutputFrameRate(24, 1)))
            .mediaSegmentCount(11)
            .transcodeDecision(decision)
            .encoderPreset(EncoderPreset.FASTER)
            .width(1920)
            .height(1080)
            .bitrate(5_000_000L)
            .build();
    var pipeline =
        builder.buildPipeline(
            TranscodeJob.builder().request(request).videoEncoder(encoder).build());
    return ((FfmpegPipeline.SingleProcess) pipeline).command();
  }

  private static VideoDecision.Encode.EncodeBuilder hevcMain10Encode(VideoDynamicRange output) {
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourceDynamicRange(output)
        .outputDynamicRange(output)
        .codecProfile(VideoCodecProfile.HEVC_MAIN_10)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(120)
        .codecString("hvc1.2.4.L120.90")
        .outputPictureFormat(new PictureFormat(10, ChromaSubsampling.CHROMA_420));
  }

  private static VideoDynamicRange range(VideoDynamicRange.CompatibleBase base) {
    return new VideoDynamicRange(
        base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
  }
}
