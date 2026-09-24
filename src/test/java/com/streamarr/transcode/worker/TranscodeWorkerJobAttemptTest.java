package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
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
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Job Attempt Tests")
class TranscodeWorkerJobAttemptTest {

  private static final String WHOLE_RUN = "01-encode-cfr.fmp4";
  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final int CHUNK = 64 * 1024;

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
    var recording = recording(WHOLE_RUN);
    var launcher = ScriptedProcessLauncher.writing(WHOLE_RUN);
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(launcher.hasLaunched(fromProto(job.getJobAttemptId()))).isTrue();
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(expectedNames(recording));
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
      assertThat(uploaded.toByteArray()).isEqualTo(bytesOf(WHOLE_RUN));
    }
  }

  @Test
  @DisplayName("Should upload a segment larger than a chunk in whole chunks when it is delivered")
  void shouldUploadASegmentLargerThanAChunkInWholeChunksWhenItIsDelivered() throws Exception {
    var output = withLargeFirstMediaData(bytesOf(WHOLE_RUN));
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(output).build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      awaitEvents(connection, EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_COMPLETED);
      var firstMediaSegment = connection.uploads().get(1);
      assertThat(firstMediaSegment.chunkLengths())
          .hasSizeGreaterThan(2)
          .last()
          .satisfies(length -> assertThat(length).isPositive().isLessThanOrEqualTo(CHUNK));
      assertThat(firstMediaSegment.chunkLengths().subList(0, 2)).containsOnly(CHUNK);
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
    var recording = recording(WHOLE_RUN);
    var job = variantJobBuilder().build();

    try (var worker = worker(ScriptedProcessLauncher.writing(WHOLE_RUN))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, job);

      // The metadata and one chunk of content for the initialization segment and each segment.
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

  @ParameterizedTest(name = "container value {0}")
  @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
  @DisplayName(
      "Should refuse the job as an invalid specification when it asks for another container")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItAsksForAnotherContainer(int container)
      throws Exception {
    var launcher = ScriptedProcessLauncher.writing(WHOLE_RUN);
    var job = variantJobBuilder();
    job.getDecisionBuilder().setContainerValue(container);

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());

      assertThat(eventsOf(connection)).containsExactly(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(lastEvent(connection).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  @Test
  @DisplayName("Should fail the attempt as a transcode failure when FFmpeg exits with an error")
  void shouldFailTheAttemptAsATranscodeFailureWhenFfmpegExitsWithAnError() throws Exception {
    var launcher =
        new ScriptedProcessLauncher(
            _ -> ScriptedProcess.builder().output(bytesOf(WHOLE_RUN)).exitCode(1).build());
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
  @DisplayName("Should report the stopped attempt only after FFmpeg has exited")
  void shouldReportTheStoppedAttemptOnlyAfterFfmpegHasExited() throws Exception {
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

      var stop = CompletableFuture.runAsync(() -> deliverStop(connection, job));
      await().atMost(EVENT_LIMIT).until(() -> process.stdinText().equals("q"));

      assertThat(eventsOf(connection)).containsExactly(EventCase.JOB_ATTEMPT_STARTED);
      process.exit();
      assertThat(stop).succeedsWithin(EVENT_LIMIT);
      assertThat(eventsOf(connection))
          .containsExactly(EventCase.JOB_ATTEMPT_STARTED, EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(process.wasDestroyedForcibly()).isFalse();
    }
  }

  @RepeatedTest(20)
  @DisplayName("Should settle the attempt once when a stop races FFmpeg's failure")
  void shouldSettleTheAttemptOnceWhenAStopRacesFfmpegsFailure() throws Exception {
    var initializationSegment =
        Arrays.copyOf(
            bytesOf(WHOLE_RUN), recording(WHOLE_RUN).initializationSegment().byteLength());
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(initializationSegment)
                    .exitCode(1)
                    .exitTiming(ExitTiming.AT_LAUNCH)
                    .build());
    var job = variantJobBuilder().build();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job);

      stopVariant(connection, job);

      assertThat(eventsOf(connection))
          .hasSize(2)
          .startsWith(EventCase.JOB_ATTEMPT_STARTED)
          .last()
          .isIn(EventCase.JOB_ATTEMPT_STOPPED, EventCase.JOB_ATTEMPT_FAILED);
    }
  }

  private TranscodeWorker worker(ScriptedProcessLauncher launcher) throws Exception {
    return workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build();
  }

  private static void deliverStop(ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    try {
      stopVariant(connection, job);
    } catch (Exception e) {
      throw new IllegalStateException(e);
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

  private static List<String> expectedNames(Recording recording) {
    return Stream.concat(
            Stream.of("init.mp4"),
            recording.segments().stream().map(segment -> "segment" + segment.number() + ".m4s"))
        .toList();
  }

  private static List<Long> expectedLengths(Recording recording) {
    return Stream.concat(
            Stream.of((long) recording.initializationSegment().byteLength()),
            recording.segments().stream().map(segment -> segment.byteLength()))
        .toList();
  }

  /**
   * The recording with its first media data box grown past two upload chunks, which the producer
   * reads without looking inside.
   */
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

      grown.writeBytes(ByteBuffer.allocate(4).putInt(size + 3 * CHUNK).array());
      grown.writeBytes(Arrays.copyOfRange(box, 4, size));
      grown.writeBytes(new byte[3 * CHUNK]);
      padded = true;
    }

    return grown.toByteArray();
  }
}
