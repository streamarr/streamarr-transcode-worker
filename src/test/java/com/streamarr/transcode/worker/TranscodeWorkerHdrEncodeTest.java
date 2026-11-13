package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.MatrixCoefficients;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs 10-bit and HDR encode jobs (streamarr/streamarr-transcode-worker#27, the contract of
 * streamarr/streamarr-server#809) through the worker and reads the FFmpeg command it launches, or
 * the failure it reports for a job that the contract forbids. The SDR encodes that assume a source
 * matrix (ADR 0013) run here too, beside the other conversions of the picture's colour.
 */
@Tag("UnitTest")
@DisplayName("Transcode Worker HDR Encode Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerHdrEncodeTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);

  /** The fallback display of decision 173: P3 D65, 1000 cd/m² and 0.0001 cd/m². */
  private static final MasteringDisplayColorVolume P3_D65_DISPLAY =
      MasteringDisplayColorVolume.newBuilder()
          .setRed(coordinates(34000, 16000))
          .setGreen(coordinates(13250, 34500))
          .setBlue(coordinates(7500, 3000))
          .setWhitePoint(coordinates(15635, 16450))
          .setMaxLuminance(10_000_000)
          .setMinLuminance(1)
          .build();

  private static final ContentLightLevel CONTENT_LIGHT_LEVEL =
      ContentLightLevel.newBuilder()
          .setMaxContentLightLevel(1000)
          .setMaxFrameAverageLightLevel(400)
          .build();

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should encode HEVC Main 10 with the job's static HDR metadata and PQ colour description when"
          + " an HDR10 source encodes to PQ")
  void shouldEncodeHevcMain10WithTheJobsStaticHdrMetadataWhenAnHdr10SourceEncodesToPq()
      throws Exception {
    var command = commandOf(hdr10Encode(hevcMain10(), CompatibleBase.COMPATIBLE_BASE_PQ));

    assertThat(valueOf(command, "-vf"))
        .contains(
            "format=yuv420p10le,"
                + "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv")
        .doesNotContain("tonemapx");
    assertThat(valueOf(command, "-profile:v")).isEqualTo("main10");
    assertThat(List.of(valueOf(command, "-x265-params").split(":")))
        .contains(
            "hdr10=1",
            "colorprim=bt2020",
            "transfer=smpte2084",
            "colormatrix=bt2020nc",
            "range=limited",
            "master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)",
            "max-cll=1000,400");
    assertPqColourDescription(command);
  }

  @Test
  @DisplayName(
      "Should give SVT-AV1 one parameter value with the bitrate cap, the PQ colour description and"
          + " the static HDR metadata in its own units when an HDR10 source encodes to AV1 PQ")
  void shouldGiveSvtAv1OneParameterValueWithTheStaticHdrMetadataWhenAnHdr10SourceEncodesToAv1Pq()
      throws Exception {
    var command = commandOf(hdr10Encode(av1Main(), CompatibleBase.COMPATIBLE_BASE_PQ));

    assertThat(command).containsOnlyOnce("-svtav1-params");
    assertThat(List.of(valueOf(command, "-svtav1-params").split(":")))
        .containsExactly(
            "mbr-overshoot-pct=0",
            "color-primaries=bt2020",
            "transfer-characteristics=smpte2084",
            "matrix-coefficients=bt2020-ncl",
            "color-range=studio",
            "mastering-display=G(0.265,0.69)B(0.15,0.06)R(0.68,0.32)WP(0.3127,0.329)L(1000,0.0001)",
            "content-light=1000,400");
    assertThat(valueOf(command, "-vf")).contains("format=yuv420p10le");
    assertPqColourDescription(command);
  }

  @Test
  @DisplayName(
      "Should encode the HLG colour description without static HDR metadata or tone mapping when an"
          + " HLG source encodes to HLG")
  void shouldEncodeTheHlgColourDescriptionWithoutStaticHdrMetadataWhenAnHlgSourceEncodesToHlg()
      throws Exception {
    var job = tenBitEncode(hevcMain10(), CompatibleBase.COMPATIBLE_BASE_HLG);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .contains(
            "format=yuv420p10le,"
                + "setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv")
        .doesNotContain("tonemapx");
    assertThat(List.of(valueOf(command, "-x265-params").split(":")))
        .contains("transfer=arib-std-b67", "colorprim=bt2020")
        .noneMatch(
            parameter -> parameter.startsWith("master-display") || parameter.equals("hdr10=1"));
    assertThat(valueOf(command, "-color_trc")).isEqualTo("arib-std-b67");
  }

  // SVT-AV1 takes transfer characteristics 18 only as "hlg" or as its number (SVT-AV1's
  // Docs/Parameters.md), while FFmpeg's own options name it arib-std-b67.
  @Test
  @DisplayName(
      "Should name the HLG transfer as SVT-AV1 names it in SVT-AV1's parameters when an HLG source"
          + " encodes to AV1 HLG")
  void shouldNameTheHlgTransferAsSvtAv1NamesItWhenAnHlgSourceEncodesToAv1Hlg() throws Exception {
    var command = commandOf(tenBitEncode(av1Main(), CompatibleBase.COMPATIBLE_BASE_HLG));

    assertThat(List.of(valueOf(command, "-svtav1-params").split(":")))
        .containsExactly(
            "mbr-overshoot-pct=0",
            "color-primaries=bt2020",
            "transfer-characteristics=hlg",
            "matrix-coefficients=bt2020-ncl",
            "color-range=studio");
    assertThat(valueOf(command, "-color_trc")).isEqualTo("arib-std-b67");
  }

  @Test
  @DisplayName(
      "Should reshape Dolby Vision into PQ with tonemapx from planar 10-bit video when a profile 5"
          + " source encodes to PQ")
  void shouldReshapeDolbyVisionIntoPqWithTonemapxWhenAProfile5SourceEncodesToPq() throws Exception {
    var job = hdr10Encode(hevcMain10(), CompatibleBase.COMPATIBLE_BASE_PQ);
    job.getDecisionBuilder().getVideoBuilder().setSourceDynamicRange(profile5());

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .contains(
            "format=yuv420p10le,tonemapx=t=smpte2084:p=bt2020:m=bt2020:r=tv:format=yuv420p10le,");
    assertPqColourDescription(command);
  }

  @Test
  @DisplayName(
      "Should encode 10-bit BT.709 video without tone mapping when a 10-bit SDR encode carries no"
          + " HDR range")
  void shouldEncode10BitBt709VideoWithoutToneMappingWhenA10BitSdrEncodeCarriesNoHdrRange()
      throws Exception {
    var job = tenBitEncode(av1Main(), CompatibleBase.COMPATIBLE_BASE_SDR);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .contains("out_transfer=bt709,format=yuv420p10le")
        .doesNotContain("tonemapx");
    assertThat(command).doesNotContain("-color_trc");
  }

  @Test
  @DisplayName(
      "Should encode 8-bit 4:2:0 when an older server's SDR encode carries no bit depth or chroma"
          + " subsampling")
  void shouldEncode8Bit420WhenAnOlderServersSdrEncodeCarriesNoBitDepthOrChromaSubsampling()
      throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf")).contains("format=yuv420p,");
  }

  // ADR 0013: the job names the matrix of an SDR source that names none, and the worker reads every
  // frame with it. swscale would otherwise read such a frame as BT.601 at every picture size.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"MATRIX_COEFFICIENTS_BT709, bt709", "MATRIX_COEFFICIENTS_SMPTE170M, smpte170m"})
  @DisplayName(
      "Should read the source with the matrix that the job assumes when an SDR encode assumes one")
  void shouldReadTheSourceWithTheMatrixThatTheJobAssumesWhenAnSdrEncodeAssumesOne(
      MatrixCoefficients assumed, String matrix) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setAssumedSourceMatrix(assumed);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .containsPattern(
            "scale=-2:\\d+:in_color_matrix="
                + matrix
                + ":out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709,");
  }

  // The server assumes a matrix for a source whose compatible base is SDR as for one whose base
  // is unspecified: a stream can name its transfer without naming its matrix.
  @Test
  @DisplayName(
      "Should read the source with the matrix that the job assumes when the source's compatible"
          + " base is SDR")
  void shouldReadTheSourceWithTheMatrixThatTheJobAssumesWhenTheSourcesCompatibleBaseIsSdr()
      throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(range(CompatibleBase.COMPATIBLE_BASE_SDR))
        .setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT709);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf")).contains(":in_color_matrix=bt709:out_color_matrix=bt709:");
  }

  @Test
  @DisplayName(
      "Should read each frame with the matrix that the frame names when an SDR encode assumes none")
  void shouldReadEachFrameWithTheMatrixThatTheFrameNamesWhenAnSdrEncodeAssumesNone()
      throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .containsPattern("scale=-2:\\d+:out_color_matrix=bt709:")
        .doesNotContain("in_color_matrix");
  }

  // The contract lets only an SDR encode assume a matrix, and only BT.709 or SMPTE 170M.
  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenAssumedSourceMatrices")
  @DisplayName(
      "Should refuse the job as an invalid specification when it assumes a source matrix that the"
          + " contract forbids")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItAssumesAForbiddenSourceMatrix(
      String assumption, VariantJob.Builder job) throws Exception {
    assertRefusedAsAnInvalidSpecification(job);
  }

  static Stream<Arguments> forbiddenAssumedSourceMatrices() {
    var encode = TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE;
    return Stream.of(
        forbidden(
            "BT.2020 for an SDR encode",
            variantJobBuilder(encode),
            video ->
                video.setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_NCL)),
        forbidden(
            "a matrix that this worker's contract does not name",
            variantJobBuilder(encode),
            video -> video.setAssumedSourceMatrixValue(99)),
        forbidden(
            "BT.709 for a PQ source",
            variantJobBuilder(encode),
            video ->
                video
                    .setSourceDynamicRange(range(CompatibleBase.COMPATIBLE_BASE_PQ))
                    .setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT709)),
        forbidden(
            "BT.709 for a stream copy",
            variantJobBuilder(TranscodeMode.TRANSCODE_MODE_REMUX),
            video -> video.setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT709)));
  }

  // swscale converts a source only into the colour it is told to, and the filters then describe
  // every frame as limited-range BT.2020, so a full-range source's levels would play clipped and a
  // BT.709 matrix or other primaries would play with wrong colours.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"COMPATIBLE_BASE_PQ, smpte2084", "COMPATIBLE_BASE_HLG, arib-std-b67"})
  @DisplayName(
      "Should scale the source into limited-range BT.2020 with its own transfer before describing"
          + " its frames that way when an HDR source keeps its transfer")
  void shouldScaleTheSourceIntoLimitedRangeBt2020WhenAnHdrSourceKeepsItsTransfer(
      CompatibleBase base, String transfer) throws Exception {
    var job =
        base == CompatibleBase.COMPATIBLE_BASE_PQ
            ? hdr10Encode(hevcMain10(), base)
            : tenBitEncode(hevcMain10(), base);

    var command = commandOf(job);

    assertThat(valueOf(command, "-vf"))
        .containsPattern(
            "scale=-2:\\d+:out_color_matrix=bt2020nc:out_range=tv:out_primaries=bt2020"
                + ":out_transfer="
                + transfer
                + ",");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenEncodes")
  @DisplayName(
      "Should refuse the job as an invalid specification when its encode asks for an output that"
          + " the contract forbids")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItsEncodeAsksForAForbiddenOutput(
      String output, VariantJob.Builder job) throws Exception {
    assertRefusedAsAnInvalidSpecification(job);
  }

  private void assertRefusedAsAnInvalidSpecification(VariantJob.Builder job) throws Exception {
    var launcher = ScriptedProcessLauncher.running();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

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

  static Stream<Arguments> forbiddenEncodes() {
    var pq = CompatibleBase.COMPATIBLE_BASE_PQ;
    var hlg = CompatibleBase.COMPATIBLE_BASE_HLG;
    var sdr = CompatibleBase.COMPATIBLE_BASE_SDR;
    return Stream.of(
        forbidden(
            "PQ in 8 bits", hdr10Encode(hevcMain10(), pq), video -> video.setOutputBitDepth(8)),
        forbidden(
            "PQ without a bit depth",
            hdr10Encode(av1Main(), pq),
            VideoDecision.Builder::clearOutputBitDepth),
        forbidden(
            "HLG without a chroma subsampling",
            tenBitEncode(hevcMain10(), hlg),
            VideoDecision.Builder::clearOutputChromaSubsampling),
        forbidden("12 bits", tenBitEncode(av1Main(), sdr), video -> video.setOutputBitDepth(12)),
        forbidden(
            "4:2:2",
            tenBitEncode(av1Main(), sdr),
            video -> video.setOutputChromaSubsampling(ChromaSubsampling.CHROMA_SUBSAMPLING_422)),
        forbidden(
            "10-bit H.264",
            variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE),
            video -> video.setOutputBitDepth(10)),
        forbidden(
            "10-bit HEVC Main",
            tenBitEncode(hevcMain10(), sdr),
            video -> video.setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)),
        forbidden(
            "8-bit HEVC Main 10",
            tenBitEncode(hevcMain10(), sdr),
            video -> video.setOutputBitDepth(8)),
        forbidden(
            "PQ without a mastering display",
            hdr10Encode(av1Main(), pq),
            VideoDecision.Builder::clearMasteringDisplay),
        forbidden(
            "PQ without a content light level",
            hdr10Encode(av1Main(), pq),
            VideoDecision.Builder::clearContentLightLevel),
        forbidden(
            "static HDR metadata on an HLG encode",
            tenBitEncode(hevcMain10(), hlg),
            video ->
                video
                    .setMasteringDisplay(P3_D65_DISPLAY)
                    .setContentLightLevel(CONTENT_LIGHT_LEVEL)),
        forbidden(
            "static HDR metadata on an SDR encode",
            tenBitEncode(av1Main(), sdr),
            video ->
                video
                    .setMasteringDisplay(P3_D65_DISPLAY)
                    .setContentLightLevel(CONTENT_LIGHT_LEVEL)),
        forbidden(
            "PQ from an SDR source",
            hdr10Encode(av1Main(), pq),
            video -> video.setSourceDynamicRange(range(sdr))),
        forbidden(
            "PQ from an HLG source",
            hdr10Encode(hevcMain10(), pq),
            video -> video.setSourceDynamicRange(range(hlg))),
        forbidden(
            "HLG from a PQ source",
            tenBitEncode(hevcMain10(), hlg),
            video -> video.setSourceDynamicRange(range(pq))),
        forbidden(
            "HLG from a Dolby Vision profile 5 source",
            tenBitEncode(hevcMain10(), hlg),
            video -> video.setSourceDynamicRange(profile5())),
        forbidden(
            "PQ with HDR10+",
            hdr10Encode(av1Main(), pq),
            video ->
                video.setOutputDynamicRange(
                    range(pq).toBuilder().setHdr10Plus(Presence.PRESENCE_PRESENT))),
        forbidden(
            "PQ with Dolby Vision",
            hdr10Encode(av1Main(), pq),
            video ->
                video.setOutputDynamicRange(
                    range(pq).toBuilder()
                        .setDolbyVisionPresent(DolbyVisionPresent.getDefaultInstance()))));
  }

  private static Arguments forbidden(
      String output, VariantJob.Builder job, UnaryOperator<VideoDecision.Builder> change) {
    change.apply(job.getDecisionBuilder().getVideoBuilder());
    return Arguments.of(output, job);
  }

  private static VariantJob.Builder hdr10Encode(VideoDecision.Builder codec, CompatibleBase base) {
    var job = tenBitEncode(codec, base);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setMasteringDisplay(P3_D65_DISPLAY)
        .setContentLightLevel(CONTENT_LIGHT_LEVEL);
    return job;
  }

  private static VariantJob.Builder tenBitEncode(VideoDecision.Builder codec, CompatibleBase base) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().clearVideoCodecFamily();
    job.getDecisionBuilder()
        .getVideoBuilder()
        .mergeFrom(codec.build())
        .setSourceDynamicRange(range(base))
        .setOutputDynamicRange(range(base))
        .setOutputBitDepth(10)
        .setOutputChromaSubsampling(ChromaSubsampling.CHROMA_SUBSAMPLING_420);
    return job;
  }

  private static VideoDecision.Builder hevcMain10() {
    return VideoDecision.newBuilder()
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10)
        .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
        .setLevel(120)
        .setCodecString("hvc1.2.4.L120.90");
  }

  private static VideoDecision.Builder av1Main() {
    return VideoDecision.newBuilder()
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
        .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
        .setLevel(8)
        .setCodecString("av01.0.08M.10");
  }

  private static VideoDynamicRange range(CompatibleBase base) {
    return VideoDynamicRange.newBuilder()
        .setCompatibleBase(base)
        .setHdr10Plus(Presence.PRESENCE_ABSENT)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
        .build();
  }

  private static VideoDynamicRange profile5() {
    return VideoDynamicRange.newBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
        .setHdr10Plus(Presence.PRESENCE_ABSENT)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setDeclared(
                    DolbyVisionConfiguration.newBuilder()
                        .setProfile(5)
                        .setLevel(1)
                        .setBaseLayerCompatibilityId(0)
                        .setBaseLayer(true)
                        .setRpu(true))
                .setObservedRpu(Presence.PRESENCE_PRESENT))
        .build();
  }

  private static ChromaticityCoordinates coordinates(int x, int y) {
    return ChromaticityCoordinates.newBuilder().setX(x).setY(y).build();
  }

  private static void assertPqColourDescription(List<String> command) {
    assertThat(valueOf(command, "-color_primaries")).isEqualTo("bt2020");
    assertThat(valueOf(command, "-color_trc")).isEqualTo("smpte2084");
    assertThat(valueOf(command, "-colorspace")).isEqualTo("bt2020nc");
    assertThat(valueOf(command, "-color_range")).isEqualTo("tv");
  }

  private static String valueOf(List<String> command, String option) {
    assertThat(command).as("the command's %s options", option).containsOnlyOnce(option);
    return command.get(command.indexOf(option) + 1);
  }

  private List<String> commandOf(VariantJob.Builder job) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var built = job.build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, built);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      return launcher.command(fromProto(built.getJobAttemptId()));
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
}
