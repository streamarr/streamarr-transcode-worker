package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVisionRpuPlacement;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// The command of a Dolby Vision profile 5 to PQ encode whose decode, scale and reshape run on the
// GPU, and the jobs that keep the CPU filters.
@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder GPU Dolby Vision Reshape Tests")
class FfmpegCommandBuilderGpuDolbyVisionReshapeTest {

  private static final List<String> GPU_DECODE =
      List.of("-hwaccel", "cuda", "-hwaccel_output_format", "cuda");
  // tonemap_cuda fails a picture without an RPU unless the picture is PQ or HLG under a matrix that
  // it reads. A profile 5 stream declares no transfer, so the filters declare BT.2020 PQ first.
  private static final String GPU_FILTERS =
      "scale_cuda=-2:1080,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc,"
          + "tonemap_cuda=t=smpte2084:p=bt2020:m=bt2020:r=tv:tradeoff=disabled:format=p010le,"
          + "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv,"
          + "sidedata=mode=delete:type=MASTERING_DISPLAY_METADATA,"
          + "sidedata=mode=delete:type=CONTENT_LIGHT_LEVEL,fps=24/1:eof_action=pass";
  private static final String CPU_RESHAPE =
      "format=yuv420p10le,tonemapx=t=smpte2084:p=bt2020:m=bt2020:r=tv:format=yuv420p10le,";
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
  private static final AudioDecision COPIED_AUDIO =
      AudioDecision.builder().mode(AudioMode.COPY).codec("aac").channels(2).bitrate(0L).build();
  private static final AudioDecision ENCODED_AUDIO =
      AudioDecision.builder()
          .mode(AudioMode.TRANSCODE)
          .codec("aac")
          .channels(2)
          .bitrate(128_000L)
          .build();

