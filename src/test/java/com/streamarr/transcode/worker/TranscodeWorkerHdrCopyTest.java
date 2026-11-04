package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copyJobBuilderFor;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
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
            unary(video -> video.setContentLightLevel(CONTENT_LIGHT_LEVEL))));
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

  private static ChromaticityCoordinates coordinates(int x, int y) {
    return ChromaticityCoordinates.newBuilder().setX(x).setY(y).build();
  }

  private static UnaryOperator<VideoDecision.Builder> unary(
      UnaryOperator<VideoDecision.Builder> change) {
    return change;
  }

  // Runs the job over FFmpeg output and returns the initialization segment that the worker uploads.
  private byte[] initializationSegmentOf(VariantJob job, byte[] ffmpegOutput) throws Exception {
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(ffmpegOutput).build());

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(launcher.hasLaunched(fromProto(job.getJobAttemptId()))).isTrue();
      return connection.uploads().stream()
          .filter(upload -> upload.metadata().getSegmentName().equals("init.mp4"))
          .findFirst()
          .orElseThrow()
          .content();
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
