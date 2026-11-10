package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    var command = commandOf(profile5Encode(), "hevc_vaapi");

    assertThat(command.get(command.indexOf("-vf") + 1))
        .contains(
            "format=yuv420p10le,tonemapx=t=smpte2084:p=bt2020:m=bt2020:r=tv:format=yuv420p10le,"
                + "format=p010le,");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoDynamicRange.CompatibleBase.class,
      names = {"PQ", "HLG"})
  @DisplayName(
      "Should encode Main 10 from biplanar p010 with the output's BT.2020 colour description and"
          + " the keyframe settings of an SDR encode when hevc_nvenc encodes PQ or HLG")
  void shouldEncodeMain10FromP010WithTheOutputsColourDescriptionWhenHevcNvencEncodesPqOrHlg(
      VideoDynamicRange.CompatibleBase base) {
    var transfer = base == VideoDynamicRange.CompatibleBase.PQ ? "smpte2084" : "arib-std-b67";

    var command = commandOf(hdrEncode(base), "hevc_nvenc");

    assertThat(argumentsBetween(command, "-c:v", "-vf"))
        .containsExactly(
            "-c:v",
            "hevc_nvenc",
            "-preset",
            "p4",
            "-tag:v",
            "hvc1",
            "-profile:v",
            "main10",
            "-level:v",
            "4.0",
            "-tier:v",
            "main",
            "-color_primaries",
            "bt2020",
            "-color_trc",
            transfer,
            "-colorspace",
            "bt2020nc",
            "-color_range",
            "tv");
    assertThat(filtersOf(command))
        .contains(
            ":out_color_matrix=bt2020nc:out_range=tv:out_primaries=bt2020:out_transfer="
                + transfer
                + ",format=p010le,setparams=color_primaries=bt2020:color_trc="
                + transfer
                + ":colorspace=bt2020nc:range=tv,");
    assertThat(argumentsBetween(command, "-forced-idr", "-f"))
        .containsExactly(
            "-forced-idr",
            "1",
            "-force_key_frames:0",
            "0,6,12,18,24,30,36,42,48,54,60",
            "-g:v:0",
            "145",
            "-keyint_min:v:0",
            "145",
            "-bf",
            "3",
            "-rc-lookahead",
            "20");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoDynamicRange.CompatibleBase.class,
      names = {"PQ", "HLG"})
  @DisplayName(
      "Should delete the frames' mastering display and content light level before hevc_nvenc when"
          + " it encodes PQ or HLG, because NVENC writes them into the samples in place of the"
          + " job's values")
  void shouldDeleteTheFramesStaticHdrMetadataBeforeHevcNvencWhenItEncodesPqOrHlg(
      VideoDynamicRange.CompatibleBase base) {
    var command = commandOf(hdrEncode(base), "hevc_nvenc");

    assertThat(filtersOf(command))
        .contains(
            ":range=tv,sidedata=mode=delete:type=MASTERING_DISPLAY_METADATA,"
                + "sidedata=mode=delete:type=CONTENT_LIGHT_LEVEL,fps=");
    assertThat(command).doesNotContain("-x265-params", "-svtav1-params");
  }

  @Test
  @DisplayName(
      "Should keep the frames' static HDR metadata when libx265 encodes PQ, because its"
          + " parameters carry the job's values")
  void shouldKeepTheFramesStaticHdrMetadataWhenLibx265EncodesPq() {
    var command = commandOf(hdrEncode(VideoDynamicRange.CompatibleBase.PQ), "libx265");

    assertThat(filtersOf(command)).doesNotContain("sidedata=");
    assertThat(command.get(command.indexOf("-x265-params") + 1))
        .contains("master-display=G(13250,34500)B(7500,3000)R(34000,16000)", "max-cll=0,0");
  }

  @Test
  @DisplayName(
      "Should reshape on the CPU into planar 10-bit PQ, hand hevc_nvenc biplanar p010 and pass it"
          + " the arguments of a PQ encode when a profile 5 source encodes to PQ")
  void shouldReshapeOnTheCpuAndPassHevcNvencTheArgumentsOfAPqEncodeWhenAProfile5SourceEncodes() {
    var pq = commandOf(hdrEncode(VideoDynamicRange.CompatibleBase.PQ), "hevc_nvenc");

    var command = commandOf(profile5Encode(), "hevc_nvenc");

    assertThat(filtersOf(command))
        .contains(
            "scale=-2:1080,format=yuv420p10le,"
                + "tonemapx=t=smpte2084:p=bt2020:m=bt2020:r=tv:format=yuv420p10le,format=p010le,"
                + "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc"
                + ":range=tv,sidedata=mode=delete:type=MASTERING_DISPLAY_METADATA,"
                + "sidedata=mode=delete:type=CONTENT_LIGHT_LEVEL,fps=");
    assertThat(withoutFilters(command)).isEqualTo(withoutFilters(pq));
  }

  private static VideoDecision.Encode hdrEncode(VideoDynamicRange.CompatibleBase base) {
    return hevcMain10Encode(range(base))
        .staticHdrMetadata(
            base == VideoDynamicRange.CompatibleBase.PQ
                ? Optional.of(STATIC_HDR_METADATA)
                : Optional.empty())
        .build();
  }

  private static VideoDecision.Encode profile5Encode() {
    return hevcMain10Encode(range(VideoDynamicRange.CompatibleBase.PQ))
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
  }

  private static String filtersOf(List<String> command) {
    return command.get(command.indexOf("-vf") + 1);
  }

  private static List<String> withoutFilters(List<String> command) {
    var arguments = new ArrayList<>(command);
    arguments.remove(arguments.indexOf("-vf") + 1);
    return arguments;
  }

  // The arguments from the first option up to, and without, the second.
  private static List<String> argumentsBetween(List<String> command, String from, String to) {
    return command.subList(command.indexOf(from), command.indexOf(to));
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
        .level(40)
        .codecString("hvc1.2.4.L120.90")
        .outputPictureFormat(new PictureFormat(10, ChromaSubsampling.CHROMA_420));
  }

  private static VideoDynamicRange range(VideoDynamicRange.CompatibleBase base) {
    return new VideoDynamicRange(
        base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
  }
}
