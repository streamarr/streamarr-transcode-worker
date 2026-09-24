package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.deliveredBytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.uploadNames;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engineBuilder;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.stopVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.SegmentContentType;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Job Attempt Tests")
class TranscodeWorkerJobAttemptTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final int UPLOAD_MESSAGE_BYTES = 64 * 1024;
  private static final int RACE_ITERATIONS = 100;

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
          .extracting(upload -> (long) upload.content().length)
          .containsExactlyElementsOf(expectedLengths(recording));
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
      var uploaded = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> uploaded.writeBytes(upload.content()));
      assertThat(uploaded.toByteArray()).isEqualTo(bytesOf(ENCODED_RECORDING));
    }
  }

  @Test
  @DisplayName(
      "Should upload a segment in full-sized data messages when it is larger than one message")
  void shouldUploadASegmentInFullSizedDataMessagesWhenItIsLargerThanOneMessage() throws Exception {
    var output = withLargeFirstMediaData(bytesOf(ENCODED_RECORDING));
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(output).build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      var firstMediaSegment = connection.uploads().get(1);
      assertThat(firstMediaSegment.dataMessageLengths())
          .hasSizeGreaterThan(2)
          .last()
          .satisfies(
              length -> assertThat(length).isPositive().isLessThanOrEqualTo(UPLOAD_MESSAGE_BYTES));
      assertThat(firstMediaSegment.dataMessageLengths().subList(0, 2))
          .containsOnly(UPLOAD_MESSAGE_BYTES);
      assertThat(firstMediaSegment.metadata().getContentLengthBytes())
          .isEqualTo(firstMediaSegment.content().length);
      var uploaded = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> uploaded.writeBytes(upload.content()));
      assertThat(uploaded.toByteArray()).isEqualTo(output);
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

      var process = launcher.process(fromProto(job.getJobAttemptId()));
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
      var quitting = launcher.process(fromProto(stopped.getJobAttemptId()));
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
      launcher.process(fromProto(running.getJobAttemptId())).exit();
      launcher.process(fromProto(next.getJobAttemptId())).exit();
    }
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
      TranscodeMode mode, double framerate) throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getExecutionBuilder().setFramerate(framerate);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  static Stream<Arguments> videoEncodingsWithoutAUsableFrameRate() {
    return Stream.of(
            TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE,
            TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .flatMap(
            mode ->
                DoubleStream.of(0, -24, Double.NaN, Double.POSITIVE_INFINITY)
                    .mapToObj(framerate -> Arguments.of(mode, framerate)));
  }

  @ParameterizedTest(name = "{0}, {1} media segments from segment {2}")
  @MethodSource("jobsAdvertisingNoMediaSegmentFromTheirStart")
  @DisplayName(
      "Should refuse the job as an invalid specification when no advertised media segment follows"
          + " its start")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenNoAdvertisedMediaSegmentFollowsItsStart(
      TranscodeMode mode, int mediaSegmentCount, int startSequenceNumber) throws Exception {
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
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

  @Test
  @DisplayName(
      "Should force keyframes from the job's start to its last advertised media segment when the"
          + " job encodes video")
  void shouldForceKeyframesFromTheJobsStartToItsLastAdvertisedMediaSegmentWhenTheJobEncodesVideo()
      throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
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
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
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

  @Test
  @DisplayName("Should run the job when the last advertised media segment is the one it starts at")
  void shouldRunTheJobWhenTheLastAdvertisedMediaSegmentIsTheOneItStartsAt() throws Exception {
    var recording = recording("07-copy-seek30.fmp4");
    var job = variantJobBuilder();
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
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getExecutionBuilder().setFramerate(0);

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
      assertThat(launcher.process(fromProto(job.getJobAttemptId())).wasDestroyedForcibly())
          .isTrue();
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
    var jobBuilder = variantJobBuilder();
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
      var uploaded = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> uploaded.writeBytes(upload.content()));
      assertThat(uploaded.toByteArray()).isEqualTo(deliveredBytesOf(recording));
      assertThat(launcher.process(fromProto(job.getJobAttemptId())).wasDestroyedForcibly())
          .isTrue();
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
      var process = launcher.process(fromProto(job.getJobAttemptId()));

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
      var process = launcher.process(fromProto(job.getJobAttemptId()));
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
      var process = launcher.process(fromProto(job.getJobAttemptId()));

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
      var process = launcher.process(fromProto(job.getJobAttemptId()));

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
    var output = withLargeFirstMediaData(bytesOf(ENCODED_RECORDING));
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
      assertThat(launcher.process(fromProto(job.getJobAttemptId())).wasTerminated()).isTrue();
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
      var process = launcher.process(fromProto(job.getJobAttemptId()));
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
      var process = launcher.process(fromProto(job.getJobAttemptId()));
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
      var process = launcher.process(fromProto(job.getJobAttemptId()));
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
        var process = launcher.process(fromProto(job.getJobAttemptId()));
        promptly().until(process::hasReachedPause);
        var start = new CyclicBarrier(2);
        var failing = Thread.ofVirtual().start(() -> resumeAt(start, process));

        awaitBarrier(start);
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
    awaitBarrier(start);
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
                assertThat(
                        launcher.process(fromProto(job.getJobAttemptId())).wasDestroyedForcibly())
                    .isFalse();
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

  private TranscodeWorker worker(ScriptedProcessLauncher launcher) throws Exception {
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

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await(EVENT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted at the race's start", e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new AssertionError("the race never started", e);
    }
  }

  private static void awaitEvents(
      ScriptedWorkerRuntime.Connection connection, EventCase... expected) {
    await()
        .atMost(EVENT_LIMIT)
        .untilAsserted(() -> assertThat(eventsOf(connection)).containsExactly(expected));
  }

  private static List<EventCase> eventsOf(ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(eventCase -> eventCase != EventCase.REGISTRATION)
        .toList();
  }

  private static EstablishWorkerSessionRequest lastEvent(
      ScriptedWorkerRuntime.Connection connection) {
    return connection.events().getLast();
  }

  private static List<Long> expectedLengths(Recording recording) {
    return Stream.concat(
            Stream.of((long) recording.initializationSegment().byteLength()),
            recording.segments().stream().map(SegmentSummary::byteLength))
        .toList();
  }

  // The recording with its first media data box grown past two upload data messages, which the
  // producer reads without looking inside.
  private static byte[] withLargeFirstMediaData(byte[] recording) {
    var grown = new ByteArrayOutputStream();
    var buffer = ByteBuffer.wrap(recording);
    var padded = false;
    while (buffer.hasRemaining()) {
      var size = buffer.getInt(buffer.position());
      var type = new String(recording, buffer.position() + 4, 4, StandardCharsets.US_ASCII);
      var box = new byte[size];
      buffer.get(box);
      if (padded || !type.equals("mdat")) {
        grown.writeBytes(box);
        continue;
      }

      grown.writeBytes(ByteBuffer.allocate(4).putInt(size + 3 * UPLOAD_MESSAGE_BYTES).array());
      grown.writeBytes(Arrays.copyOfRange(box, 4, size));
      grown.writeBytes(new byte[3 * UPLOAD_MESSAGE_BYTES]);
      padded = true;
    }

    return grown.toByteArray();
  }
}
