package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs 10-bit and HDR encodes (streamarr/streamarr-transcode-worker#27) through the producer over
 * the FFmpeg and ffprobe on {@code PATH}, the locked build in CI, and reads the delivered stream
 * back with ffprobe: the profile, bit depth, colour description and HDR10 static metadata. The
 * HDR10, HLG and 10-bit SDR sources are synthetic libx265 streams at 64x36 and 24 fps with a
 * keyframe every second. The profile 5 source is the synthetic {@code dolby-vision-5-dvh1.mp4}
 * sample-entry fixture, whose RPUs come from dovi_tool's generator: these tests check the reshape's
 * plumbing, not its colours.
 */
@Tag("SmokeTest")
@DisplayName("HDR Encode Smoke Tests")
class HdrEncodeSmokeTest {

  private static final int SOURCE_SECONDS = 4;
  private static final int PERIOD_SECONDS = 1;
  private static final int PROFILE_5_SOURCE_SECONDS = 6;
  private static final int PROFILE_5_PERIOD_SECONDS = 2;
  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(120);
  private static final Duration FRAGMENTATION_TARGET = Duration.ofMillis(250);
  // libx265 at 64x36 or 64x64 in Main 10 at level 1.0 in the Main tier, which the job forces as 10.
  private static final String HEVC_MAIN_10_CODEC_STRING = "hvc1.2.4.L30.90";
  private static final int HEVC_LEVEL = 10;
  // SVT-AV1 at 64x36 in the Main profile, 10-bit, at level 2.0 in the Main tier.
  private static final String AV1_TEN_BIT_CODEC_STRING = "av01.0.00M.10";
  private static final int AV1_LEVEL = 20;

  /** A P3 D65 display of 1000 cd/m² and 0.005 cd/m², with MaxCLL 1000 and MaxFALL 400. */
  private static final StaticHdrMetadata SOURCE_METADATA =
      new StaticHdrMetadata(
          display(10_000_000, 50), new PlannedCopyOutput.ContentLightLevel(1000, 400));

  /**
   * Decision 173's fallback for a source without static HDR metadata: a P3 D65 display of 1000
   * cd/m² and 0.0001 cd/m², and a content light level of 0, which means unknown.
   */
  private static final StaticHdrMetadata FALLBACK_METADATA =
      new StaticHdrMetadata(display(10_000_000, 1), new PlannedCopyOutput.ContentLightLevel(0, 0));

  @TempDir static Path root;
  private static Path hdr10;
  private static Path hlg;
  private static Path sdr10;

  @BeforeAll
  static void writeTheSources() throws Exception {
    hdr10 =
        encoded(
            "hdr10",
            "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                + ":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)"
                + "L(10000000,50):max-cll=1000,400");
    hlg = encoded("hlg", "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc");
    sdr10 = encoded("sdr10", "colorprim=bt709:transfer=bt709:colormatrix=bt709");
  }

  @Test
  @DisplayName(
      "Should deliver HEVC Main 10 with the BT.2020 PQ colour description and the job's static HDR"
          + " metadata when an HDR10 source encodes to HDR10")
  void shouldDeliverHevcMain10WithThePqColourDescriptionAndTheJobsMetadataWhenEncodingHdr10()
      throws Exception {
    var delivered =
        encoded(hdr10, hevcEncode(range(VideoDynamicRange.CompatibleBase.PQ)).build(), 0);

    var probed = probed(delivered.acceptedBytes());

    assertThat(probed)
        .contains(
            "profile=Main 10",
            "pix_fmt=yuv420p10le",
            "color_range=tv",
            "color_space=bt2020nc",
            "color_transfer=smpte2084",
            "color_primaries=bt2020",
            "side_data_type=Mastering display metadata",
            "red_x=34000/50000",
            "green_y=34500/50000",
            "white_point_x=15635/50000",
            "max_luminance=10000000/10000",
            "min_luminance=50/10000",
            "side_data_type=Content light level metadata",
            "max_content=1000",
            "max_average=400");
  }

  @Test
  @DisplayName(
      "Should deliver 10-bit AV1 with the BT.2020 PQ colour description and the job's static HDR"
          + " metadata when an HDR10 source encodes to AV1 HDR10")
  void shouldDeliverTenBitAv1WithThePqColourDescriptionAndTheJobsMetadataWhenEncodingAv1Hdr10()
      throws Exception {
    var encode =
        VideoDecision.Encode.builder()
            .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
            .sourceDynamicRange(range(VideoDynamicRange.CompatibleBase.PQ))
            .outputDynamicRange(range(VideoDynamicRange.CompatibleBase.PQ))
            .codecProfile(VideoCodecProfile.AV1_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(AV1_LEVEL)
            .codecString(AV1_TEN_BIT_CODEC_STRING)
            .outputPictureFormat(tenBit())
            .staticHdrMetadata(Optional.of(SOURCE_METADATA))
            .build();

    var probed = probed(encoded(hdr10, encode, 0).acceptedBytes());

    // AV1 stores the chromaticities in units of 1/65536 and the peak in units of 1/256 cd/m².
    assertThat(probed)
        .contains(
            "pix_fmt=yuv420p10le",
            "color_space=bt2020nc",
            "color_transfer=smpte2084",
            "color_primaries=bt2020",
            "side_data_type=Mastering display metadata",
            "red_x=44564/65536",
            "max_luminance=256000/256",
            "side_data_type=Content light level metadata",
            "max_content=1000",
            "max_average=400");
  }

  @Test
  @DisplayName(
      "Should deliver HEVC Main 10 with the BT.2020 HLG colour description and no static HDR"
          + " metadata when an HLG source encodes to HLG")
  void shouldDeliverHevcMain10WithTheHlgColourDescriptionWhenAnHlgSourceEncodesToHlg()
      throws Exception {
    var hlgRange = range(VideoDynamicRange.CompatibleBase.HLG);
    var encode = hevcEncode(hlgRange).staticHdrMetadata(Optional.empty()).build();

    var probed = probed(encoded(hlg, encode, 0).acceptedBytes());

    assertThat(probed)
        .contains(
            "profile=Main 10",
            "pix_fmt=yuv420p10le",
            "color_space=bt2020nc",
            "color_transfer=arib-std-b67",
            "color_primaries=bt2020")
        .doesNotContain("Mastering display metadata", "Content light level metadata");
  }

  @Test
  @DisplayName(
      "Should deliver 10-bit BT.709 video without HDR metadata when a 10-bit SDR source encodes to"
          + " 10-bit SDR")
  void shouldDeliverTenBitBt709VideoWithoutHdrMetadataWhenATenBitSdrSourceEncodesToTenBitSdr()
      throws Exception {
    var sdr = VideoDynamicRange.sdr();
    var encode = hevcEncode(sdr).staticHdrMetadata(Optional.empty()).build();

    var probed = probed(encoded(sdr10, encode, 0).acceptedBytes());

    assertThat(probed)
        .contains("profile=Main 10", "pix_fmt=yuv420p10le", "color_transfer=bt709")
        .doesNotContain("Mastering display metadata", "smpte2084");
  }

  @Test
  @DisplayName(
      "Should deliver HDR10 with the fallback static HDR metadata and without Dolby Vision when a"
          + " profile 5 source reshapes into PQ")
  void shouldDeliverHdr10WithTheFallbackMetadataWithoutDolbyVisionWhenAProfile5SourceReshapes()
      throws Exception {
    var probed = probed(encoded(profile5Source(), profile5Encode(), 0).acceptedBytes());

    assertThat(probed)
        .contains(
            "profile=Main 10",
            "pix_fmt=yuv420p10le",
            "color_range=tv",
            "color_space=bt2020nc",
            "color_transfer=smpte2084",
            "color_primaries=bt2020",
            "side_data_type=Mastering display metadata",
            "green_x=13250/50000",
            "max_luminance=10000000/10000",
            "min_luminance=1/10000",
            "side_data_type=Content light level metadata",
            "max_content=0",
            "max_average=0")
        .doesNotContain("Dolby Vision", "DOVI");
  }

  @Test
  @DisplayName(
      "Should list every encode feature of the software HEVC and AV1 encoders when the worker"
          + " detects its capabilities with the locked FFmpeg")
  void shouldListEveryEncodeFeatureOfTheSoftwareHevcAndAv1EncodersWithTheLockedFfmpeg() {
    var service = new TranscodeCapabilityService("ffmpeg", ProcessBuilder::start);

    service.detectCapabilities();

    assertThat(service.workerVerifiedEncoders())
        .extracting(encoder -> encoder.backend().encoder(), WorkerVerifiedEncoder::encodeFeatures)
        .containsExactlyInAnyOrder(
            tuple("libx264", Set.of()),
            tuple("libx265", Set.of(EncodeFeature.values())),
            tuple("libsvtav1", Set.of(EncodeFeature.values())));
  }

  @Test
  @DisplayName(
      "Should deliver the same initialization segment and static HDR metadata from attempts that"
          + " start at different keyframes when a profile 5 source reshapes into PQ")
  void shouldDeliverTheSameInitializationAndMetadataFromAttemptsAtDifferentKeyframes()
      throws Exception {
    var fromStart = encoded(profile5Source(), profile5Encode(), 0);
    var fromSegment2 = encoded(profile5Source(), profile5Encode(), 2);

    assertThat(fromSegment2.acceptedNames()).first().isEqualTo("init.mp4");
    assertThat(fromSegment2.acceptedNames()).doesNotContain("segment0.m4s", "segment1.m4s");
    assertThat(fromSegment2.contentOf("init.mp4")).isEqualTo(fromStart.contentOf("init.mp4"));
    assertThat(staticMetadataLines(probed(fromSegment2.acceptedBytes())))
        .isNotEmpty()
        .isEqualTo(staticMetadataLines(probed(fromStart.acceptedBytes())));
  }

  private static VideoDecision.Encode profile5Encode() {
    var profile5 =
        new VideoDynamicRange(
            VideoDynamicRange.CompatibleBase.NONE,
            VideoDynamicRange.Presence.ABSENT,
            new VideoDynamicRange.DolbyVision.Present(
                Optional.empty(),
                VideoDynamicRange.Presence.PRESENT,
                VideoDynamicRange.DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
    return hevcEncode(range(VideoDynamicRange.CompatibleBase.PQ))
        .sourceDynamicRange(profile5)
        .staticHdrMetadata(Optional.of(FALLBACK_METADATA))
        .build();
  }

  private static VideoDecision.Encode.EncodeBuilder hevcEncode(VideoDynamicRange output) {
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourcePacketsCarryPresentationTimes(true)
        .sourceDynamicRange(output)
        .outputDynamicRange(output)
        .codecProfile(VideoCodecProfile.HEVC_MAIN_10)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(HEVC_LEVEL)
        .codecString(HEVC_MAIN_10_CODEC_STRING)
        .outputPictureFormat(tenBit())
        .staticHdrMetadata(Optional.of(SOURCE_METADATA));
  }

  private static PictureFormat tenBit() {
    return new PictureFormat(10, ChromaSubsampling.CHROMA_420);
  }

  private static VideoDynamicRange range(VideoDynamicRange.CompatibleBase base) {
    return new VideoDynamicRange(
        base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
  }

  private static PlannedCopyOutput.MasteringDisplay display(long maxLuminance, long minLuminance) {
    return PlannedCopyOutput.MasteringDisplay.builder()
        .red(new PlannedCopyOutput.Chromaticity(34000, 16000))
        .green(new PlannedCopyOutput.Chromaticity(13250, 34500))
        .blue(new PlannedCopyOutput.Chromaticity(7500, 3000))
        .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
        .maxLuminance(maxLuminance)
        .minLuminance(minLuminance)
        .build();
  }

  private static List<String> staticMetadataLines(String probed) {
    return probed
        .lines()
        .filter(
            line ->
                line.matches(
                    "(red|green|blue|white_point)_[xy]=.*|(max|min)_luminance=.*"
                        + "|max_(content|average)=.*"))
        .toList();
  }

  private static Path profile5Source() throws URISyntaxException {
    return Path.of(
        Objects.requireNonNull(
                HdrEncodeSmokeTest.class.getResource("/sample-entries/dolby-vision-5-dvh1.mp4"))
            .toURI());
  }

  // The attempt's deliveries, after the producer accepted its output against the job.
  private static RecordingSegmentSink encoded(
      Path source, VideoDecision.Encode encode, int firstSegment) {
    var sink = new RecordingSegmentSink();
    var request = request(source, encode, firstSegment);

    var producer = engine().startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isInstanceOf(Completed.class);
    return sink;
  }

  private static TranscodeRequest request(
      Path source, VideoDecision.Encode encode, int firstSegment) {
    var profile5 =
        encode.sourceDynamicRange().compatibleBase() == VideoDynamicRange.CompatibleBase.NONE;
    var period = profile5 ? PROFILE_5_PERIOD_SECONDS : PERIOD_SECONDS;
    var seconds = profile5 ? PROFILE_5_SOURCE_SECONDS : SOURCE_SECONDS;
    var height = profile5 ? 64 : 36;
    var codecFamily = encode.codecProfile() == VideoCodecProfile.AV1_MAIN ? "av1" : "hevc";
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(period)
        .outputFrameRate(Optional.of(new OutputFrameRate(24, 1)))
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                .videoCodecFamily(codecFamily)
                .videoDecision(encode)
                .audioDecision(AudioDecision.builder().mode(AudioMode.NONE).build())
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .encoderPreset(EncoderPreset.SUPERFAST)
        .width(64)
        .height(height)
        .bitrate(200_000L)
        .startSequenceNumber(firstSegment)
        .mediaSegmentCount(seconds / period)
        .build();
  }

  private static FfmpegTranscodeEngine engine() {
    var capabilities = new TranscodeCapabilityService("ffmpeg", ProcessBuilder::start);
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", FRAGMENTATION_TARGET))
        .capabilityService(capabilities)
        .launcher(new ProcessBuilderLauncher())
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
        .build();
  }

  private static Path encoded(String name, String colourParameters) throws Exception {
    var elementary = root.resolve(name + ".hevc");
    runToCompletion(
        List.of(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:bframes=0:log-level=error:" + colourParameters,
            "-f",
            "hevc",
            elementary.toString()));
    var muxed = root.resolve(name + ".mp4");
    runToCompletion(
        List.of(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "hevc",
            "-framerate",
            "24",
            "-i",
            elementary.toString(),
            "-c",
            "copy",
            "-tag:v",
            "hvc1",
            muxed.toString()));
    return muxed;
  }

  // ffprobe's reading of the delivered stream and of the side data of its first frame.
  private static String probed(byte[] delivered) throws Exception {
    var media = Files.createTempFile(root, "delivered", ".mp4");
    Files.write(media, delivered);
    return runToCompletion(
        List.of(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-read_intervals",
            "%+#1",
            "-show_entries",
            "stream=profile,pix_fmt,color_range,color_space,color_transfer,color_primaries"
                + ":stream_side_data:frame=side_data_list",
            "-of",
            "default=nw=1",
            media.toString()));
  }

  private static String runToCompletion(List<String> command)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
    return output;
  }
}
