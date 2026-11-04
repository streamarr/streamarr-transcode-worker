package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.DeliveredSamples.assertCarriesTheRecordedSamples;
import static com.streamarr.transcode.fixtures.RecordingEdits.withBytesAfterTheFirstVideoSample;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copyJobBuilderFor;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.BitstreamRewrite;
import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionOutput;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionSampleEntry;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoCopyFeature;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs copy jobs that carry the HDR copy contract (ADR 0013, ADR 0036) through the worker, with a
 * scripted FFmpeg that writes a recorded libx265 copy under {@code hvc1}. The recorded copy has no
 * {@code colr} box, so any planned range passes its transfer check.
 */
@Tag("UnitTest")
@DisplayName("Transcode Worker HDR Copy Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerHdrCopyTest {

  private static final Recording HEVC_COPY = recording("18-copy-hevc-keeping-packet-bytes.fmp4");
  private static final Recording AV1_COPY = recording("09-svtav1-vfr.fmp4");
  // libx264's High profile stream, whose colr box declares BT.709.
  private static final Recording H264_COPY = recording(ENCODED_RECORDING);
  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final int PREFIX_SEI = 39;
  private static final int SUFFIX_SEI = 40;
  // The hvcC record's fields before its count of NAL unit arrays (ISO/IEC 14496-15, 8.3.3.1.2).
  private static final int HVCC_FIELDS_BEFORE_ARRAYS = 22;

  /** BT.2020 primaries, the D65 white point, 1000 cd/m² and 0.005 cd/m². */
  private static final MasteringDisplayColorVolume MASTERING_DISPLAY =
      MasteringDisplayColorVolume.newBuilder()
          .setRed(coordinates(34000, 16000))
          .setGreen(coordinates(13250, 34500))
          .setBlue(coordinates(7500, 3000))
          .setWhitePoint(coordinates(15635, 16450))
          .setMaxLuminance(10_000_000)
          .setMinLuminance(50)
          .build();

  /** MaxCLL 1000 cd/m² and MaxFALL 400 cd/m². */
  private static final ContentLightLevel CONTENT_LIGHT_LEVEL =
      ContentLightLevel.newBuilder()
          .setMaxContentLightLevel(1000)
          .setMaxFrameAverageLightLevel(400)
          .build();

  /**
   * The prefix SEI NAL unit of {@link #MASTERING_DISPLAY}, written out by hand from ITU-T H.265
   * D.2.28: its header, payload type 137 and size 24, green, blue, red and the white point, then
   * the luminances, whose three zero bytes take an emulation prevention byte, and the stop bit.
   */
  private static final String MASTERING_DISPLAY_SEI =
      "4E01" + "8918" + "33C286C41D4C0BB884D03E803D134042" + "00989680000003" + "0032" + "80";

  /** The prefix SEI NAL unit of {@link #CONTENT_LIGHT_LEVEL}: payload type 144 and size 4. */
  private static final String CONTENT_LIGHT_LEVEL_SEI = "4E01" + "9004" + "03E80190" + "80";

  /**
   * A suffix SEI NAL unit after its 4-byte length that holds one HDR10+ message: ITU-T T.35,
   * country 0xB5, provider 0x003C, provider-oriented code 0x0001 and application 4.
   */
  private static final String HDR10_PLUS_SEI = "0000000D" + "5001" + "0408B5003C0001040100" + "80";

  /** The same HDR10+ message in an H.264 SEI NAL unit, whose 1-byte header names type 6. */
  private static final String H264_HDR10_PLUS_SEI =
      "0000000C" + "06" + "0408B5003C0001040100" + "80";

  /** A NAL unit of type 62 after its 4-byte length, as a Dolby Vision RPU: rpu_nal_prefix 25. */
  private static final String RPU = "00000004" + "7C01" + "1908";

  // Dolby Vision configuration records (Dolby's ISO BMFF specification, 3.2): version 1.0, then
  // the profile (7 bits), level (6 bits), the RPU, enhancement-layer and base-layer flags,
  // bl_signal_compatibility_id (4 bits) and dv_md_compression (2 bits), then 19 reserved bytes.
  // Profile 8, level 6, an RPU and a base layer, compatibility 1 and compression 0.
  private static final String PROFILE_8_LEVEL_6_PQ_RECORD = "0100103510" + "00".repeat(19);
  // Profile 5, level 6, an RPU and a base layer, compatibility 0 and compression 0.
  private static final String PROFILE_5_LEVEL_6_RECORD = "01000A3500" + "00".repeat(19);
  // Profile 10, level 6, an RPU and a base layer, compatibility 2 and compression 0. The recorded
  // AV1 copy's colr box declares BT.709, so its planned base is SDR.
  private static final String PROFILE_10_LEVEL_6_SDR_RECORD = "0100143520" + "00".repeat(19);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should declare the bitstream rewrites and copy features that it delivers when it registers")
  void shouldDeclareTheBitstreamRewritesAndCopyFeaturesThatItDeliversWhenItRegisters()
      throws Exception {
    try (var worker = worker(ScriptedProcessLauncher.running())) {
      worker.start("localhost", 1);

      var capabilities = runtime.connection().registration().getCapabilities();
      assertThat(capabilities.getBitstreamRewritesList())
          .containsExactlyInAnyOrder(
              BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION,
              BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS,
              BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD);
      assertThat(capabilities.getVideoCopyFeaturesList())
          .containsExactlyInAnyOrder(
              VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT,
              VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("plannedStaticMetadata")
  @DisplayName(
      "Should deliver each static HDR value that the job carries as its own SEI message in the hvc1"
          + " record when a copy plans a PQ output")
  void shouldDeliverEachStaticHdrValueAsItsOwnSeiMessageInTheHvc1RecordWhenACopyPlansAPqOutput(
      String carried, UnaryOperator<VideoDecision.Builder> staticValues, List<String> expected)
      throws Exception {
    var job = hevcCopyJob();
    staticValues.apply(job.getDecisionBuilder().getVideoBuilder().setOutputDynamicRange(pq()));

    var initializationSegment = initializationSegmentOf(job.build(), bytesOf(HEVC_COPY.file()));

    assertThat(seiNalUnitsOfTheRecord(initializationSegment)).containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> plannedStaticMetadata() {
    return Stream.of(
        Arguments.of(
            "both values",
            unary(
                video ->
                    video
                        .setMasteringDisplay(MASTERING_DISPLAY)
                        .setContentLightLevel(CONTENT_LIGHT_LEVEL)),
            List.of(MASTERING_DISPLAY_SEI, CONTENT_LIGHT_LEVEL_SEI)),
        Arguments.of(
            "only the mastering display",
            unary(video -> video.setMasteringDisplay(MASTERING_DISPLAY)),
            List.of(MASTERING_DISPLAY_SEI)),
        Arguments.of(
            "only the content light level",
            unary(video -> video.setContentLightLevel(CONTENT_LIGHT_LEVEL)),
            List.of(CONTENT_LIGHT_LEVEL_SEI)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contradictoryPlannedOutputs")
  @DisplayName(
      "Should refuse the job as an invalid specification when its copy plans an output that the"
          + " contract forbids")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItsCopyPlansAnOutputThatTheContractForbids(
      String plan, UnaryOperator<VideoDecision.Builder> planned) throws Exception {
    var job = hevcCopyJob();
    planned.apply(job.getDecisionBuilder().getVideoBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> contradictoryPlannedOutputs() {
    return Stream.of(
        Arguments.of(
            "an UNSPECIFIED compatible base",
            unary(
                video ->
                    video.setOutputDynamicRange(
                        pq().toBuilder()
                            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED)))),
        Arguments.of(
            "an UNSPECIFIED HDR10+ presence",
            unary(
                video ->
                    video.setOutputDynamicRange(
                        pq().toBuilder().setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)))),
        Arguments.of(
            "a Dolby Vision presence that is not set",
            unary(video -> video.setOutputDynamicRange(pq().toBuilder().clearDolbyVision()))),
        Arguments.of(
            "a mastering display without a planned range",
            unary(video -> video.setMasteringDisplay(MASTERING_DISPLAY))),
        Arguments.of(
            "a content light level without a planned range",
            unary(video -> video.setContentLightLevel(CONTENT_LIGHT_LEVEL))),
        Arguments.of(
            "a mastering display with an HLG output",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(
                            pq().toBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_HLG))
                        .setMasteringDisplay(MASTERING_DISPLAY))),
        Arguments.of(
            "a content light level with an SDR output",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(
                            pq().toBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR))
                        .setContentLightLevel(CONTENT_LIGHT_LEVEL))),
        Arguments.of(
            "a chromaticity above 50000",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .setMasteringDisplay(
                            MASTERING_DISPLAY.toBuilder().setWhitePoint(coordinates(50001, 0))))),
        Arguments.of(
            "a MaxCLL above 65535",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .setContentLightLevel(
                            CONTENT_LIGHT_LEVEL.toBuilder().setMaxContentLightLevel(65536)))),
        Arguments.of(
            "a MaxFALL above 65535",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .setContentLightLevel(
                            CONTENT_LIGHT_LEVEL.toBuilder().setMaxFrameAverageLightLevel(65536)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copyValuesOnAnEncode")
  @DisplayName(
      "Should refuse the job as an invalid specification when an encode carries a value that only a"
          + " copy carries")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAnEncodeCarriesAValueThatOnlyACopyCarries(
      String carrying, UnaryOperator<VideoDecision.Builder> withValue) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    withValue.apply(job.getDecisionBuilder().getVideoBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> copyValuesOnAnEncode() {
    return Stream.of(
        Arguments.of(
            "a mastering display", unary(video -> video.setMasteringDisplay(MASTERING_DISPLAY))),
        Arguments.of(
            "a content light level",
            unary(video -> video.setContentLightLevel(CONTENT_LIGHT_LEVEL))),
        Arguments.of(
            "a bitstream rewrite",
            unary(
                video ->
                    video.addBitstreamRewrites(
                        BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS))),
        Arguments.of(
            "a Dolby Vision output", unary(video -> video.setDolbyVisionOutput(profile5Output()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("strippedUnits")
  @DisplayName(
      "Should deliver the samples without the units that a listed rewrite strips when a copy lists"
          + " a strip rewrite")
  void shouldDeliverTheSamplesWithoutTheUnitsThatAListedRewriteStripsWhenACopyListsAStripRewrite(
      StrippedCopy stripped) throws Exception {
    var job = copyJobBuilderFor(stripped.recording());
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setOutputDynamicRange(stripped.range())
        .addBitstreamRewrites(stripped.rewrite());
    var output =
        withBytesAfterTheFirstVideoSample(
            bytesOf(stripped.recording().file()), HexFormat.of().parseHex(stripped.unit()));

    var uploads = uploadsOf(job.build(), output);

    assertCarriesTheRecordedSamples(uploads, stripped.recording());
  }

  static Stream<StrippedCopy> strippedUnits() {
    return Stream.of(
        StrippedCopy.builder()
            .name("HDR10+ from HEVC")
            .recording(HEVC_COPY)
            .range(pq())
            .rewrite(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS)
            .unit(HDR10_PLUS_SEI)
            .build(),
        StrippedCopy.builder()
            .name("Dolby Vision from HEVC")
            .recording(HEVC_COPY)
            .range(pq())
            .rewrite(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION)
            .unit(RPU)
            .build(),
        StrippedCopy.builder()
            .name("HDR10+ from H.264")
            .recording(H264_COPY)
            .range(pq().toBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR).build())
            .rewrite(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS)
            .unit(H264_HDR10_PLUS_SEI)
            .build());
  }

  /**
   * A recorded copy, a NAL unit added to its first video sample, and the rewrite that strips it.
   */
  @Builder
  record StrippedCopy(
      String name,
      Recording recording,
      VideoDynamicRange range,
      BitstreamRewrite rewrite,
      String unit) {

    @Override
    public String toString() {
      return name;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionOutputs")
  @DisplayName(
      "Should deliver the planned sample entry with the normalized Dolby Vision record when a copy"
          + " plans a Dolby Vision output and normalizes its record")
  void shouldDeliverThePlannedSampleEntryWithTheNormalizedRecordWhenACopyPlansADolbyVisionOutput(
      PlannedDolbyVision planned) throws Exception {
    var job = copyJobBuilderFor(planned.recording());
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setCodecString(planned.codecString())
        .setOutputDynamicRange(planned.range())
        .setDolbyVisionOutput(planned.output())
        .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD);

    var initializationSegment =
        initializationSegmentOf(job.build(), bytesOf(planned.recording().file()));

    assertThat(sampleEntryTypeOf(initializationSegment)).isEqualTo(planned.sampleEntry());
    assertThat(dolbyVisionRecordsOf(initializationSegment)).containsExactly(planned.record());
  }

  static Stream<PlannedDolbyVision> dolbyVisionOutputs() {
    return Stream.of(
        PlannedDolbyVision.builder()
            .name("profile 8.1 under hvc1")
            .recording(HEVC_COPY)
            .range(pqWithDolbyVision())
            .output(
                DolbyVisionOutput.newBuilder()
                    .setProfile(8)
                    .setLevel(6)
                    .setBaseLayerCompatibilityId(1)
                    .setSampleEntry(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1)
                    .build())
            .codecString(HEVC_COPY.videoCodecString())
            .sampleEntry("hvc1")
            .record("dvvC" + PROFILE_8_LEVEL_6_PQ_RECORD)
            .build(),
        PlannedDolbyVision.builder()
            .name("profile 5 under dvh1")
            .recording(HEVC_COPY)
            .range(profile5())
            .output(profile5Output().build())
            .codecString("dvh1.05.06")
            .sampleEntry("dvh1")
            .record("dvcC" + PROFILE_5_LEVEL_6_RECORD)
            .build(),
        PlannedDolbyVision.builder()
            .name("profile 10 on the SDR base of the recorded AV1 copy under av01")
            .recording(AV1_COPY)
            .range(
                pqWithDolbyVision().toBuilder()
                    .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                    .build())
            .output(
                DolbyVisionOutput.newBuilder()
                    .setProfile(10)
                    .setLevel(6)
                    .setBaseLayerCompatibilityId(2)
                    .setSampleEntry(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_AV01)
                    .build())
            .codecString(AV1_COPY.videoCodecString())
            .sampleEntry("av01")
            .record("dvvC" + PROFILE_10_LEVEL_6_SDR_RECORD)
            .build());
  }

  /** A planned Dolby Vision output of a recorded copy and the record that the copy delivers. */
  @Builder
  record PlannedDolbyVision(
      String name,
      Recording recording,
      VideoDynamicRange range,
      DolbyVisionOutput output,
      String codecString,
      String sampleEntry,
      String record) {

    @Override
    public String toString() {
      return name;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contradictoryRewritesAndDolbyVisionOutputs")
  @DisplayName(
      "Should refuse the job as an invalid specification when its copy lists rewrites or a Dolby"
          + " Vision output that the worker cannot deliver")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItsCopyListsWhatTheWorkerCannotDeliver(
      String plan, UnaryOperator<VideoDecision.Builder> planned) throws Exception {
    var job = hevcCopyJob();
    planned.apply(job.getDecisionBuilder().getVideoBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> contradictoryRewritesAndDolbyVisionOutputs() {
    return Stream.of(
        Arguments.of(
            "a rewrite without a planned range",
            unary(
                video ->
                    video.addBitstreamRewrites(
                        BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS))),
        Arguments.of(
            "an UNSPECIFIED rewrite",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_UNSPECIFIED))),
        Arguments.of(
            "the profile 7 to 8.1 conversion",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .addBitstreamRewrites(
                            BitstreamRewrite
                                .BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1))),
        Arguments.of(
            "a rewrite listed twice",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS)
                        .addBitstreamRewrites(
                            BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS))),
        Arguments.of(
            "a Dolby Vision strip with a planned range that keeps Dolby Vision",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pqWithDolbyVision())
                        .addBitstreamRewrites(
                            BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION))),
        Arguments.of(
            "an HDR10+ strip with a planned range that keeps HDR10+",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(
                            pq().toBuilder().setHdr10Plus(Presence.PRESENCE_PRESENT))
                        .addBitstreamRewrites(
                            BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS))),
        Arguments.of(
            "a normalized record without a Dolby Vision output",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pqWithDolbyVision())
                        .addBitstreamRewrites(
                            BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD))),
        Arguments.of(
            "a Dolby Vision output without a planned range",
            unary(video -> video.setDolbyVisionOutput(profile5Output()))),
        Arguments.of(
            "a Dolby Vision output with a planned range without Dolby Vision",
            unary(
                video ->
                    video
                        .setOutputDynamicRange(pq())
                        .setDolbyVisionOutput(
                            profile5Output()
                                .setProfile(8)
                                .setBaseLayerCompatibilityId(1)
                                .setSampleEntry(
                                    DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1)))),
        Arguments.of(
            "an UNSPECIFIED sample entry",
            dolbyVisionOutput(
                output ->
                    output.setSampleEntry(
                        DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_UNSPECIFIED))),
        Arguments.of(
            "a dav1 sample entry",
            dolbyVisionOutput(
                output ->
                    output
                        .setProfile(10)
                        .setSampleEntry(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DAV1))),
        Arguments.of(
            "a profile 8 output under dvh1", dolbyVisionOutput(output -> output.setProfile(8))),
        Arguments.of("level 0", dolbyVisionOutput(output -> output.setLevel(0))),
        Arguments.of("level 14", dolbyVisionOutput(output -> output.setLevel(14))),
        Arguments.of(
            "a compatibility identifier that another base takes",
            dolbyVisionOutput(output -> output.setBaseLayerCompatibilityId(1))));
  }

  // A copy of the recorded libx265 stream that names the recording's codec string.
  private static VariantJob.Builder hevcCopyJob() {
    var job = copyJobBuilderFor(HEVC_COPY);
    job.getDecisionBuilder().getVideoBuilder().setCopyKeepsPacketBytes(true);
    return job;
  }

  // A PQ range without HDR10+ or Dolby Vision, as an HDR10 or PQ10 copy plans it.
  private static VideoDynamicRange pq() {
    return VideoDynamicRange.newBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
        .setHdr10Plus(Presence.PRESENCE_ABSENT)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
        .build();
  }

  // A PQ range with Dolby Vision, as a profile 8.1 copy that keeps its RPUs plans it.
  private static VideoDynamicRange pqWithDolbyVision() {
    return pq().toBuilder()
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder().setObservedRpu(Presence.PRESENCE_PRESENT))
        .build();
  }

  // The range of a Dolby Vision profile 5 copy, which has no compatible base.
  private static VideoDynamicRange profile5() {
    return pqWithDolbyVision().toBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
        .build();
  }

  // The genuine dvh1 output of a profile 5 copy at level 6.
  private static DolbyVisionOutput.Builder profile5Output() {
    return DolbyVisionOutput.newBuilder()
        .setProfile(5)
        .setLevel(6)
        .setBaseLayerCompatibilityId(0)
        .setSampleEntry(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DVH1);
  }

  // A profile 5 copy whose Dolby Vision output takes the change.
  private static UnaryOperator<VideoDecision.Builder> dolbyVisionOutput(
      UnaryOperator<DolbyVisionOutput.Builder> change) {
    return video ->
        video
            .setOutputDynamicRange(profile5())
            .setDolbyVisionOutput(change.apply(profile5Output()))
            .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD);
  }

  private static ChromaticityCoordinates coordinates(int x, int y) {
    return ChromaticityCoordinates.newBuilder().setX(x).setY(y).build();
  }

  private static UnaryOperator<VideoDecision.Builder> unary(
      UnaryOperator<VideoDecision.Builder> change) {
    return change;
  }

  // Runs the job over FFmpeg output and returns the initialization segment that the worker uploads.
  private byte[] initializationSegmentOf(VariantJob job, byte[] ffmpegOutput) throws Exception {
    var uploads = uploadsOf(job, ffmpegOutput);
    var initializationSegment =
        TopLevelBoxes.of(uploads).stream()
            .filter(box -> box.type().equals("moov"))
            .findFirst()
            .orElseThrow()
            .end();
    return Arrays.copyOf(uploads, initializationSegment);
  }

  // Runs the job over FFmpeg output and returns every segment that the worker uploads, in order.
  private byte[] uploadsOf(VariantJob job, byte[] ffmpegOutput) throws Exception {
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(ffmpegOutput).build());

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(launcher.hasLaunched(fromProto(job.getJobAttemptId()))).isTrue();
      var uploaded = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> uploaded.writeBytes(upload.content()));
      return uploaded.toByteArray();
    }
  }

  private void assertRefusedAsAnInvalidSpecification(VariantJob job) throws Exception {
    var launcher = ScriptedProcessLauncher.writing(HEVC_COPY.file());

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(
              connection.events().stream()
                  .filter(EstablishWorkerSessionRequest::hasJobAttemptFailed)
                  .findFirst()
                  .orElseThrow()
                  .getJobAttemptFailed()
                  .getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  private TranscodeWorker worker(ScriptedProcessLauncher launcher) {
    return workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build();
  }

  private static void awaitEvents(
      ScriptedWorkerRuntime.Connection connection, EventCase... expected) {
    await()
        .atMost(EVENT_LIMIT)
        .untilAsserted(
            () ->
                assertThat(
                        connection.events().stream()
                            .map(EstablishWorkerSessionRequest::getEventCase)
                            .filter(
                                eventCase ->
                                    eventCase != EventCase.REGISTRATION
                                        && eventCase != EventCase.PROBE_CAPACITY_CHANGED))
                    .containsExactly(expected));
  }

  // The SEI NAL units of every array of the first hvcC record (ISO/IEC 14496-15, 8.3.3.1.2), in
  // hexadecimal.
  private static List<String> seiNalUnitsOfTheRecord(byte[] initializationSegment) {
    var hvcC = indexOf(initializationSegment, "hvcC");
    assertThat(hvcC).as("the hvcC box").isNotNegative();
    var record = ByteBuffer.wrap(initializationSegment);
    record.position(hvcC + 4 + HVCC_FIELDS_BEFORE_ARRAYS);
    var arrays = Byte.toUnsignedInt(record.get());
    var seiNalUnits = new ArrayList<String>();
    for (var array = 0; array < arrays; array++) {
      record.get();
      var count = Short.toUnsignedInt(record.getShort());
      for (var nalUnit = 0; nalUnit < count; nalUnit++) {
        var bytes = new byte[Short.toUnsignedInt(record.getShort())];
        record.get(bytes);
        var type = (bytes[0] >> 1) & 0x3F;
        if (type == PREFIX_SEI || type == SUFFIX_SEI) {
          seiNalUnits.add(HexFormat.of().withUpperCase().formatHex(bytes));
        }
      }
    }

    return seiNalUnits;
  }

  // The type of the first entry of the video track's stsd box, the one that the output writes.
  private static String sampleEntryTypeOf(byte[] initializationSegment) {
    var stsd = indexOf(initializationSegment, "stsd");
    assertThat(stsd).as("the stsd box").isNotNegative();
    // The entry follows the stsd's version, flags and entry count, then its own 4-byte size.
    var type = stsd + 4 + 8 + 4;
    return new String(initializationSegment, type, 4, StandardCharsets.US_ASCII);
  }

  // Each Dolby Vision configuration record box, as its type and its body in hexadecimal.
  private static List<String> dolbyVisionRecordsOf(byte[] initializationSegment) {
    var records = new ArrayList<String>();
    for (var type : List.of("dvcC", "dvvC", "dvwC")) {
      var at = indexOf(initializationSegment, type);
      if (at >= 0) {
        var size = ByteBuffer.wrap(initializationSegment).getInt(at - 4);
        var body = Arrays.copyOfRange(initializationSegment, at + 4, at - 4 + size);
        records.add(type + HexFormat.of().withUpperCase().formatHex(body));
      }
    }

    return records;
  }

  // Where the four-character code first appears in the bytes, or -1.
  private static int indexOf(byte[] bytes, String fourcc) {
    var pattern = fourcc.getBytes(StandardCharsets.US_ASCII);
    for (var start = 0; start <= bytes.length - pattern.length; start++) {
      var matches = true;
      for (var offset = 0; offset < pattern.length && matches; offset++) {
        matches = bytes[start + offset] == pattern[offset];
      }

      if (matches) {
        return start;
      }
    }

    return -1;
  }
}