  private final FfmpegCommandBuilder builder =
      new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1));

  @Test
  @DisplayName(
      "Should decode in hardware, scale and reshape on the GPU and pass hevc_nvenc the arguments of"
          + " a PQ encode when hevc_nvenc passed its GPU reshape test and a profile 5 source"
          + " encodes to PQ")
  void shouldDecodeScaleAndReshapeOnTheGpuWhenHevcNvencPassedItsGpuReshapeTest() {
    var pq = singleCommand(verifiedJob(request(pqEncode().build(), COPIED_AUDIO).build()));

    var command =
        singleCommand(verifiedJob(request(profile5Encode().build(), COPIED_AUDIO).build()));

    assertThat(command)
        .containsSequence(
            "-hwaccel", "cuda", "-hwaccel_output_format", "cuda", "-i", "/media/movie.mkv");
    assertThat(filtersOf(command)).isEqualTo(GPU_FILTERS);
    assertThat(withoutFiltersAndGpuDecode(command)).isEqualTo(withoutFiltersAndGpuDecode(pq));
  }

  // ADR 0013, as amended in streamarr/streamarr-adr#132: tonemap_cuda can look the conversion up
  // in a 3D table instead of computing it for each pixel, and it chooses the table by itself on a
  // GPU with few multiprocessors from before Ampere. The table misses the bound against the exact
  // value, so the worker lets tonemap_cuda use the table only after a measurement passes.
  @Test
  @DisplayName(
      "Should make tonemap_cuda compute the reshape for each pixel when hevc_nvenc reshapes a"
          + " profile 5 source on the GPU")
  void shouldMakeTonemapCudaComputeTheReshapeForEachPixelWhenHevcNvencReshapesOnTheGpu() {
    var command =
        singleCommand(verifiedJob(request(profile5Encode().build(), COPIED_AUDIO).build()));

    assertThat(Arrays.stream(filtersOf(command).split(",")))
        .filteredOn(filter -> filter.startsWith("tonemap_cuda="))
        .singleElement()
        .satisfies(
            tonemap ->
                assertThat(tonemap.substring("tonemap_cuda=".length()).split(":"))
                    .contains("tradeoff=disabled"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("jobsThatTheGpuFiltersCannotRun")
  @DisplayName(
      "Should decode in software and keep the CPU filters when the GPU filters cannot run the job")
  void shouldDecodeInSoftwareAndKeepTheCpuFiltersWhenTheGpuFiltersCannotRunTheJob(
      String reason, TranscodeJob job, String cpuFilters) {
    var command = singleCommand(job);

    assertThat(command).doesNotContain("-hwaccel", "-hwaccel_output_format");
    assertThat(filtersOf(command)).contains(cpuFilters).doesNotContain("_cuda");
  }

  static Stream<Arguments> jobsThatTheGpuFiltersCannotRun() {
    var deinterlaced =
        profile5Encode()
            .deinterlace(
                Optional.of(
                    new Deinterlace(
                        Deinterlace.FirstField.TOP, Deinterlace.Output.ONE_FRAME_PER_FRAME)))
            .build();
    var av1Profile10 = profile5Encode().sourceCodecFamily(Optional.of(CodecFamily.AV1)).build();
    var toneMapped =
        profile5Encode()
            .outputDynamicRange(VideoDynamicRange.sdr())
            .staticHdrMetadata(Optional.empty())
            .build();
    return Stream.of(
        Arguments.of(
            "hevc_nvenc did not pass its GPU reshape test",
            TranscodeJob.builder()
                .request(request(profile5Encode().build(), COPIED_AUDIO).build())
                .videoEncoder("hevc_nvenc")
                .build(),
            "scale=-2:1080," + CPU_RESHAPE + "format=p010le,"),
        Arguments.of(
            "the source is interlaced, and bwdif takes frames from system memory",
            verifiedJob(request(deinterlaced, COPIED_AUDIO).build()),
            "bwdif=mode=send_frame:parity=tff:deint=all,scale=-2:1080," + CPU_RESHAPE),
        Arguments.of(
            "the source is AV1 profile 10.0, whose hardware decode no recording shows",
            verifiedJob(request(av1Profile10, COPIED_AUDIO).build()),
            "scale=-2:1080," + CPU_RESHAPE),
        Arguments.of(
            "the output is SDR, which tonemapx tone-maps",
            verifiedJob(request(toneMapped, COPIED_AUDIO).build()),
            "scale=-2:1080,tonemapx=tonemap=mobius"),
        Arguments.of(
            "the source is PQ, which needs no reshape",
            verifiedJob(request(pqEncode().build(), COPIED_AUDIO).build()),
            "scale=-2:1080:out_color_matrix=bt2020nc"));
  }

  @Test
  @DisplayName(
      "Should seek before the hardware decoder and trim its frames on the GPU when a replacement"
          + " attempt of a profile 5 source starts at a later segment")
  void shouldSeekBeforeTheHardwareDecoderAndTrimWhenAReplacementAttemptStartsAtALaterSegment() {
    var replacement =
        request(profile5Encode().build(), COPIED_AUDIO).startSequenceNumber(3).build();

    var command = singleCommand(verifiedJob(replacement));

    assertThat(command)
        .containsSubsequence("-ss", "12", "-noaccurate_seek", "-i", "/media/movie.mkv")
        .containsSubsequence("-hwaccel", "cuda", "-hwaccel_output_format", "cuda", "-i");
    assertThat(filtersOf(command)).isEqualTo(GPU_FILTERS + ",trim=start_pts=432");
  }

  @Test
  @DisplayName(
      "Should decode the copy in hardware and the source's audio without it when the worker moves"
          + " the RPUs of a profile 5 source before the GPU reshape")
  void shouldDecodeTheCopyInHardwareAndTheSourcesAudioWithoutItWhenTheWorkerMovesTheRpus() {
    var movesRpus =
        profile5Encode()
            .sourceDynamicRange(profile5(DolbyVisionRpuPlacement.STARTS_SAMPLE))
            .build();

    var pipeline = builder.buildPipeline(verifiedJob(request(movesRpus, ENCODED_AUDIO).build()));

    var pipe =
        assertThat(pipeline)
            .asInstanceOf(InstanceOfAssertFactories.type(FfmpegPipeline.RpuMovingPipe.class))
            .actual();
    assertThat(pipe.copyCommand()).doesNotContain("-hwaccel");
    assertThat(pipe.encodeCommand())
        .containsSubsequence(
            "-hwaccel", "cuda", "-hwaccel_output_format", "cuda", "-i", "pipe:0", "-i")
        .containsOnlyOnce("-hwaccel");
    assertThat(filtersOf(pipe.encodeCommand())).isEqualTo("trim=start=0," + GPU_FILTERS);
  }

  @Test
  @DisplayName(
      "Should decode only the video's input in hardware and hold the last GPU frame when a profile"
          + " 5 encode holds its last frame and reads its audio from a second input")
  void shouldDecodeOnlyTheVideosInputInHardwareAndHoldTheLastGpuFrameWhenAProfile5EncodeHolds() {
    var held = profile5Encode().holdsLastFrameToAdvertisedEnd(true).build();
    var request =
        request(held, ENCODED_AUDIO).advertisedEnd(Optional.of(Duration.ofSeconds(66))).build();

    var command = singleCommand(verifiedJob(request));

    assertThat(command)
        .startsWith("ffmpeg", "-y", "-hwaccel", "cuda", "-hwaccel_output_format", "cuda", "-i")
        .containsOnlyOnce("-hwaccel");
    assertThat(command.get(command.lastIndexOf("-filter_complex") + 1))
        .startsWith(
            "[0:0]" + GPU_FILTERS + ",tpad=stop=-1:stop_mode=clone,trim=end_pts=1584[held_video]");
  }

  @Test
  @DisplayName(
      "Should test only hevc_nvenc for the GPU reshape when the worker names the encoders whose"
          + " GPU reshape it tests")
  void shouldTestOnlyHevcNvencForTheGpuReshapeWhenNamingTheEncodersWhoseGpuReshapeItTests() {
    assertThat(FfmpegCommandBuilder.testsGpuDolbyVisionReshape("hevc_nvenc")).isTrue();
    assertThat(
            Stream.of("h264_nvenc", "av1_nvenc", "libx265", "libsvtav1", "hevc_qsv", "unknown")
                .filter(FfmpegCommandBuilder::testsGpuDolbyVisionReshape))
        .isEmpty();
  }

  private static TranscodeJob verifiedJob(TranscodeRequest request) {
    return TranscodeJob.builder()
        .request(request)
        .videoEncoder("hevc_nvenc")
        .gpuDolbyVisionReshapeVerified(true)
        .build();
  }

  private List<String> singleCommand(TranscodeJob job) {
    return assertThat(builder.buildPipeline(job))
        .asInstanceOf(InstanceOfAssertFactories.type(FfmpegPipeline.SingleProcess.class))
        .actual()
        .command();
  }

  private static String filtersOf(List<String> command) {
    return command.get(command.indexOf("-vf") + 1);
  }

  private static List<String> withoutFiltersAndGpuDecode(List<String> command) {
    var arguments = new ArrayList<>(command);
    arguments.remove(arguments.indexOf("-vf") + 1);
    var gpuDecode = Collections.indexOfSubList(arguments, GPU_DECODE);
    if (gpuDecode >= 0) {
      arguments.subList(gpuDecode, gpuDecode + GPU_DECODE.size()).clear();
    }

    return arguments;
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(
      VideoDecision.Encode encode, AudioDecision audio) {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(
                audio.mode() == AudioMode.COPY
                    ? TranscodeMode.VIDEO_TRANSCODE
                    : TranscodeMode.FULL_TRANSCODE)
            .videoCodecFamily("hevc")
            .videoDecision(encode)
            .audioDecision(audio)
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .outputFrameRate(Optional.of(new OutputFrameRate(24, 1)))
        .mediaSegmentCount(11)
        .transcodeDecision(decision)
        .encoderPreset(EncoderPreset.FASTER)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L);
  }

  private static VideoDecision.Encode.EncodeBuilder profile5Encode() {
    return pqEncode().sourceDynamicRange(profile5(DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }

  private static VideoDecision.Encode.EncodeBuilder pqEncode() {
    var pq =
        new VideoDynamicRange(
            CompatibleBase.PQ, Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourcePacketsCarryPresentationTimes(true)
        .sourceDynamicRange(pq)
        .outputDynamicRange(pq)
        .codecProfile(VideoCodecProfile.HEVC_MAIN_10)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(40)
        .codecString("hvc1.2.4.L120.90")
        .outputPictureFormat(new PictureFormat(10, ChromaSubsampling.CHROMA_420))
        .staticHdrMetadata(Optional.of(STATIC_HDR_METADATA));
  }

  private static VideoDynamicRange profile5(DolbyVisionRpuPlacement placement) {
    return new VideoDynamicRange(
        CompatibleBase.NONE,
        Presence.ABSENT,
        new VideoDynamicRange.DolbyVision.Present(Optional.empty(), Presence.PRESENT, placement));
  }
}
