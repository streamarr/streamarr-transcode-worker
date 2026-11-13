package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.uploadNames;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.stopVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.SteppedClock;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.Builder;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// The producers deliver their segments on their own threads, which offer no hook, so the tests wait
// for the worker's state with Awaitility.
@Tag("UnitTest")
@DisplayName("Transcode Worker Segment Upload Limit Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerSegmentUploadLimitTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  // The server's limit of upload calls from one worker at once.
  private static final int SERVER_UPLOAD_LIMIT = 8;
  private static final int OVERLAPPING_COPIES = SERVER_UPLOAD_LIMIT + 2;
  // Beside a 512 MiB reserve and the two slots' 512 MiB, 4096 MiB hold twelve copies of 256 MiB.
  private static final long TWELVE_COPY_HEAP_BYTES = 4096L * 1024 * 1024;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should keep at most the server's limit of uploads open and fail no attempt when more"
          + " segments than the limit are ready at once")
  void shouldKeepAtMostTheServersLimitOfUploadsOpenAndFailNoAttemptWhenMoreSegmentsAreReadyAtOnce()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(SERVER_UPLOAD_LIMIT);
    var jobs = copies(OVERLAPPING_COPIES);

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      jobs.forEach(job -> startVariant(connection, job));
      promptly()
          .until(
              () ->
                  connection.uploadsAwaitingAcknowledgement() == SERVER_UPLOAD_LIMIT
                      && worker.queuedSegmentUploads() == OVERLAPPING_COPIES - SERVER_UPLOAD_LIMIT);

      assertThat(connection.uploads()).hasSize(SERVER_UPLOAD_LIMIT);

      connection.releaseAcknowledgements();
      promptly()
          .until(() -> jobs.stream().allMatch(job -> terminalEventOf(connection, job).isPresent()));
      assertThat(jobs)
          .allSatisfy(
              job ->
                  assertThat(terminalEventOf(connection, job))
                      .contains(EventCase.JOB_ATTEMPT_COMPLETED));
      assertThat(connection.uploads())
          .hasSize(OVERLAPPING_COPIES * uploadNames(recording(ENCODED_RECORDING)).size());
      assertThat(connection.mostUploadsOpenAtOnce()).isEqualTo(SERVER_UPLOAD_LIMIT);
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should open an upload for every ready segment at once when the server states no limit")
  void shouldOpenAnUploadForEveryReadySegmentAtOnceWhenTheServerStatesNoLimit() throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(0);
    var jobs = copies(OVERLAPPING_COPIES);

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();

      jobs.forEach(job -> startVariant(connection, job));

      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == OVERLAPPING_COPIES);
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should open the queued uploads in the order their segments became ready when an upload"
          + " ends")
  void shouldOpenTheQueuedUploadsInTheOrderTheirSegmentsBecameReadyWhenAnUploadEnds()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var first = variantJobBuilder().build();
    var second = variantJobBuilder().build();
    var third = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, first);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, second);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      startVariant(connection, third);
      promptly().until(() -> worker.queuedSegmentUploads() == 2);

      connection.acknowledgeAwaitingUploads();
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      connection.acknowledgeAwaitingUploads();
      promptly().until(() -> connection.uploads().size() == 3);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);

      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getJobAttemptId())
          .containsExactly(
              first.getJobAttemptId(), second.getJobAttemptId(), third.getJobAttemptId());
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure without opening an upload when no upload"
          + " ends within the upload deadline")
  void shouldFailTheAttemptWithoutOpeningAnUploadWhenNoUploadEndsWithinTheUploadDeadline()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var ahead = variantJobBuilder().build();
    var queued = variantJobBuilder().build();
    // The server answers no upload, so each call ends at its deadline. The segment ahead opens its
    // call only when the holding call ends, so that call is still open when the queued segment has
    // waited one upload deadline.
    var configuration = configurationBuilder().uploadDeadline(Duration.ofSeconds(1)).build();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .configuration(configuration)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, ahead);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);

      startVariant(connection, queued);

      promptly().until(() -> terminalEventOf(connection, queued).isPresent());
      assertThat(terminalEventOf(connection, queued)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, queued))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(uploadsOf(connection, queued)).isEmpty();
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure without opening an upload when an upload"
          + " ends only after the queued segment's upload deadline")
  void
      shouldFailTheAttemptWithoutOpeningAnUploadWhenAnUploadEndsOnlyAfterTheQueuedSegmentsDeadline()
          throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var queued = variantJobBuilder().build();
    // The queued segment waits on the clock's reading of the upload deadline, so the clock can pass
    // that deadline while the segment still waits, and the end of the holding upload then wakes it.
    var clock = new SteppedClock();
    var configuration = configurationBuilder().uploadDeadline(Duration.ofMinutes(5)).build();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .configuration(configuration)
                .clock(clock)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, queued);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      clock.advance(Duration.ofMinutes(6));

      connection.acknowledgeAwaitingUploads();

      promptly().until(() -> terminalEventOf(connection, queued).isPresent());
      assertThat(terminalEventOf(connection, queued)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, queued))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(uploadsOf(connection, queued)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should give the upload of a segment that waited for a call the whole upload deadline when"
          + " the call opens")
  void shouldGiveTheUploadOfASegmentThatWaitedForACallTheWholeUploadDeadlineWhenTheCallOpens()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var queued = variantJobBuilder().build();
    // The queued segment's wait passes four of the five minutes on the clock, which counts the
    // wait, and leaves the call's deadline to count from the call's opening.
    var clock = new SteppedClock();
    var configuration = configurationBuilder().uploadDeadline(Duration.ofMinutes(5)).build();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .configuration(configuration)
                .clock(clock)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, queued);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      clock.advance(Duration.ofMinutes(4));

      connection.acknowledgeAwaitingUploads();

      promptly().until(() -> !uploadsOf(connection, queued).isEmpty());
      assertThat(firstUploadOf(connection, queued).deadline())
          .hasValueSatisfying(
              deadline ->
                  assertThat(deadline.timeRemaining(TimeUnit.MINUTES)).isGreaterThanOrEqualTo(4));
    }
  }

  @Test
  @DisplayName(
      "Should stop the attempt without opening an upload when it stops while its segment waits"
          + " for an upload")
  void shouldStopTheAttemptWithoutOpeningAnUploadWhenItStopsWhileItsSegmentWaitsForAnUpload()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var queued = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploads().size() == 1);
      startVariant(connection, queued);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);

      stopVariant(connection, queued);

      // The default upload deadline of 60 s outlasts this wait, so only the stop ends it.
      promptly().until(() -> terminalEventOf(connection, queued).isPresent());
      assertThat(terminalEventOf(connection, queued)).contains(EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(worker.queuedSegmentUploads()).isZero();
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasCancelled()).isFalse());
    }
  }

  @Test
  @DisplayName(
      "Should end the wait of a queued segment without opening an upload when the worker session"
          + " ends")
  void shouldEndTheWaitOfAQueuedSegmentWithoutOpeningAnUploadWhenTheWorkerSessionEnds()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var queued = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdUploadReadiness();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploads().size() == 1);
      startVariant(connection, queued);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);

      connection.fail("the server went away");

      // The default upload deadline of 60 s outlasts this wait, so only the session's end
      // ends it.
      promptly().until(() -> worker.queuedSegmentUploads() == 0);
      assertThat(connection.uploads()).hasSize(1);
    }
  }

  @Test
  @DisplayName(
      "Should open the refused segment's upload again until the server accepts it, and fail no"
          + " attempt, when the server refuses it at the worker's segment upload limit")
  void shouldOpenTheRefusedSegmentsUploadAgainUntilAcceptedWhenServerRefusesItAtTheWorkersLimit()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseUploadsAtTheWorkersLimit();

      startVariant(connection, job);

      promptly()
          .until(() -> refusedUploadCount(connection) >= 2 && worker.queuedSegmentUploads() == 1);
      assertThat(acceptedSegmentNamesOf(connection, job)).isEmpty();

      connection.acceptUploads();
      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      assertThat(terminalEventOf(connection, job)).contains(EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(acceptedSegmentNamesOf(connection, job))
          .containsExactlyElementsOf(uploadNames(recording(ENCODED_RECORDING)));
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should keep the refused segment ahead of a segment that was already waiting when the server"
          + " refuses it at the worker's segment upload limit")
  void shouldKeepTheRefusedSegmentAheadOfAWaitingSegmentWhenServerRefusesItAtTheWorkersLimit()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var holding = variantJobBuilder().build();
    var refused = variantJobBuilder().build();
    var waiting = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, refused);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 2);
      startVariant(connection, waiting);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      connection.refuseUploadsAtTheWorkersLimit();

      firstUploadOf(connection, refused).refuseAtTheWorkersLimit();

      // The worker counts one open upload of its two, so only the refused segment's place at the
      // front of the queue holds the waiting segment back.
      promptly().until(() -> refusedUploadCount(connection) >= 3);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getJobAttemptId())
          .doesNotContain(waiting.getJobAttemptId());

      connection.acceptUploads();
      connection.releaseAcknowledgements();
      var jobs = List.of(holding, refused, waiting);
      promptly()
          .until(() -> jobs.stream().allMatch(job -> terminalEventOf(connection, job).isPresent()));
      assertThat(jobs)
          .allSatisfy(
              job -> {
                assertThat(terminalEventOf(connection, job))
                    .contains(EventCase.JOB_ATTEMPT_COMPLETED);
                assertThat(acceptedSegmentNamesOf(connection, job))
                    .containsExactlyElementsOf(uploadNames(recording(ENCODED_RECORDING)));
              });
    }
  }

  @Test
  @DisplayName(
      "Should open the refused segment's upload again without waiting out the delay between its"
          + " uploads when another of the worker's uploads ends")
  void shouldOpenTheRefusedSegmentsUploadAgainWithoutWaitingOutItsDelayWhenAnotherUploadEnds()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var holding = variantJobBuilder().build();
    var refused = variantJobBuilder().build();
    // The clock stands still, so the delay after the refusal never passes, and only the end of
    // the holding upload can open the refused segment's upload again.
    var clock = new SteppedClock();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .clock(clock)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, refused);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 2);
      firstUploadOf(connection, refused).refuseAtTheWorkersLimit();
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      assertThat(uploadsOf(connection, refused)).hasSize(1);

      connection.acknowledgeAwaitingUploads();

      promptly().until(() -> uploadsOf(connection, refused).size() == 2);
      assertThat(uploadsOf(connection, refused).getLast().wasRefused()).isFalse();
      assertThat(firstUploadOf(connection, holding).wasRefused()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should open the uploads of two refused segments again without waiting out their delays when"
          + " two of the worker's other uploads end together")
  void shouldOpenTwoRefusedSegmentsUploadsAgainWithoutWaitingOutTheirDelaysWhenTwoUploadsEnd()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(4);
    var holding = List.of(variantJobBuilder().build(), variantJobBuilder().build());
    var refused = List.of(variantJobBuilder().build(), variantJobBuilder().build());
    // The clock stands still, so only the ends of the two holding uploads can open the refused
    // segments' uploads again, one for each end.
    var clock = new SteppedClock();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .clock(clock)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      holding.forEach(job -> startVariant(connection, job));
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 2);
      refused.forEach(job -> startVariant(connection, job));
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 4);
      refused.forEach(job -> firstUploadOf(connection, job).refuseAtTheWorkersLimit());
      promptly().until(() -> worker.queuedSegmentUploads() == 2);

      connection.acknowledgeAwaitingUploads();

      promptly()
          .until(() -> refused.stream().allMatch(job -> uploadsOf(connection, job).size() == 2));
      assertThat(refused)
          .allSatisfy(
              job -> assertThat(uploadsOf(connection, job).getLast().wasRefused()).isFalse());
    }
  }

  @Test
  @DisplayName(
      "Should upload a refused segment once the server accepts it when the other refused upload is"
          + " stopped")
  void shouldUploadARefusedSegmentOnceTheServerAcceptsItWhenTheOtherRefusedUploadIsStopped()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var first = variantJobBuilder().build();
    var stopped = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, first);
      startVariant(connection, stopped);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 2);
      connection.refuseUploadsAtTheWorkersLimit();
      firstUploadOf(connection, first).refuseAtTheWorkersLimit();
      firstUploadOf(connection, stopped).refuseAtTheWorkersLimit();

      // No upload of the worker stays open, so none ends to free a place for the other.
      stopVariant(connection, stopped);

      promptly().until(() -> terminalEventOf(connection, stopped).isPresent());
      connection.acceptUploads();
      connection.releaseAcknowledgements();
      promptly().until(() -> terminalEventOf(connection, first).isPresent());
      assertThat(terminalEventOf(connection, first)).contains(EventCase.JOB_ATTEMPT_COMPLETED);
      assertThat(acceptedSegmentNamesOf(connection, first))
          .containsExactlyElementsOf(uploadNames(recording(ENCODED_RECORDING)));
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure when the server refuses the segment at the"
          + " worker's segment upload limit for an upload deadline from its first wait")
  void shouldFailTheAttemptWhenServerRefusesTheSegmentAtTheLimitForTheUploadDeadline()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker = workerWithADeadlineBeyondTheLongestDelayBetweenUploads(runtime)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseUploadsAtTheWorkersLimit();

      startVariant(connection, job);

      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      assertThat(terminalEventOf(connection, job)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, job))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure when the server keeps refusing a queued"
          + " segment past the upload deadline counted from the segment's first wait")
  void shouldFailTheAttemptWhenTheServerKeepsRefusingAQueuedSegmentPastItsFirstWaitsDeadline()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(1);
    var holding = variantJobBuilder().build();
    var queued = variantJobBuilder().build();
    // The queued segment waits four of the five minutes on the clock before its first refusal, and
    // the clock then passes the five minutes from its first wait while refusals keep it waiting.
    var clock = new SteppedClock();
    var configuration = configurationBuilder().uploadDeadline(Duration.ofMinutes(5)).build();

    try (var worker =
        worker(
            WorkerSetup.builder()
                .runtime(runtime)
                .launcher(writingTheRecording())
                .configuration(configuration)
                .clock(clock)
                .build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdAcknowledgements();
      startVariant(connection, holding);
      promptly().until(() -> connection.uploadsAwaitingAcknowledgement() == 1);
      startVariant(connection, queued);
      promptly().until(() -> worker.queuedSegmentUploads() == 1);
      clock.advance(Duration.ofMinutes(4));
      connection.refuseUploadsAtTheWorkersLimit();
      connection.acknowledgeAwaitingUploads();
      promptly().until(() -> !uploadsOf(connection, queued).isEmpty());

      clock.advance(Duration.ofMinutes(2));

      promptly().until(() -> terminalEventOf(connection, queued).isPresent());
      assertThat(terminalEventOf(connection, queued)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, queued))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(uploadsOf(connection, queued))
          .allSatisfy(upload -> assertThat(upload.wasRefused()).isTrue());
    }
  }

  @Test
  @DisplayName(
      "Should wait between the uploads of a refused segment when the server keeps refusing it at"
          + " the worker's segment upload limit")
  void shouldWaitBetweenTheUploadsOfARefusedSegmentWhenServerKeepsRefusingItAtTheWorkersLimit()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker = workerWithADeadlineBeyondTheLongestDelayBetweenUploads(runtime)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseUploadsAtTheWorkersLimit();

      startVariant(connection, job);

      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      // Opening the call again at once would open thousands within the upload deadline.
      assertThat(connection.uploads()).hasSizeBetween(2, 10);
    }
  }

  // Only a deadline counted from a segment's first wait can end the segment's wait, because each
  // delay between its uploads is shorter than the upload deadline.
  private TranscodeWorker workerWithADeadlineBeyondTheLongestDelayBetweenUploads(
      ScriptedWorkerRuntime runtime) {
    var configuration = configurationBuilder().uploadDeadline(Duration.ofSeconds(2)).build();
    return worker(
        WorkerSetup.builder()
            .runtime(runtime)
            .launcher(writingTheRecording())
            .configuration(configuration)
            .build());
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure when the server refuses its upload at a"
          + " fleet-wide limit")
  void shouldFailTheAttemptAsATranscodeFailureWhenTheServerRefusesItsUploadAtAFleetWideLimit()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseNextUploadAtAFleetWideLimit();

      startVariant(connection, job);

      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      assertThat(terminalEventOf(connection, job)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, job))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploads())
          .singleElement()
          .satisfies(upload -> assertThat(upload.wasRefused()).isTrue());
      assertThat(worker.queuedSegmentUploads()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a transcode failure when the server marks a refusal at the"
          + " worker's segment upload limit although it stated no limit")
  void shouldFailTheAttemptWhenTheServerMarksARefusalAtTheWorkersLimitAlthoughItStatedNoLimit()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(0);
    var job = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseNextUploadAtTheWorkersLimit();

      startVariant(connection, job);

      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      assertThat(terminalEventOf(connection, job)).contains(EventCase.JOB_ATTEMPT_FAILED);
      assertThat(failureOf(connection, job))
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploads()).singleElement();
    }
  }

  @Test
  @DisplayName(
      "Should stop the attempt without opening another upload when it stops while its refused"
          + " segment waits at the front of the queue")
  void shouldStopTheAttemptWithoutOpeningAnotherUploadWhenItStopsWhileItsRefusedSegmentWaits()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseUploadsAtTheWorkersLimit();
      startVariant(connection, job);
      promptly()
          .until(() -> refusedUploadCount(connection) >= 2 && worker.queuedSegmentUploads() == 1);

      stopVariant(connection, job);

      // The default upload deadline of 60 s outlasts this wait, so only the stop ends it.
      promptly().until(() -> terminalEventOf(connection, job).isPresent());
      assertThat(terminalEventOf(connection, job)).contains(EventCase.JOB_ATTEMPT_STOPPED);
      assertThat(worker.queuedSegmentUploads()).isZero();
      assertThat(connection.uploads())
          .allSatisfy(upload -> assertThat(upload.wasRefused()).isTrue());
    }
  }

  @Test
  @DisplayName(
      "Should end the wait of a refused segment without opening another upload when the worker"
          + " session ends")
  void shouldEndTheWaitOfARefusedSegmentWithoutOpeningAnotherUploadWhenTheWorkerSessionEnds()
      throws Exception {
    var runtime = ScriptedWorkerRuntime.withSegmentUploadLimit(2);
    var job = variantJobBuilder().build();

    try (var worker =
        worker(WorkerSetup.builder().runtime(runtime).launcher(writingTheRecording()).build())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseUploadsAtTheWorkersLimit();
      startVariant(connection, job);
      promptly()
          .until(() -> refusedUploadCount(connection) >= 2 && worker.queuedSegmentUploads() == 1);

      connection.fail("the server went away");

      // The default upload deadline of 60 s outlasts this wait, so only the session's end
      // ends it.
      promptly().until(() -> worker.queuedSegmentUploads() == 0);
      assertThat(connection.uploads())
          .allSatisfy(upload -> assertThat(upload.wasRefused()).isTrue());
    }
  }

  @Builder
  private record WorkerSetup(
      ScriptedWorkerRuntime runtime,
      ScriptedProcessLauncher launcher,
      TranscodeWorkerConfiguration configuration,
      MonotonicClock clock) {}

  private TranscodeWorker worker(WorkerSetup setup) {
    var configuration = setup.configuration();
    if (configuration == null) {
      configuration = configurationBuilder().build();
    }

    var clock = setup.clock();
    if (clock == null) {
      clock = MonotonicClock.system();
    }

    return workerBuilder(tempDir)
        .runtime(setup.runtime())
        .engine(engine(setup.launcher()))
        .configuration(configuration)
        .maximumHeapBytes(TWELVE_COPY_HEAP_BYTES)
        .clock(clock)
        .build();
  }

  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configurationBuilder() {
    return workerConfigurationBuilder()
        .availableSlots(2)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }

  // FFmpeg writes the whole recording at once, so each attempt has its next segment ready as soon
  // as its previous upload ends.
  private static ScriptedProcessLauncher writingTheRecording() {
    return ScriptedProcessLauncher.writing(ENCODED_RECORDING);
  }

  private static List<VariantJob> copies(int count) {
    return IntStream.range(0, count).mapToObj(_ -> variantJobBuilder().build()).toList();
  }

  // How the worker reported the end of the job's attempt: completed, failed or stopped.
  private static Optional<EventCase> terminalEventOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.events().stream()
        .filter(event -> endedAttemptOf(event).equals(Optional.of(job.getJobAttemptId())))
        .map(EstablishWorkerSessionRequest::getEventCase)
        .findFirst();
  }

  private static Optional<Uuid> endedAttemptOf(EstablishWorkerSessionRequest event) {
    return switch (event.getEventCase()) {
      case JOB_ATTEMPT_COMPLETED -> Optional.of(event.getJobAttemptCompleted().getJobAttemptId());
      case JOB_ATTEMPT_FAILED -> Optional.of(event.getJobAttemptFailed().getJobAttemptId());
      case JOB_ATTEMPT_STOPPED -> Optional.of(event.getJobAttemptStopped().getJobAttemptId());
      default -> Optional.empty();
    };
  }

  private static long refusedUploadCount(ScriptedWorkerRuntime.Connection connection) {
    return connection.uploads().stream()
        .filter(ScriptedWorkerRuntime.UploadCall::wasRefused)
        .count();
  }

  private static ScriptedWorkerRuntime.UploadCall firstUploadOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return uploadsOf(connection, job).getFirst();
  }

  // The job's uploads, in the order the worker opened them.
  private static List<ScriptedWorkerRuntime.UploadCall> uploadsOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.uploads().stream()
        .filter(upload -> upload.metadata().getJobAttemptId().equals(job.getJobAttemptId()))
        .toList();
  }

  // The names of the job's segments that the server accepted, in the order the worker opened them.
  private static List<String> acceptedSegmentNamesOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.uploads().stream()
        .filter(upload -> !upload.wasRefused())
        .map(ScriptedWorkerRuntime.UploadCall::metadata)
        .filter(metadata -> metadata.getJobAttemptId().equals(job.getJobAttemptId()))
        .map(SegmentUploadMetadata::getSegmentName)
        .toList();
  }

  private static JobAttemptFailure failureOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.events().stream()
        .filter(EstablishWorkerSessionRequest::hasJobAttemptFailed)
        .map(EstablishWorkerSessionRequest::getJobAttemptFailed)
        .filter(failed -> failed.getJobAttemptId().equals(job.getJobAttemptId()))
        .findFirst()
        .orElseThrow()
        .getFailure();
  }

  private static ConditionFactory promptly() {
    return await().atMost(EVENT_LIMIT).pollInterval(Duration.ofMillis(5));
  }
}
