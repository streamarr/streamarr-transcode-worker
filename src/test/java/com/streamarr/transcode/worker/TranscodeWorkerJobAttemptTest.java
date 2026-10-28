package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.DeliveredSamples.assertCarriesTheRecordedSamples;
import static com.streamarr.transcode.fixtures.DeliveredSamples.audioSamplesOfTheDeliveredSegments;
import static com.streamarr.transcode.fixtures.Races.awaitStart;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.uploadNames;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engineBuilder;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copyJobBuilderFor;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sdrDynamicRange;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.stopVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.SegmentContentType;
import build.buf.gen.streamarr.transcode.v1.SubtitleMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeDecision;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import com.streamarr.transcode.fixtures.TopLevelBoxes.Box;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Job Attempt Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerJobAttemptTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final int UPLOAD_MESSAGE_BYTES = 64 * 1024;
  private static final int RACE_ITERATIONS = 100;
  private static final int BURSTS = 10;
  private static final int BURST_SLOTS = 2;

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should upload every segment FFmpeg writes as fragmented MP4 when the attempt completes")
  void shouldUploadEverySegmentFfmpegWritesAsFragmentedMp4WhenTheAttemptCompletes()
      throws Exception {
    var recording = recording(ENCODED_RECORDING);
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(launcher.hasLaunched(fromProto(job.getJobAttemptId()))).isTrue();
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
      assertThat(connection.uploads())
          .allSatisfy(
              upload -> {
                var metadata = upload.metadata();
                assertThat(metadata.getContentType())
                    .isEqualTo(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4);
                assertThat(metadata.getContentLengthBytes()).isEqualTo(upload.content().length);
                assertThat(metadata.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
                assertThat(metadata.getJobId()).isEqualTo(job.getJobId());
                assertThat(metadata.getStreamSessionId()).isEqualTo(job.getStreamSessionId());
                assertThat(metadata.getVariantLabel())
                    .isEqualTo(job.getVariant().getVariantLabel());
                assertThat(metadata.getWorker()).isEqualTo(connection.registration().getWorker());
              });
      assertCarriesTheRecordedSamples(uploadedBytes(connection), recording);
    }
  }

  @ParameterizedTest(name = "{0}, {1}")
  @CsvSource({
    "TRANSCODE_MODE_REMUX, SUBTITLE_MODE_EXCLUDE, true",
    "TRANSCODE_MODE_FULL_TRANSCODE, SUBTITLE_MODE_EXCLUDE, true",
    "TRANSCODE_MODE_REMUX, SUBTITLE_MODE_SIDECAR, false",
    "TRANSCODE_MODE_FULL_TRANSCODE, SUBTITLE_MODE_SIDECAR, false"
  })
  @DisplayName("Should map the absolute chosen video stream index when a job starts")
  void shouldMapTheAbsoluteChosenVideoStreamIndexWhenAJobStarts(
      TranscodeMode mode, SubtitleMode subtitleMode, boolean excludesSubtitles) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(mode);
    job.getDecisionBuilder().getVideoBuilder().setStreamIndex(2);
    job.getDecisionBuilder().getSubtitleBuilder().setMode(subtitleMode);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      var command = launcher.command(fromProto(job.getJobAttemptId()));
      assertThat(command).containsSequence("-map", "0:2").doesNotContain("0:v:0", "0:v:2");
      assertThat(command.contains("-0:s")).isEqualTo(excludesSubtitles);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName("Should refuse the job when the chosen video stream index is missing")
  void shouldRefuseTheJobWhenTheChosenVideoStreamIndexIsMissing(TranscodeMode mode)
      throws Exception {
    var job = variantJobBuilder(mode);
    job.getDecisionBuilder().getVideoBuilder().clearStreamIndex();

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest
  @CsvSource({"24000/1001, 145", "30000/1001, 181", "4294967295/4294967295, 7"})
  @DisplayName("Should preserve the exact output frame rate when the legacy decimal disagrees")
  void shouldPreserveTheExactOutputFrameRateWhenTheLegacyDecimalDisagrees(
      String exactRate, String expectedGop) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    var terms = exactRate.split("/");
    job.getExecutionBuilder()
        .setFramerate(30)
        .setOutputFrameRate(
            Fraction.newBuilder()
                .setNumerator(Integer.parseUnsignedInt(terms[0]))
                .setDenominator(Integer.parseUnsignedInt(terms[1])));

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-r:v:0", exactRate)
          .containsSequence("-g:v:0", expectedGop);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  @DisplayName("Should refuse the job when the chosen video stream index exceeds FFmpeg's range")
  void shouldRefuseTheJobWhenTheChosenVideoStreamIndexExceedsFfmpegsRange(int streamIndex)
      throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().getVideoBuilder().setStreamIndex(streamIndex);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 17, 128})
  @DisplayName(
      "Should upload a segment in full-sized data messages when it is larger than one message")
  void shouldUploadASegmentInFullSizedDataMessagesWhenItIsLargerThanOneMessage(int segmentMiB)
      throws Exception {
    var segmentBytes = segmentMiB * 1024 * 1024;
    var output = new AtomicReference<>(bytesOf(ENCODED_RECORDING));
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(output.get()).build());
    var recorded = variantJobBuilder().build();
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, recorded);
      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      // The worker rebuilds the first media segment, so only an attempt measures its length before
      // the grown first video sample brings it to the size under test.
      var recordedSegmentBytes = uploadsOf(connection, recorded).get(1).content().length;
      output.set(
          withLargeFirstVideoSample(
              bytesOf(ENCODED_RECORDING), segmentBytes - recordedSegmentBytes));
      startVariant(connection, job);

      awaitEvents(
          connection,
          EventCase.JOB_ATTEMPT_STARTED,
          EventCase.JOB_ATTEMPT_COMPLETED,
          EventCase.JOB_ATTEMPT_STARTED,
          EventCase.JOB_ATTEMPT_COMPLETED);
      var uploads = uploadsOf(connection, job);
      var firstMediaSegment = uploads.get(1);
      assertThat(firstMediaSegment.dataMessageLengths())
          .hasSizeGreaterThan(2)
          .last()
          .satisfies(
              length -> assertThat(length).isPositive().isLessThanOrEqualTo(UPLOAD_MESSAGE_BYTES));
      assertThat(firstMediaSegment.dataMessageLengths().subList(0, 2))
          .containsOnly(UPLOAD_MESSAGE_BYTES);
      assertThat(firstMediaSegment.metadata().getContentLengthBytes()).isEqualTo(segmentBytes);
      assertThat(firstMediaSegment.content()).hasSize(segmentBytes);
      var uploaded = uploadedBytes(uploads);
      assertCarriesTheRecordedSamples(uploaded, recording(ENCODED_RECORDING), output.get());
      var uploadedMovie = Mp4Samples.read(uploaded);
      assertThat(uploadedMovie.samplesOf(uploadedMovie.audioTrack()))
          .containsExactlyElementsOf(
              audioSamplesOfTheDeliveredSegments(recording(ENCODED_RECORDING), true));
    }
  }

  @Test
  @DisplayName("Should ask FFmpeg to quit when the worker cannot report that the attempt started")
  void shouldAskFfmpegToQuitWhenTheWorkerCannotReportThatTheAttemptStarted() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.registration();
      connection.refuseMessages();

      startVariant(connection, job);

      var process = processOf(launcher, job);
      assertThat(process.isAlive()).isFalse();
      assertThat(process.stdinText()).isEqualTo("q");
      assertThat(eventsOf(connection)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should send each upload message only when the upload call is ready for it")
  void shouldSendEachUploadMessageOnlyWhenTheUploadCallIsReadyForIt() throws Exception {
    var recording = recording(ENCODED_RECORDING);
    var job = variantJobBuilder().build();

    try (var worker = worker(ScriptedProcessLauncher.writing(ENCODED_RECORDING))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);

      // The metadata and one data message for the initialization segment and each segment.
      var expectedMessages = 2 * (recording.segments().size() + 1);
      for (var granted = 1; granted <= expectedMessages; granted++) {
        connection.grantUploadMessage();
        var sent = granted;
        await()
            .pollInterval(Duration.ofMillis(5))
            .atMost(EVENT_LIMIT)
            .until(() -> connection.uploadMessageCount() >= sent);
      }

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(connection.uploadMessagesSentWhileNotReady()).isZero();
      assertThat(connection.uploadMessageCount()).isEqualTo(expectedMessages);
    }
  }

  @Test
  @DisplayName(
      "Should refuse a job beyond the advertised slots as a startup failure without launching"
          + " FFmpeg")
  void shouldRefuseAJobBeyondTheAdvertisedSlotsAsAStartupFailureWithoutLaunchingFfmpeg()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var first = variantJobBuilder().build();
    var second = variantJobBuilder().build();
    var beyond = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      assertThat(connection.registration().getAvailableSlots()).isEqualTo(2);
      startVariant(connection, first);
      startVariant(connection, second);

      startVariant(connection, beyond);

      assertThat(eventsOf(connection))
          .containsExactly(
              EventCase.JOB_ATTEMPT_STARTED,
              EventCase.JOB_ATTEMPT_STARTED,
              EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getJobAttemptId())
          .isEqualTo(beyond.getJobAttemptId());
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      assertThat(launcher.hasLaunched(fromProto(beyond.getJobAttemptId()))).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should run a job in the slot a stopped attempt freed while its FFmpeg is still quitting")
  void shouldRunAJobInTheSlotAStoppedAttemptFreedWhileItsFfmpegIsStillQuitting() throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcessLauncher.runningProcessBuilder()
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var stopped = variantJobBuilder().build();
    var running = variantJobBuilder().build();
    var next = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, stopped);
      startVariant(connection, running);
      var quitting = processOf(launcher, stopped);
      stopVariant(connection, stopped);
      await().atMost(EVENT_LIMIT).until(() -> quitting.stdinText().equals("q"));

      startVariant(connection, next);

      assertThat(quitting.isAlive()).isTrue();
      assertThat(eventsOf(connection))
          .containsExactly(
              EventCase.JOB_ATTEMPT_STARTED,
              EventCase.JOB_ATTEMPT_STARTED,
              EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.hasLaunched(fromProto(next.getJobAttemptId()))).isTrue();
      quitting.exit();
      processOf(launcher, running).exit();
      processOf(launcher, next).exit();
    }
  }

  @Test
  @DisplayName("Should reuse freed slots when stops and starts arrive in bursts")
  void shouldReuseFreedSlotsWhenStopsAndStartsArriveInBursts() throws Exception {
    var recording = recording(ENCODED_RECORDING);
    var firstFragmentOfSegment1 = recording.segments().get(1).firstFragmentIndex();
    var output = bytesOf(ENCODED_RECORDING);
    var readerWaits = endOfFragment(output, firstFragmentOfSegment1);
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(
                        Arrays.copyOf(output, endOfFragment(output, firstFragmentOfSegment1 + 2)))
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var started = new ArrayList<VariantJob>();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      var running = startJobs(connection);
      started.addAll(running);
      awaitEachReaderAt(launcher, running, readerWaits);
      for (var burst = 0; burst < BURSTS; burst++) {
        var stopped = running;
        stopJobs(connection, stopped);
        running = startJobs(connection);
        started.addAll(running);

        awaitEachReaderAt(launcher, running, readerWaits);
        assertThat(stopped)
            .allSatisfy(
                job -> {
                  assertThat(processOf(launcher, job).isAlive()).isTrue();
                  assertThat(terminalEventsOf(connection, job)).isEmpty();
                });
        stopped.forEach(job -> processOf(launcher, job).exit());
        stopped.forEach(job -> awaitTerminalEvent(connection, job));
      }

      stopJobs(connection, running);
      running.forEach(job -> processOf(launcher, job).exit());
      running.forEach(job -> awaitTerminalEvent(connection, job));
      assertThat(started)
          .hasSize((BURSTS + 1) * BURST_SLOTS)
          .allSatisfy(
              job ->
                  assertThat(terminalEventsOf(connection, job))
                      .containsExactly(EventCase.JOB_ATTEMPT_STOPPED));
      assertThat(eventsOf(connection)).doesNotContain(EventCase.JOB_ATTEMPT_FAILED);
    }
  }

  private static List<VariantJob> startJobs(ScriptedWorkerRuntime.Connection connection) {
    var jobs = IntStream.range(0, BURST_SLOTS).mapToObj(_ -> variantJobBuilder().build()).toList();
    for (var job : jobs) {
      startVariant(connection, job);
    }

    return jobs;
  }

  private static void stopJobs(ScriptedWorkerRuntime.Connection connection, List<VariantJob> jobs) {
    for (var job : jobs) {
      stopVariant(connection, job);
    }
  }

  private static void awaitEachReaderAt(
      ScriptedProcessLauncher launcher, List<VariantJob> jobs, int offset) {
    jobs.forEach(job -> promptly().until(() -> processOf(launcher, job).bytesTaken() == offset));
  }

  private static ScriptedProcess processOf(ScriptedProcessLauncher launcher, VariantJob job) {
    return launcher.process(fromProto(job.getJobAttemptId()));
  }

  // Where the fragment at this position after the initialization segment ends.
  private static int endOfFragment(byte[] output, int fragmentIndex) {
    return TopLevelBoxes.of(output).stream()
        .filter(box -> box.type().equals("mdat"))
        .skip(fragmentIndex)
        .findFirst()
        .orElseThrow()
        .end();
  }

  @Test
  @DisplayName(
      "Should refuse the job as an invalid specification when it carries no video decision")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItCarriesNoVideoDecision() throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().clearVideo();

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoDecisionsThatTheTranscodeModeContradicts")
  @DisplayName(
      "Should refuse the job as an invalid specification when its video decision contradicts the"
          + " transcode mode")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItsVideoDecisionContradictsTheTranscodeMode(
      TranscodeMode mode, VideoMode videoMode) throws Exception {
    var job = variantJobBuilder(mode);
    job.getDecisionBuilder().getVideoBuilder().setMode(videoMode);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> videoDecisionsThatTheTranscodeModeContradicts() {
    return Stream.of(
        Arguments.of(TranscodeMode.TRANSCODE_MODE_REMUX, VideoMode.VIDEO_MODE_ENCODE),
        Arguments.of(TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE, VideoMode.VIDEO_MODE_ENCODE),
        Arguments.of(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE, VideoMode.VIDEO_MODE_COPY),
        Arguments.of(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE, VideoMode.VIDEO_MODE_COPY),
        Arguments.of(TranscodeMode.TRANSCODE_MODE_REMUX, VideoMode.VIDEO_MODE_UNSPECIFIED),
        Arguments.of(
            TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE, VideoMode.VIDEO_MODE_UNSPECIFIED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteEncodes")
  @DisplayName(
      "Should refuse the job as an invalid specification when its encode lacks a value that the"
          + " worker forces or checks")
  void
      shouldRefuseTheJobAsAnInvalidSpecificationWhenItsEncodeLacksAValueThatTheWorkerForcesOrChecks(
          String lacking, UnaryOperator<VideoDecision.Builder> incomplete) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    incomplete.apply(job.getDecisionBuilder().getVideoBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> incompleteEncodes() {
    return Stream.of(
        Arguments.of("codec family", unary(VideoDecision.Builder::clearCodecFamily)),
        Arguments.of("codec profile", unary(VideoDecision.Builder::clearCodecProfile)),
        Arguments.of("level", unary(VideoDecision.Builder::clearLevel)),
        Arguments.of("codec string", unary(VideoDecision.Builder::clearCodecString)),
        Arguments.of("source dynamic range", unary(VideoDecision.Builder::clearSourceDynamicRange)),
        Arguments.of(
            "output dynamic range", unary(VideoDecision.Builder::clearOutputDynamicRange)));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CompatibleBase.class,
      names = {"COMPATIBLE_BASE_SDR", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Should refuse the job when an encode requests an unsupported output compatible base")
  void shouldRefuseTheJobWhenAnEncodeRequestsAnUnsupportedOutputCompatibleBase(
      CompatibleBase compatibleBase) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(VideoDynamicRange.getDefaultInstance())
        .setOutputDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(compatibleBase)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance()));

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest
  @MethodSource("unsupportedSdrOutputs")
  @DisplayName("Should refuse the job when SDR output does not exclude HDR enhancements")
  void shouldRefuseTheJobWhenSdrOutputDoesNotExcludeHdrEnhancements(VideoDynamicRange output)
      throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setOutputDynamicRange(output);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<VideoDynamicRange> unsupportedSdrOutputs() {
    var sdr =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
            .setHdr10Plus(Presence.PRESENCE_ABSENT)
            .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    return Stream.of(
        sdr.clone().clearHdr10Plus().build(),
        sdr.clone().setHdr10Plus(Presence.PRESENCE_PRESENT).build(),
        sdr.clone().clearDolbyVision().build(),
        sdr.clone().setDolbyVisionPresent(DolbyVisionPresent.getDefaultInstance()).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceDynamicRanges")
  @DisplayName("Should follow the server's compatible base when an encode carries HDR details")
  void shouldFollowTheServersCompatibleBaseWhenAnEncodeCarriesHdrDetails(
      String description, VideoDynamicRange source, boolean toneMapped) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setSourceDynamicRange(source);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      var command = launcher.command(fromProto(job.getJobAttemptId()));
      var filter = command.get(command.indexOf("-vf") + 1);
      assertThat(filter.contains("tonemapx=")).as(description).isEqualTo(toneMapped);
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = DolbyVisionRpuPlacement.class,
      names = {
        "DOLBY_VISION_RPU_PLACEMENT_BEFORE_NEXT_PICTURE",
        "DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED"
      })
  @DisplayName(
      "Should launch an FFmpeg that copies the source and an FFmpeg that encodes the copy when the"
          + " encode's Dolby Vision profile 5 source may start samples with RPUs")
  void
      shouldLaunchAnFfmpegThatCopiesTheSourceAndAnFfmpegThatEncodesTheCopyWhenTheEncodesDolbyVisionProfile5SourceMayStartSamplesWithRpus(
          DolbyVisionRpuPlacement placement) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setObservedRpu(Presence.PRESENCE_PRESENT)
                        .setRpuPlacement(placement)));
    job.getDecisionBuilder().getAudioBuilder().setCodec("eac3");

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      var commands = launcher.commands(fromProto(job.getJobAttemptId()));
      assertThat(commands).hasSize(2);
      assertThat(commands.getFirst())
          .containsSequence("-c:v", "copy")
          .containsSequence(
              "-movie_timescale",
              "1000000",
              "-flush_packets",
              "1",
              "pipe:1",
              "-map",
              "0:0",
              "-c:v",
              "copy",
              "-f",
              "framecrc",
              "-flush_packets",
              "1");
      assertThat(commands.getFirst().getLast()).startsWith("unix:");
      assertThat(commands.getLast()).containsSequence("-f", "mp4", "-i", "pipe:0");
    }
  }

  @Test
  @DisplayName(
      "Should launch one FFmpeg when every RPU of the encode's Dolby Vision profile 5 source follows"
          + " its picture")
  void shouldLaunchOneFfmpegWhenEveryRpuOfTheEncodesDolbyVisionProfile5SourceFollowsItsPicture()
      throws Exception {
    var placement = DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE;
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setObservedRpu(Presence.PRESENCE_PRESENT)
                        .setRpuPlacement(placement)));
    job.getDecisionBuilder().getAudioBuilder().setCodec("eac3");

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.commands(fromProto(job.getJobAttemptId()))).hasSize(1);
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoCodecFamily.class,
      names = {
        "VIDEO_CODEC_FAMILY_UNSPECIFIED",
        "VIDEO_CODEC_FAMILY_H264",
        "VIDEO_CODEC_FAMILY_AV1"
      })
  @DisplayName(
      "Should launch one FFmpeg when the encode's Dolby Vision profile 5 source starts samples with"
          + " RPUs and is not HEVC")
  void
      shouldLaunchOneFfmpegWhenTheEncodesDolbyVisionProfile5SourceStartsSamplesWithRpusAndIsNotHevc(
          VideoCodecFamily sourceCodecFamily) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceCodecFamily(sourceCodecFamily)
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setObservedRpu(Presence.PRESENCE_PRESENT)
                        .setRpuPlacement(
                            DolbyVisionRpuPlacement
                                .DOLBY_VISION_RPU_PLACEMENT_BEFORE_NEXT_PICTURE)));
    job.getDecisionBuilder().getAudioBuilder().setCodec("eac3");

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.commands(fromProto(job.getJobAttemptId()))).hasSize(1);
    }
  }

  static Stream<Arguments> sourceDynamicRanges() {
    var pq = VideoDynamicRange.newBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ);
    var declared =
        DolbyVisionConfiguration.newBuilder()
            .setProfile(5)
            .setLevel(6)
            .setBaseLayerCompatibilityId(0)
            .setBaseLayer(true)
            .setRpu(true);
    var dolbyVision =
        VideoDynamicRange.newBuilder()
            .setHdr10Plus(Presence.PRESENCE_ABSENT)
            .setDolbyVisionPresent(
                DolbyVisionPresent.newBuilder()
                    .setDeclared(declared)
                    .setObservedRpu(Presence.PRESENCE_PRESENT));
    return Stream.of(
        Arguments.of("unspecified", VideoDynamicRange.getDefaultInstance(), false),
        Arguments.of("PQ", pq.build(), true),
        Arguments.of("HDR10+", pq.clone().setHdr10Plus(Presence.PRESENCE_PRESENT).build(), true),
        Arguments.of(
            "profile 5",
            dolbyVision.clone().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE).build(),
            true),
        Arguments.of(
            "profile 8.2",
            dolbyVision
                .clone()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setDeclared(declared.clone().setProfile(8).setBaseLayerCompatibilityId(2))
                        .setObservedRpu(Presence.PRESENCE_ABSENT))
                .build(),
            false),
        Arguments.of(
            "RPU without declared configuration",
            pq.clone()
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder().setObservedRpu(Presence.PRESENCE_PRESENT))
                .build(),
            true),
        Arguments.of(
            "incomplete declared configuration",
            pq.clone()
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setDeclared(DolbyVisionConfiguration.getDefaultInstance()))
                .build(),
            true),
        Arguments.of(
            "HLG without enhancements",
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_HLG)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                .build(),
            true));
  }

  @ParameterizedTest
  @MethodSource("unknownSourceDynamicRangeValues")
  @DisplayName("Should refuse the job when source dynamic range carries an unknown enum value")
  void shouldRefuseTheJobWhenSourceDynamicRangeCarriesAnUnknownEnumValue(VideoDynamicRange source)
      throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setSourceDynamicRange(source);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<VideoDynamicRange> unknownSourceDynamicRangeValues() {
    return Stream.of(
        VideoDynamicRange.newBuilder().setCompatibleBaseValue(100).build(),
        VideoDynamicRange.newBuilder().setHdr10PlusValue(100).build(),
        VideoDynamicRange.newBuilder()
            .setDolbyVisionPresent(DolbyVisionPresent.newBuilder().setObservedRpuValue(100))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("jobsWithoutAMode")
  @DisplayName(
      "Should refuse the job as an invalid specification when it names no transcode, audio or"
          + " subtitle mode")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItNamesNoTranscodeAudioOrSubtitleMode(
      String lacking, UnaryOperator<TranscodeDecision.Builder> withoutMode) throws Exception {
    var job = variantJobBuilder();
    withoutMode.apply(job.getDecisionBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> jobsWithoutAMode() {
    return Stream.of(
        Arguments.of(
            "transcode mode",
            decisionChange(decision -> decision.setMode(TranscodeMode.TRANSCODE_MODE_UNSPECIFIED))),
        Arguments.of(
            "audio mode",
            decisionChange(
                decision -> {
                  decision.getAudioBuilder().setMode(AudioMode.AUDIO_MODE_UNSPECIFIED);
                  return decision;
                })),
        Arguments.of(
            "subtitle mode",
            decisionChange(
                decision -> {
                  decision.getSubtitleBuilder().setMode(SubtitleMode.SUBTITLE_MODE_UNSPECIFIED);
                  return decision;
                })));
  }

  private static UnaryOperator<TranscodeDecision.Builder> decisionChange(
      UnaryOperator<TranscodeDecision.Builder> change) {
    return change;
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoCodecFamily.class,
      names = {"VIDEO_CODEC_FAMILY_HEVC", "VIDEO_CODEC_FAMILY_AV1"})
  @DisplayName(
      "Should refuse the job as an invalid specification when an HEVC or AV1 encode carries no"
          + " tier")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAnHevcOrAv1EncodeCarriesNoTier(
      VideoCodecFamily family) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .clearVideoCodecFamily()
        .getVideoBuilder()
        .setCodecFamily(family)
        .setCodecProfile(mainProfileOf(family))
        .clearTier();

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @Test
  @DisplayName(
      "Should refuse the job as an invalid specification when an H.264 encode carries a tier")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAnH264EncodeCarriesATier() throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWithEncodeValues")
  @DisplayName(
      "Should refuse the job as an invalid specification when a stream copy carries encode values")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAStreamCopyCarriesEncodeValues(
      String carrying, UnaryOperator<VideoDecision.Builder> withValue) throws Exception {
    var job = variantJobBuilder();
    withValue.apply(job.getDecisionBuilder().getVideoBuilder());

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> copiesWithEncodeValues() {
    return Stream.of(
        Arguments.of(
            "codec profile",
            unary(video -> video.setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH))),
        Arguments.of("tier", unary(video -> video.setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN))),
        Arguments.of("level", unary(video -> video.setLevel(10))),
        Arguments.of(
            "empty output dynamic range",
            unary(video -> video.setOutputDynamicRange(VideoDynamicRange.getDefaultInstance()))),
        Arguments.of(
            "SDR output dynamic range",
            unary(video -> video.setOutputDynamicRange(sdrDynamicRange()))));
  }

  private static VideoCodecProfile mainProfileOf(VideoCodecFamily family) {
    return switch (family) {
      case VIDEO_CODEC_FAMILY_HEVC -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN;
      case VIDEO_CODEC_FAMILY_AV1 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN;
      default -> throw new AssertionError("No Main profile is listed for " + family);
    };
  }

  @Test
  @DisplayName(
      "Should refuse the job as an invalid specification when a stream copy carries no codec"
          + " string")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAStreamCopyCarriesNoCodecString()
      throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().getVideoBuilder().clearCodecString();

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoCodecProfile.class,
      names = {"VIDEO_CODEC_PROFILE_HEVC_MAIN", "VIDEO_CODEC_PROFILE_AV1_MAIN"})
  @DisplayName(
      "Should refuse the job as an invalid specification when its codec profile belongs to another"
          + " codec family")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItsCodecProfileBelongsToAnotherCodecFamily(
      VideoCodecProfile profile) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setCodecProfile(profile);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoCodecProfile.class,
      names = {
        "VIDEO_CODEC_PROFILE_UNSPECIFIED",
        "VIDEO_CODEC_PROFILE_H264_HIGH",
        "VIDEO_CODEC_PROFILE_HEVC_MAIN",
        "VIDEO_CODEC_PROFILE_AV1_MAIN",
        "UNRECOGNIZED"
      },
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Should refuse the job as an invalid specification when an encode carries a codec profile"
          + " that only a probe reports")
  void
      shouldRefuseTheJobAsAnInvalidSpecificationWhenAnEncodeCarriesACodecProfileThatOnlyAProbeReports(
          VideoCodecProfile profile) throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setCodecProfile(profile);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName(
      "Should refuse the job as an invalid specification when its video decision and its codec"
          + " family string name different codec families")
  void
      shouldRefuseTheJobAsAnInvalidSpecificationWhenItsVideoDecisionAndItsCodecFamilyStringNameDifferentCodecFamilies(
          TranscodeMode mode) throws Exception {
    var job = variantJobBuilder(mode);
    job.getDecisionBuilder().setVideoCodecFamily("av1");

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @Test
  @DisplayName(
      "Should encode the codec family that the video decision names when the job sends no codec"
          + " family string")
  void shouldEncodeTheCodecFamilyThatTheVideoDecisionNamesWhenTheJobSendsNoCodecFamilyString()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .clearVideoCodecFamily()
        .getVideoBuilder()
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
        .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-c:v", "libsvtav1");
    }
  }

  @Test
  @DisplayName("Should force the High tier when an HEVC encode carries the High tier")
  void shouldForceTheHighTierWhenAnHevcEncodeCarriesTheHighTier() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder()
        .clearVideoCodecFamily()
        .getVideoBuilder()
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
        .setTier(VideoCodecTier.VIDEO_CODEC_TIER_HIGH)
        .setLevel(41)
        .setCodecString("hvc1.1.6.H123.90");

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-c:v", "libx265")
          .containsSequence("-profile:v", "main")
          .containsSequence("-x265-params", "no-scenecut=1:no-open-gop=1:level-idc=41:high-tier=1");
    }
  }

  @Test
  @DisplayName("Should force the job's codec profile and level when the job encodes H.264")
  void shouldForceTheJobsCodecProfileAndLevelWhenTheJobEncodesH264() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setLevel(42).setCodecString("avc1.64002A");

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-c:v", "libx264")
          .containsSequence("-profile:v", "high", "-level:v", "42");
    }
  }

  @Test
  @DisplayName(
      "Should report an output mismatch and upload nothing when the initialization segment declares"
          + " another level than the job forces")
  void
      shouldReportAnOutputMismatchAndUploadNothingWhenTheInitializationSegmentDeclaresAnotherLevelThanTheJobForces()
          throws Exception {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setLevel(42).setCodecString("avc1.64002A");

    try (var worker = worker(ScriptedProcessLauncher.writing(ENCODED_RECORDING))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
      assertThat(connection.uploads()).isEmpty();
    }
  }

  private static UnaryOperator<VideoDecision.Builder> unary(
      UnaryOperator<VideoDecision.Builder> operator) {
    return operator;
  }

  @ParameterizedTest(name = "container value {0}")
  @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
  @DisplayName(
      "Should refuse the job as an invalid specification when it asks for another container")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItAsksForAnotherContainer(int container)
      throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().setContainerValue(container);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0} at {1} fps")
  @MethodSource("videoEncodingsWithoutAUsableFrameRate")
  @DisplayName(
      "Should refuse the job as an invalid specification when it encodes video without a usable"
          + " frame rate")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItEncodesVideoWithoutAUsableFrameRate(
      TranscodeMode mode, Optional<Fraction> frameRate) throws Exception {
    var job = variantJobBuilder(mode);
    job.getExecutionBuilder().clearOutputFrameRate();
    frameRate.ifPresent(job.getExecutionBuilder()::setOutputFrameRate);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> videoEncodingsWithoutAUsableFrameRate() {
    return Stream.of(
            TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE,
            TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .flatMap(
            mode ->
                Stream.of(
                        Optional.<Fraction>empty(),
                        Optional.of(Fraction.getDefaultInstance()),
                        Optional.of(Fraction.newBuilder().setNumerator(24).build()),
                        Optional.of(Fraction.newBuilder().setDenominator(1).build()))
                    .map(frameRate -> Arguments.of(mode, frameRate)));
  }

  @ParameterizedTest(name = "{0}, {1} media segments from segment {2}")
  @MethodSource("jobsAdvertisingNoMediaSegmentFromTheirStart")
  @DisplayName(
      "Should refuse the job as an invalid specification when no advertised media segment follows"
          + " its start")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenNoAdvertisedMediaSegmentFollowsItsStart(
      TranscodeMode mode, int mediaSegmentCount, int startSequenceNumber) throws Exception {
    var job = variantJobBuilder(mode);
    job.getExecutionBuilder()
        .setMediaSegmentCount(mediaSegmentCount)
        .setStartSequenceNumber(startSequenceNumber);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> jobsAdvertisingNoMediaSegmentFromTheirStart() {
    return Stream.of(
            TranscodeMode.TRANSCODE_MODE_REMUX, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .flatMap(
            mode ->
                Stream.of(
                    Arguments.of(mode, 0, 0), Arguments.of(mode, 5, 5), Arguments.of(mode, 4, 5)));
  }

  @ParameterizedTest(name = "{0} is {1}")
  @MethodSource("encoderPresetNames")
  @DisplayName("Should map the encoder preset when the job carries one")
  void shouldMapEncoderPresetWhenJobCarriesOne(EncoderPreset preset, String presetName)
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getVariantBuilder().setEncoderPreset(preset);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-c:v", "libx264", "-preset", presetName);
    }
  }

  static Stream<Arguments> encoderPresetNames() {
    return Stream.of(
        Arguments.of(EncoderPreset.ENCODER_PRESET_SUPERFAST, "superfast"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_VERYFAST, "veryfast"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_FASTER, "faster"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_FAST, "fast"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_MEDIUM, "medium"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_SLOW, "slow"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_SLOWER, "slower"),
        Arguments.of(EncoderPreset.ENCODER_PRESET_VERYSLOW, "veryslow"));
  }

  @ParameterizedTest(name = "{0}, preset value {1}")
  @MethodSource("videoEncodingsWithoutAPreset")
  @DisplayName(
      "Should refuse the video job as an invalid specification when its preset is unspecified")
  void shouldRefuseVideoJobWhenPresetIsUnspecified(TranscodeMode mode, int presetValue)
      throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getVariantBuilder().setEncoderPresetValue(presetValue);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> videoEncodingsWithoutAPreset() {
    return Stream.of(
            TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE,
            TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .flatMap(
            mode ->
                IntStream.of(EncoderPreset.ENCODER_PRESET_UNSPECIFIED_VALUE, Integer.MAX_VALUE)
                    .mapToObj(presetValue -> Arguments.of(mode, presetValue)));
  }

  // ADR 0044 leaves ultrafast off the preset scale: libx264 writes Constrained Baseline at
  // ultrafast, whatever profile the job asks for.
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_VIDEO_TRANSCODE", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName(
      "Should refuse the video job as an invalid specification when its preset is ultrafast")
  void shouldRefuseVideoJobWhenPresetIsUltrafast(TranscodeMode mode) throws Exception {
    var job = variantJobBuilder(mode);
    job.getVariantBuilder().setEncoderPreset(EncoderPreset.ENCODER_PRESET_ULTRAFAST);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_AUDIO_TRANSCODE"})
  @DisplayName("Should run the job without a preset when it copies the video")
  void shouldRunTheJobWithoutAPresetWhenItCopiesTheVideo(TranscodeMode mode) throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getVariantBuilder().setEncoderPreset(EncoderPreset.ENCODER_PRESET_UNSPECIFIED);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId()))).doesNotContain("-preset");
    }
  }

  @Test
  @DisplayName(
      "Should pass the job's own preset to every attempt when a replacement attempt runs the same"
          + " job")
  void shouldPassTheJobsOwnPresetToEveryAttemptWhenAReplacementAttemptRunsTheSameJob()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var first = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    first.getVariantBuilder().setEncoderPreset(EncoderPreset.ENCODER_PRESET_SLOW);
    var replacement = first.clone().setJobAttemptId(toProto(UUID.randomUUID()));
    replacement.getExecutionBuilder().setStartSequenceNumber(5);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, first.build());
      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      startVariant(connection, replacement.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(List.of(first, replacement))
          .extracting(job -> launcher.command(fromProto(job.getJobAttemptId())))
          .allSatisfy(command -> assertThat(command).containsSequence("-preset", "slow"));
    }
  }

  @Test
  @DisplayName(
      "Should force keyframes from the job's start to its last advertised media segment when the"
          + " job encodes video")
  void shouldForceKeyframesFromTheJobsStartToItsLastAdvertisedMediaSegmentWhenTheJobEncodesVideo()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(4)
        .setStartSequenceNumber(2)
        .setMediaSegmentCount(5);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSequence("-force_key_frames:0", "8,12,16");
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_AUDIO_TRANSCODE"})
  @DisplayName(
      "Should seek a stream copy to its start sequence number's boundary when the job's seek"
          + " position names another time")
  void
      shouldSeekAStreamCopyToItsStartSequenceNumbersBoundaryWhenTheJobsSeekPositionNamesAnotherTime(
          TranscodeMode mode) throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder(mode);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(6)
        .setStartSequenceNumber(5)
        .setSeekPositionSeconds(17);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSubsequence("-ss", "30", "-i");
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_AUDIO_TRANSCODE"})
  @DisplayName(
      "Should seek a stream copy to the server's keyframe position when the job carries one")
  void shouldSeekAStreamCopyToTheServersKeyframePositionWhenTheJobCarriesOne(TranscodeMode mode)
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(6)
        .setStartSequenceNumber(5)
        .setSeekPositionSeconds(30)
        .getSeekPositionBuilder()
        .setSeconds(31)
        .setNanos(281_250_000);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED);
      assertThat(launcher.command(fromProto(job.getJobAttemptId())))
          .containsSubsequence("-ss", "31.28125", "-i");
    }
  }

  @Test
  @DisplayName(
      "Should complete the attempt with every media segment when its encoded audio's last frames"
          + " arrive after their media segment was delivered")
  void
      shouldCompleteTheAttemptWithEveryMediaSegmentWhenItsEncodedAudiosLastFramesArriveAfterTheirMediaSegmentWasDelivered()
          throws Exception {
    var recording = recording("17-encode-audio-ends-long-before-video.fmp4");
    assertThat(recording.droppedAudioSamples()).isNotEmpty();
    var job = variantJobBuilder();
    job.getExecutionBuilder().setMediaSegmentCount(recording.mediaSegmentCount());

    try (var worker = worker(ScriptedProcessLauncher.writing(recording.file()))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
    }
  }

  @Test
  @DisplayName("Should run the job when the last advertised media segment is the one it starts at")
  void shouldRunTheJobWhenTheLastAdvertisedMediaSegmentIsTheOneItStartsAt() throws Exception {
    var recording = recording("07-copy-seek30.fmp4");
    var job = copyJobBuilderFor(recording);
    job.getExecutionBuilder()
        .setStartSequenceNumber(recording.startSequenceNumber())
        .setMediaSegmentCount(recording.startSequenceNumber() + 1);

    try (var worker = worker(ScriptedProcessLauncher.writing(recording.file()))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_AUDIO_TRANSCODE"})
  @DisplayName("Should run the job when it copies the video without a probed frame rate")
  void shouldRunTheJobWhenItCopiesTheVideoWithoutAProbedFrameRate(TranscodeMode mode)
      throws Exception {
    var job = variantJobBuilder(mode);
    job.getExecutionBuilder().clearOutputFrameRate();

    try (var worker = worker(ScriptedProcessLauncher.writing(ENCODED_RECORDING))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
    }
  }

  @Test
  @DisplayName("Should fail the attempt as a transcode failure when FFmpeg exits with an error")
  void shouldFailTheAttemptAsATranscodeFailureWhenFfmpegExitsWithAnError() throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ -> ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).exitCode(1).build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getJobAttemptId())
          .isEqualTo(job.getJobAttemptId());
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure and end FFmpeg when reading its output"
          + " throws unexpectedly")
  void shouldFailTheAttemptAsATranscodeFailureAndEndFfmpegWhenReadingItsOutputThrowsUnexpectedly()
      throws Exception {
    var failureOffset = recording(ENCODED_RECORDING).initializationSegment().byteLength() + 100;
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .failReadAfter(failureOffset)
                    .failReadWith(new IllegalStateException("scripted defect"))
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(processOf(launcher, job).wasDestroyedForcibly()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should upload the segments before the skipped number, then fail the attempt and end FFmpeg,"
          + " when source keyframes are further apart than the period")
  void
      shouldUploadTheSegmentsBeforeTheSkippedNumberThenFailTheAttemptAndEndFfmpegWhenSourceKeyframesAreFurtherApartThanThePeriod()
          throws Exception {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var launcher = ScriptedProcessLauncher.writing(recording.file());
    var jobBuilder = copyJobBuilderFor(recording);
    jobBuilder
        .getExecutionBuilder()
        .setTargetSegmentDurationSeconds(recording.period())
        .setStartSequenceNumber(recording.startSequenceNumber());
    var job = jobBuilder.build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
      assertCarriesTheRecordedSamples(uploadedBytes(connection), recording);
      assertThat(processOf(launcher, job).wasDestroyedForcibly()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should return to the control stream and report the stop only after FFmpeg has exited when"
          + " stopped")
  void shouldReturnToTheControlStreamAndReportTheStopOnlyAfterFfmpegHasExitedWhenStopped()
      throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcessLauncher.runningProcessBuilder()
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);
      var process = processOf(launcher, job);

      stopVariant(connection, job);

      assertThat(process.isAlive()).isTrue();
      await().atMost(EVENT_LIMIT).until(() -> process.stdinText().equals("q"));
      assertThat(eventsOf(connection)).containsExactly(EventCase.JOB_ATTEMPT_STARTED);
      process.exit();
      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName("Should wait for a stopping FFmpeg to exit when the worker closes")
  void shouldWaitForAStoppingFfmpegToExitWhenTheWorkerCloses() throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcessLauncher.runningProcessBuilder()
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);
      var process = processOf(launcher, job);
      stopVariant(connection, job);
      await().atMost(EVENT_LIMIT).until(() -> process.stdinText().equals("q"));

      var closing = CompletableFuture.runAsync(worker::close);

      await().during(Duration.ofMillis(200)).atMost(EVENT_LIMIT).until(() -> !closing.isDone());
      process.exit();
      assertThat(closing).succeedsWithin(EVENT_LIMIT);
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should let FFmpeg exit and send no further upload message when stopped while an upload"
          + " awaits readiness")
  void shouldLetFfmpegExitAndSendNoFurtherUploadMessageWhenStoppedWhileAnUploadAwaitsReadiness()
      throws Exception {
    var launcher = writingUntilTheTestExits();
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);
      await().atMost(EVENT_LIMIT).until(() -> connection.uploads().size() == 1);
      var process = processOf(launcher, job);

      stopVariant(connection, job);

      assertStopReportedOnlyOnceFfmpegExits(connection, process);
      connection.grantUploadMessage();
      await()
          .during(Duration.ofMillis(200))
          .atMost(EVENT_LIMIT)
          .until(() -> connection.uploadMessageCount() == 0);
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasCancelled()).isTrue());
      assertThat(process.hasReadToEndOfOutput()).isTrue();
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should let FFmpeg exit and report the stop when stopped while an upload awaits"
          + " acknowledgement")
  void shouldLetFfmpegExitAndReportTheStopWhenStoppedWhileAnUploadAwaitsAcknowledgement()
      throws Exception {
    var launcher = writingUntilTheTestExits();
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, job);
      await()
          .atMost(EVENT_LIMIT)
          .until(
              () ->
                  connection.uploads().size() == 1
                      && connection.uploads().getFirst().awaitsAcknowledgement());
      var process = processOf(launcher, job);

      stopVariant(connection, job);

      assertStopReportedOnlyOnceFfmpegExits(connection, process);
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasCancelled()).isTrue());
      assertThat(process.hasReadToEndOfOutput()).isTrue();
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt within the readiness bound when the receiver never reports"
          + " readiness")
  void shouldFailTheAttemptWithinTheReadinessBoundWhenTheReceiverNeverReportsReadiness()
      throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJobBuilder().build();
    var configuration =
        configurationBuilder().uploadReadinessTimeout(Duration.ofMillis(200)).build();

    try (var worker =
        workerBuilder(tempDir)
            .runtime(runtime)
            .engine(engine(launcher))
            .configuration(configuration)
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploadMessageCount()).isZero();
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasCancelled()).isTrue());
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt within the acknowledgement deadline when the receiver never"
          + " acknowledges")
  void shouldFailTheAttemptWithinTheAcknowledgementDeadlineWhenTheReceiverNeverAcknowledges()
      throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJobBuilder().build();
    var configuration =
        configurationBuilder().uploadAcknowledgementTimeout(Duration.ofMillis(200)).build();

    try (var worker =
        workerBuilder(tempDir)
            .runtime(runtime)
            .engine(engine(launcher))
            .configuration(configuration)
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, job);

      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(
              () ->
                  assertThat(eventsOf(connection))
                      .containsExactly(
                          EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED));
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasCancelled()).isTrue());
    }
  }

  @Test
  @DisplayName(
      "Should count the acknowledgement deadline from the first message when readiness comes"
          + " slowly")
  void shouldCountTheAcknowledgementDeadlineFromTheFirstMessageWhenReadinessComesSlowly()
      throws Exception {
    var output = withLargeFirstVideoSample(bytesOf(ENCODED_RECORDING), 3 * UPLOAD_MESSAGE_BYTES);
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(output).build());
    var job = variantJobBuilder().build();
    var configuration =
        configurationBuilder().uploadAcknowledgementTimeout(Duration.ofMillis(300)).build();

    try (var worker =
            workerBuilder(tempDir)
                .runtime(runtime)
                .engine(engine(launcher))
                .configuration(configuration)
                .build();
        var receiver = Executors.newSingleThreadScheduledExecutor()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);

      // Each message waits 100 ms for readiness; the first media segment's messages need longer
      // than the deadline in all.
      receiver.scheduleAtFixedRate(connection::grantUploadMessage, 0, 100, TimeUnit.MILLISECONDS);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4", "segment0.m4s");
      assertThat(connection.uploads().getLast().wasCancelled()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt and terminate FFmpeg when FFmpeg writes nothing for the stall"
          + " timeout")
  void shouldFailTheAttemptAndTerminateFfmpegWhenFfmpegWritesNothingForTheStallTimeout()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder().build();
    var engine = engineBuilder(launcher).encoderStallTimeout(Duration.ofMillis(200)).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).engine(engine).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(processOf(launcher, job).wasTerminated()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure and terminate FFmpeg when FFmpeg does not"
          + " exit within the grace period after its output ends")
  void
      shouldFailTheAttemptAsATranscodeFailureAndTerminateFfmpegWhenFfmpegDoesNotExitWithinTheGracePeriodAfterItsOutputEnds()
          throws Exception {
    var recording = recording(ENCODED_RECORDING);
    var launcher = writingUntilTheTestExits();
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
      var process = processOf(launcher, job);
      assertThat(process.wasTerminated()).isTrue();
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should complete the attempt when the receiver withholds readiness longer than the stall"
          + " timeout")
  void shouldCompleteTheAttemptWhenTheReceiverWithholdsReadinessLongerThanTheStallTimeout()
      throws Exception {
    var job = variantJobBuilder().build();
    var engine =
        engineBuilder(ScriptedProcessLauncher.writing(ENCODED_RECORDING))
            .encoderStallTimeout(Duration.ofMillis(100))
            .build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).engine(engine).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);
      await().atMost(EVENT_LIMIT).until(() -> connection.uploads().size() == 1);

      await()
          .during(Duration.ofMillis(500))
          .atMost(EVENT_LIMIT)
          .until(() -> eventsOf(connection).equals(List.of(EventCase.JOB_ATTEMPT_STARTED)));
      for (var granted = 0; granted < 100; granted++) {
        connection.grantUploadMessage();
      }

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
    }
  }

  @Test
  @DisplayName(
      "Should report the stop to its own session and not to the next when the worker reconnects")
  void shouldReportTheStopToItsOwnSessionAndNotToTheNextWhenTheWorkerReconnects() throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcessLauncher.runningProcessBuilder()
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var firstSession = runtime.connection();
      startVariant(firstSession, job);
      var process = processOf(launcher, job);
      stopVariant(firstSession, job);
      await().atMost(EVENT_LIMIT).until(() -> process.stdinText().equals("q"));
      var closing = CompletableFuture.runAsync(worker::close);
      process.exit();
      assertThat(closing).succeedsWithin(EVENT_LIMIT);

      worker.start("localhost", 1);
      var nextSession = runtime.connection();
      nextSession.registration();

      assertThat(process.wasDestroyedForcibly()).isFalse();
      assertThat(eventsOf(firstSession))
          .containsExactly(EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(eventsOf(nextSession)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should report the stop without a forced kill when FFmpeg's output breaks after the stop")
  void shouldReportTheStopWithoutAForcedKillWhenFfmpegsOutputBreaksAfterTheStop() throws Exception {
    var failureOffset = recording(ENCODED_RECORDING).initializationSegment().byteLength() + 100;
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .pauseAfter(failureOffset)
                    .failReadAfter(failureOffset)
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);
      var process = processOf(launcher, job);
      promptly().until(process::hasReachedPause);
      stopVariant(connection, job);
      promptly().until(() -> process.stdinText().equals("q"));

      process.resume();

      await()
          .during(Duration.ofMillis(200))
          .atMost(EVENT_LIMIT)
          .until(() -> eventsOf(connection).equals(List.of(EventCase.JOB_ATTEMPT_STARTED)));
      process.exit();
      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should report the recorded failure without asking FFmpeg to quit when stopped before the"
          + " failure settles")
  void shouldReportTheRecordedFailureWithoutAskingFfmpegToQuitWhenStoppedBeforeTheFailureSettles()
      throws Exception {
    var failureOffset = recording(ENCODED_RECORDING).initializationSegment().byteLength() + 100;
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .failReadAfter(failureOffset)
                    .lingersAfterKill(true)
                    .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);
      var process = processOf(launcher, job);
      promptly().until(process::wasDestroyedForcibly);

      stopVariant(connection, job);

      await()
          .during(Duration.ofMillis(200))
          .atMost(EVENT_LIMIT)
          .until(() -> eventsOf(connection).equals(List.of(EventCase.JOB_ATTEMPT_STARTED)));
      process.exit();
      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(process.stdinText()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should report once as whichever the producer recorded first when a stop races a failure")
  void shouldReportOnceAsWhicheverTheProducerRecordedFirstWhenAStopRacesAFailure()
      throws Exception {
    // FFmpeg's output breaks at this offset once the test lets the reader past its pause, and
    // FFmpeg exits when asked to quit.
    var failureOffset = recording(ENCODED_RECORDING).initializationSegment().byteLength() + 100;
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .pauseAfter(failureOffset)
                    .failReadAfter(failureOffset)
                    .exitTiming(ExitTiming.AT_QUIT)
                    .build());
    var jobs = new ArrayList<VariantJob>();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      for (var iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
        var job = variantJobBuilder().build();
        jobs.add(job);
        startVariant(connection, job);
        var process = processOf(launcher, job);
        promptly().until(process::hasReachedPause);
        var start = new CyclicBarrier(2);
        var failing = Thread.ofVirtual().start(() -> resumeAt(start, process));

        awaitStart(start);
        stopVariant(connection, job);

        assertThat(failing.join(EVENT_LIMIT)).isTrue();
        var reported = awaitTerminalEvent(connection, job);
        assertThat(reported == EventCase.JOB_ATTEMPT_STOPPED)
            .as("the stop was recorded first, so it asked FFmpeg to quit")
            .isEqualTo(process.stdinText().equals("q"));
      }

      assertThat(jobs).allSatisfy(job -> assertThat(terminalEventsOf(connection, job)).hasSize(1));
    }
  }

  // Lets the paused FFmpeg's output continue into its failure once the race's other side is ready.
  private static void resumeAt(CyclicBarrier start, ScriptedProcess process) {
    awaitStart(start);
    process.resume();
  }

  @Test
  @DisplayName(
      "Should report the stop and let FFmpeg quit when the stop arrives while the first upload"
          + " opens")
  void shouldReportTheStopAndLetFfmpegQuitWhenTheStopArrivesWhileTheFirstUploadOpens()
      throws Exception {
    // FFmpeg writes at once and exits only when asked to quit, so every attempt ends in its stop.
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .exitTiming(ExitTiming.AT_QUIT)
                    .build());
    var jobs = new ArrayList<VariantJob>();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      for (var iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
        var job = variantJobBuilder().build();
        jobs.add(job);
        startVariant(connection, job);
        stopVariant(connection, job);
        awaitTerminalEvent(connection, job);
      }

      assertThat(jobs)
          .allSatisfy(
              job -> {
                assertThat(terminalEventsOf(connection, job))
                    .containsExactly(EventCase.JOB_ATTEMPT_STOPPED);
                assertThat(processOf(launcher, job).wasDestroyedForcibly()).isFalse();
              });
    }
  }

  // FFmpeg that writes the recording at once and exits only when the test lets it.
  private static ScriptedProcessLauncher writingUntilTheTestExits() {
    return new ScriptedProcessLauncher(
        _ ->
            ScriptedProcess.builder()
                .output(bytesOf(ENCODED_RECORDING))
                .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                .build());
  }

  // The worker asks FFmpeg to quit, reports nothing while FFmpeg lives, and reports the stop once
  // it
  // has exited.
  private static void assertStopReportedOnlyOnceFfmpegExits(
      ScriptedWorkerRuntime.Connection connection, ScriptedProcess process) {
    promptly().until(() -> process.stdinText().equals("q"));
    await()
        .during(Duration.ofMillis(200))
        .atMost(EVENT_LIMIT)
        .until(() -> eventsOf(connection).equals(List.of(EventCase.JOB_ATTEMPT_STARTED)));
    process.exit();
    awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STOPPED);
  }

  // The worker reports the job attempt failed as an invalid specification and never starts FFmpeg.
  private void assertRefusedAsAnInvalidSpecification(VariantJob job) throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      assertThat(eventsOf(connection)).containsExactly(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  // The worker configuration the fixture worker uses, to adjust.
  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configurationBuilder() {
    return workerConfigurationBuilder()
        .availableSlots(2)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }

  private TranscodeWorker worker(ScriptedProcessLauncher launcher) {
    return workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build();
  }

  private static EventCase awaitTerminalEvent(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    promptly().until(() -> !terminalEventsOf(connection, job).isEmpty());
    return terminalEventsOf(connection, job).getFirst();
  }

  // How the worker reported the end of the job's attempt: completed, failed or stopped.
  private static List<EventCase> terminalEventsOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.events().stream()
        .filter(event -> endedAttemptOf(event).equals(Optional.of(job.getJobAttemptId())))
        .map(EstablishWorkerSessionRequest::getEventCase)
        .toList();
  }

  private static Optional<Uuid> endedAttemptOf(EstablishWorkerSessionRequest event) {
    return switch (event.getEventCase()) {
      case JOB_ATTEMPT_COMPLETED -> Optional.of(event.getJobAttemptCompleted().getJobAttemptId());
      case JOB_ATTEMPT_FAILED -> Optional.of(event.getJobAttemptFailed().getJobAttemptId());
      case JOB_ATTEMPT_STOPPED -> Optional.of(event.getJobAttemptStopped().getJobAttemptId());
      default -> Optional.empty();
    };
  }

  private static ConditionFactory promptly() {
    return await().atMost(EVENT_LIMIT).pollInterval(Duration.ofMillis(5));
  }

  private static void awaitEvents(
      ScriptedWorkerRuntime.Connection connection, EventCase... expected) {
    await()
        .atMost(EVENT_LIMIT)
        .untilAsserted(() -> assertThat(eventsOf(connection)).containsExactly(expected));
  }

  // The job attempt events, without the registration and the probe capacity changes.
  private static List<EventCase> eventsOf(ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(
            eventCase ->
                eventCase != EventCase.REGISTRATION
                    && eventCase != EventCase.PROBE_CAPACITY_CHANGED)
        .toList();
  }

  private static EstablishWorkerSessionRequest lastEvent(
      ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .filter(event -> !event.hasProbeCapacityChanged())
        .toList()
        .getLast();
  }

  private static byte[] uploadedBytes(ScriptedWorkerRuntime.Connection connection) {
    return uploadedBytes(connection.uploads());
  }

  private static byte[] uploadedBytes(List<ScriptedWorkerRuntime.UploadCall> uploads) {
    var uploaded = new ByteArrayOutputStream();
    uploads.forEach(upload -> uploaded.writeBytes(upload.content()));
    return uploaded.toByteArray();
  }

  private static List<ScriptedWorkerRuntime.UploadCall> uploadsOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.uploads().stream()
        .filter(upload -> upload.metadata().getJobAttemptId().equals(job.getJobAttemptId()))
        .toList();
  }

  // The recording with its first video sample grown by this many bytes, which the producer copies
  // into the first media segment without looking inside it.
  private static byte[] withLargeFirstVideoSample(byte[] recording, int growth) {
    var boxes = TopLevelBoxes.of(recording);
    var moof = boxes.stream().filter(box -> box.type().equals("moof")).findFirst().orElseThrow();
    var mdat = boxes.get(boxes.indexOf(moof) + 1);
    var fields = ByteBuffer.wrap(recording.clone());
    var runs = runsOf(fields, moof);
    var videoRun = runs.getFirst();
    var firstSampleEnd =
        moof.start() + fields.getInt(videoRun + 16) + firstSampleSize(fields, videoRun);
    fields.putInt(
        sizeFieldOfFirstSample(fields, videoRun), firstSampleSize(fields, videoRun) + growth);
    runs.subList(1, runs.size())
        .forEach(run -> fields.putInt(run + 16, fields.getInt(run + 16) + growth));
    fields.putInt(mdat.start(), mdat.size() + growth);
    var grown = new ByteArrayOutputStream();
    grown.write(fields.array(), 0, firstSampleEnd);
    grown.writeBytes(new byte[growth]);
    grown.write(fields.array(), firstSampleEnd, recording.length - firstSampleEnd);
    return grown.toByteArray();
  }

  // Where each trun of the moof starts, in traf order: the video traf's first, as FFmpeg writes it.
  private static List<Integer> runsOf(ByteBuffer fields, Box moof) {
    return childrenOf(fields, moof.start(), "traf").stream()
        .flatMap(traf -> childrenOf(fields, traf, "trun").stream())
        .toList();
  }

  // Where each child box of this type starts inside the box that starts here.
  private static List<Integer> childrenOf(ByteBuffer fields, int box, String type) {
    var children = new ArrayList<Integer>();
    var fourcc = ByteBuffer.wrap(type.getBytes(US_ASCII)).getInt();
    for (var child = box + 8; child < box + fields.getInt(box); child += fields.getInt(child)) {
      if (fields.getInt(child + 4) == fourcc) {
        children.add(child);
      }
    }

    return children;
  }

  // A trun's first sample entry follows its data offset and first-sample flags; the recordings'
  // video runs carry each sample's size, after its duration when they carry durations.
  private static int sizeFieldOfFirstSample(ByteBuffer fields, int run) {
    var flags = fields.getInt(run + 8) & 0xFF_FFFF;
    var field = run + 16;
    for (var presentField : new int[] {0x1, 0x4, 0x100}) {
      if ((flags & presentField) != 0) {
        field += 4;
      }
    }

    return field;
  }

  private static int firstSampleSize(ByteBuffer fields, int run) {
    return fields.getInt(sizeFieldOfFirstSample(fields, run));
  }
}
