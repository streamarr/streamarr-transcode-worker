package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.AudioDecision;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.StopVariantCommand;
import build.buf.gen.streamarr.transcode.v1.SubtitleDecision;
import build.buf.gen.streamarr.transcode.v1.SubtitleMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeDecision;
import build.buf.gen.streamarr.transcode.v1.TranscodeExecution;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VariantSpec;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.transcode.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.fakes.FakeSegmentProducingFfmpegProcessManager;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Control Plane Integration Tests")
class TranscodeWorkerControlPlaneIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should fail startup when the control stream closes before registration is accepted")
  void shouldFailStartupWhenControlStreamClosesBeforeRegistrationIsAccepted() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var service = ControllableWorkerService.closingBeforeRegistration();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot)) {
      server.start();

      assertThatThrownBy(() -> worker.start("localhost", server.port()))
          .isInstanceOf(ExecutionException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Worker session closed");
    }
  }

  @Test
  @DisplayName("Should warn when the control plane sends an unknown command")
  void shouldWarnWhenControlPlaneSendsUnknownCommand() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var service = new ControllableWorkerService();
    var appender = attachWorkerAppender();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      service.send(EstablishWorkerSessionResponse.getDefaultInstance());

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(appender.list)
                      .anySatisfy(
                          event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains("COMMAND_NOT_SET");
                          }));
    } finally {
      detachWorkerAppender(appender);
    }
  }

  @Test
  @DisplayName(
      "Should reject a start command addressed to another worker when handling a start command")
  void shouldRejectStartCommandAddressedToAnotherWorkerWhenHandlingStartCommand() throws Exception {
    assertRejectsStartCommand(
        target -> target.toBuilder().setWorkerId(toProto(UUID.randomUUID())).build());
  }

  @Test
  @DisplayName(
      "Should reject a start command addressed to an earlier worker boot when handling a start command")
  void shouldRejectStartCommandAddressedToEarlierWorkerBootWhenHandlingStartCommand()
      throws Exception {
    assertRejectsStartCommand(
        target -> target.toBuilder().setBootId(toProto(UUID.randomUUID())).build());
  }

  @Test
  @DisplayName(
      "Should ignore a stop command addressed to another worker when handling a stop command")
  void shouldIgnoreStopCommandAddressedToAnotherWorkerWhenHandlingStopCommand() throws Exception {
    assertIgnoresStopCommand(
        target -> target.toBuilder().setWorkerId(toProto(UUID.randomUUID())).build());
  }

  @Test
  @DisplayName(
      "Should ignore a stop command addressed to an earlier worker boot when handling a stop command")
  void shouldIgnoreStopCommandAddressedToEarlierWorkerBootWhenHandlingStopCommand()
      throws Exception {
    assertIgnoresStopCommand(
        target -> target.toBuilder().setBootId(toProto(UUID.randomUUID())).build());
  }

  @Test
  @DisplayName("Should reject startup when the worker already has an active session")
  void shouldRejectStartupWhenWorkerAlreadyHasAnActiveSession() throws Exception {
    var service = new ControllableWorkerService();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot())) {
      server.start();
      var port = server.port();
      worker.start("localhost", port);

      assertThatThrownBy(() -> worker.start("localhost", port))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Transcode worker is already started");
    }
  }

  @Test
  @DisplayName("Should stop the active media process when the worker closes")
  void shouldStopActiveMediaProcessWhenWorkerCloses() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FakeFfmpegProcessManager();
    var job = variantJob();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);

      worker.close();

      assertThat(processes.isRunning(fromProto(job.getStreamSessionId()))).isFalse();
    }
  }

  @Test
  @DisplayName("Should preserve the other variant when the control plane stops one attempt")
  void shouldPreserveOtherVariantWhenControlPlaneStopsOneAttempt() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FakeFfmpegProcessManager();
    var stopped = variantJob();
    var surviving =
        stopped.toBuilder()
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setVariant(stopped.getVariant().toBuilder().setVariantLabel("1080p"))
            .build();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), stopped);
      service.awaitStarted(stopped);
      service.sendStart(service.registeredWorker(), surviving);
      service.awaitStarted(surviving);

      service.sendStop(service.registeredWorker(), stopped);
      service.awaitStopped(stopped);

      assertThat(processes.isRunning(fromProto(stopped.getStreamSessionId()), "720p")).isFalse();
      assertThat(processes.isRunning(fromProto(surviving.getStreamSessionId()), "1080p")).isTrue();
    }
  }

  @Test
  @DisplayName("Should preserve command order when replacing the same variant repeatedly")
  void shouldPreserveCommandOrderWhenReplacingSameVariantRepeatedly() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FakeFfmpegProcessManager();
    var current = variantJob();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), current);
      service.awaitStarted(current);

      var replacements = new ArrayList<VariantJob>();
      for (var index = 0; index < 100; index++) {
        service.sendStop(service.registeredWorker(), current);
        var next = current.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build();
        service.sendStart(service.registeredWorker(), next);
        replacements.add(current);
        replacements.add(next);
        current = next;
      }
      for (var index = 0; index < replacements.size(); index += 2) {
        service.awaitStopped(replacements.get(index));
        service.awaitStarted(replacements.get(index + 1));
      }

      assertThat(processes.isRunning(fromProto(current.getStreamSessionId()), "720p")).isTrue();
    }
  }

  @Test
  @DisplayName("Should upload the requested sequence when resuming a job mid-timeline")
  void shouldUploadRequestedSequenceWhenResumingJobMidTimeline() throws Exception {
    var service = new ControllableWorkerService();
    var bytes = "resumed segment".getBytes(StandardCharsets.UTF_8);
    var processes = new EndingProcessManager(Map.of("segment7.ts", bytes));
    var initial = variantJob();
    var resumed =
        initial.toBuilder()
            .setExecution(initial.getExecution().toBuilder().setStartSequenceNumber(7))
            .build();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), resumed);
      service.awaitStarted(resumed);
      service.awaitCompleted(resumed);

      assertThat(service.uploads)
          .singleElement()
          .satisfies(
              upload -> {
                assertThat(upload.metadata().getSegmentName()).isEqualTo("segment7.ts");
                assertThat(upload.metadata().getJobAttemptId())
                    .isEqualTo(resumed.getJobAttemptId());
                assertThat(upload.bytes()).isEqualTo(bytes);
              });
    }
  }

  @Test
  @DisplayName("Should fail the attempt when the media process exits without a segment")
  void shouldFailAttemptWhenMediaProcessExitsWithoutASegment() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new EndingProcessManager(Map.of());
    var job = variantJob();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      var failure = service.awaitFailure();

      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(service.uploads).isEmpty();
    }
  }

  @Test
  @DisplayName("Should accept the next valid job when a media process fails to start")
  void shouldAcceptNextValidJobWhenMediaProcessFailsToStart() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FirstStartFailingProcessManager();
    var failed = variantJob();
    var next = variantJob();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), failed);
      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(failed.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);

      service.sendStart(service.registeredWorker(), next);
      service.awaitStarted(next);

      assertThat(processes.isRunning(fromProto(next.getStreamSessionId()))).isTrue();
      assertThat(processes.isRunning(fromProto(failed.getStreamSessionId()))).isFalse();
    }
  }

  @Test
  @DisplayName("Should accept a valid job when earlier decisions contain invalid enum values")
  void shouldAcceptValidJobWhenEarlierDecisionsContainInvalidEnumValues() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FakeFfmpegProcessManager();
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      for (var malformed : malformedVariantJobs()) {
        service.sendStart(service.registeredWorker(), malformed);
        assertThat(service.awaitFailure().getJobAttemptId()).isEqualTo(malformed.getJobAttemptId());
        assertThat(processes.getStarted())
            .doesNotContain(fromProto(malformed.getStreamSessionId()));
      }

      var valid = variantJob();
      service.sendStart(service.registeredWorker(), valid);
      service.awaitStarted(valid);

      assertThat(processes.isRunning(fromProto(valid.getStreamSessionId()))).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should log abandoned attempts and stop their processes when the control plane fails")
  void shouldLogAbandonedAttemptsAndStopTheirProcessesWhenControlPlaneFails() throws Exception {
    var service = new ControllableWorkerService();
    var processes = new FakeFfmpegProcessManager();
    var job = variantJob();
    try (var capture = new WorkerLogCapture("abandoned job attempt");
        var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);

      service.failSession();

      assertThatThrownBy(worker::awaitDisconnection)
          .isInstanceOf(WorkerJobException.class)
          .hasMessage("Worker session failed")
          .hasRootCauseInstanceOf(StatusRuntimeException.class);
      assertThat(processes.isRunning(fromProto(job.getStreamSessionId()))).isFalse();
      assertThat(capture.event.get(5, TimeUnit.SECONDS).getFormattedMessage())
          .contains(fromProto(job.getJobAttemptId()).toString());
    }
  }

  @Test
  @DisplayName("Should log the unreported failure when the worker closes during an upload")
  void shouldLogUnreportedFailureWhenWorkerClosesDuringUpload() throws Exception {
    var service = new ControllableWorkerService();
    service.acknowledgeUploads = false;
    var processes =
        new FakeSegmentProducingFfmpegProcessManager(
            "segment0.ts", "pending segment".getBytes(StandardCharsets.UTF_8));
    var job = variantJob();
    try (var capture = new WorkerLogCapture("Could not report job attempt failure");
        var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      assertThat(service.uploadReceived.await(5, TimeUnit.SECONDS)).isTrue();

      worker.close();

      assertThat(capture.event.get(5, TimeUnit.SECONDS).getFormattedMessage())
          .contains(fromProto(job.getStreamSessionId()).toString());
      assertThat(processes.isRunning(fromProto(job.getStreamSessionId()))).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should stop the newly started process before deleting its output when the control plane disconnects during startup")
  void shouldStopNewlyStartedProcessBeforeDeletingOutputWhenControlPlaneDisconnectsDuringStartup()
      throws Exception {
    var service = new ControllableWorkerService();
    var processes = new DisconnectingProcessManager(service);
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), variantJob());

      assertThat(processes.outputExistedAtTermination.get(5, TimeUnit.SECONDS))
          .as("the media process must stop before its working directory is removed")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should remove the uploaded segment only when the control plane acknowledges its bytes")
  void shouldRemoveUploadedSegmentOnlyWhenControlPlaneAcknowledgesItsBytes() throws Exception {
    var service = new ControllableWorkerService();
    service.acknowledgeUploads = false;
    var bytes = "uploaded segment".getBytes(StandardCharsets.UTF_8);
    var processes = new FakeSegmentProducingFfmpegProcessManager("segment0.ts", bytes);
    var job = variantJob();
    var output =
        tempDir
            .resolve("segments")
            .resolve(fromProto(job.getJobAttemptId()).toString())
            .resolve("segment0.ts");
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      assertThat(service.uploadReceived.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(output).hasBinaryContent(bytes);
      assertThat(service.uploads)
          .singleElement()
          .satisfies(upload -> assertThat(upload.bytes()).isEqualTo(bytes));

      service.acknowledgePendingUpload();

      await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(output).doesNotExist());
    }
  }

  @Test
  @DisplayName(
      "Should finish and remove attempt output when every produced segment is acknowledged")
  void shouldFinishAndRemoveAttemptOutputWhenEveryProducedSegmentIsAcknowledged() throws Exception {
    var service = new ControllableWorkerService();
    var first = "first segment".getBytes(StandardCharsets.UTF_8);
    var second = "second segment".getBytes(StandardCharsets.UTF_8);
    var processes = new EndingProcessManager(Map.of("segment0.ts", first, "segment1.ts", second));
    var job = variantJob();
    var output = tempDir.resolve("segments").resolve(fromProto(job.getJobAttemptId()).toString());
    try (var server = new TestServer(service);
        var worker = worker(preparedMediaRoot(), processes)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      service.awaitCompleted(job);

      assertThat(service.uploads)
          .satisfiesExactly(
              upload -> {
                assertThat(upload.metadata().getSegmentName()).isEqualTo("segment0.ts");
                assertThat(upload.bytes()).isEqualTo(first);
              },
              upload -> {
                assertThat(upload.metadata().getSegmentName()).isEqualTo("segment1.ts");
                assertThat(upload.bytes()).isEqualTo(second);
              });
      await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(output).doesNotExist());
    }
  }

  private Path preparedMediaRoot() throws IOException {
    var root = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(root.resolve("movie.mkv"), "test media");
    return root;
  }

  private void assertRejectsStartCommand(UnaryOperator<WorkerIdentity> changeTarget)
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = new ControllableWorkerService();
    var processManager = new FakeFfmpegProcessManager();
    var job = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot, processManager)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(changeTarget.apply(service.registeredWorker()), job);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(processManager.getStarted()).isEmpty();
    }
  }

  private void assertIgnoresStopCommand(UnaryOperator<WorkerIdentity> changeTarget)
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = new ControllableWorkerService();
    var processManager = new FakeFfmpegProcessManager();
    var protectedJob = variantJob();
    var orderingBarrierJob = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot, processManager)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), protectedJob);
      service.awaitStarted(protectedJob);

      service.sendStop(changeTarget.apply(service.registeredWorker()), protectedJob);
      service.sendStart(service.registeredWorker(), orderingBarrierJob);
      service.awaitStarted(orderingBarrierJob);

      assertThat(
              processManager.isRunning(
                  fromProto(protectedJob.getStreamSessionId()),
                  protectedJob.getVariant().getVariantLabel()))
          .as("a command for another worker must not stop this worker's active attempt")
          .isTrue();
    }
  }

  private TranscodeWorker worker(Path mediaRoot) throws Exception {
    return worker(mediaRoot, new FakeFfmpegProcessManager());
  }

  private TranscodeWorker worker(Path mediaRoot, FakeFfmpegProcessManager processManager)
      throws Exception {
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, remuxEngine(processManager));
  }

  private static VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                .setVideoCodecFamily("h264")
                .setAudio(
                    AudioDecision.newBuilder()
                        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                        .setCodec("aac")
                        .setChannels(2)
                        .setBitrateBitsPerSecond(128_000))
                .setSubtitle(
                    SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                .setContainer(ContainerFormat.CONTAINER_FORMAT_MPEG_TS)
                .setAlignKeyframesToSegments(true))
        .setVariant(
            VariantSpec.newBuilder()
                .setVariantLabel("720p")
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(5_000_000))
        .setExecution(
            TranscodeExecution.newBuilder().setTargetSegmentDurationSeconds(6).setFramerate(23.976))
        .build();
  }

  private List<VariantJob> malformedVariantJobs() {
    var unspecifiedMode = variantJob();
    var unknownMode = variantJob();
    var unspecifiedAudio = variantJob();
    var unknownAudio = variantJob();
    var unspecifiedSubtitle = variantJob();
    var unknownSubtitle = variantJob();
    var unspecifiedContainer = variantJob();
    var unknownContainer = variantJob();
    return List.of(
        unspecifiedMode.toBuilder()
            .setDecision(
                unspecifiedMode.getDecision().toBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_UNSPECIFIED))
            .build(),
        unknownMode.toBuilder()
            .setDecision(unknownMode.getDecision().toBuilder().setModeValue(Integer.MAX_VALUE))
            .build(),
        unspecifiedAudio.toBuilder()
            .setDecision(
                unspecifiedAudio.getDecision().toBuilder()
                    .setAudio(
                        unspecifiedAudio.getDecision().getAudio().toBuilder()
                            .setMode(AudioMode.AUDIO_MODE_UNSPECIFIED)))
            .build(),
        unknownAudio.toBuilder()
            .setDecision(
                unknownAudio.getDecision().toBuilder()
                    .setAudio(
                        unknownAudio.getDecision().getAudio().toBuilder()
                            .setModeValue(Integer.MAX_VALUE)))
            .build(),
        unspecifiedSubtitle.toBuilder()
            .setDecision(
                unspecifiedSubtitle.getDecision().toBuilder()
                    .setSubtitle(
                        unspecifiedSubtitle.getDecision().getSubtitle().toBuilder()
                            .setMode(SubtitleMode.SUBTITLE_MODE_UNSPECIFIED)))
            .build(),
        unknownSubtitle.toBuilder()
            .setDecision(
                unknownSubtitle.getDecision().toBuilder()
                    .setSubtitle(
                        unknownSubtitle.getDecision().getSubtitle().toBuilder()
                            .setModeValue(Integer.MAX_VALUE)))
            .build(),
        unspecifiedContainer.toBuilder()
            .setDecision(
                unspecifiedContainer.getDecision().toBuilder()
                    .setContainer(ContainerFormat.CONTAINER_FORMAT_UNSPECIFIED))
            .build(),
        unknownContainer.toBuilder()
            .setDecision(
                unknownContainer.getDecision().toBuilder().setContainerValue(Integer.MAX_VALUE))
            .build());
  }

  private static ListAppender<ILoggingEvent> attachWorkerAppender() {
    var logger = (Logger) LoggerFactory.getLogger(TranscodeWorker.class);
    logger.setLevel(Level.WARN);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachWorkerAppender(ListAppender<ILoggingEvent> appender) {
    var logger = (Logger) LoggerFactory.getLogger(TranscodeWorker.class);
    logger.detachAppender(appender);
    logger.setLevel(null);
  }

  private static final class ControllableWorkerService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final boolean closeBeforeRegistration;
    private final CountDownLatch registered = new CountDownLatch(1);
    private final AtomicReference<WorkerIdentity> worker = new AtomicReference<>();
    private final BlockingQueue<EstablishWorkerSessionRequest> events = new LinkedBlockingQueue<>();
    private StreamObserver<EstablishWorkerSessionResponse> responses;
    private final ConcurrentLinkedQueue<UploadedSegment> uploads = new ConcurrentLinkedQueue<>();
    private final CountDownLatch uploadReceived = new CountDownLatch(1);
    private boolean acknowledgeUploads = true;
    private StreamObserver<UploadSegmentResponse> pendingUpload;
    private int pendingLength;

    private void acknowledgePendingUpload() {
      pendingUpload.onNext(
          UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(pendingLength).build());
      pendingUpload.onCompleted();
    }

    private ControllableWorkerService() {
      this(false);
    }

    private ControllableWorkerService(boolean closeBeforeRegistration) {
      this.closeBeforeRegistration = closeBeforeRegistration;
    }

    private static ControllableWorkerService closingBeforeRegistration() {
      return new ControllableWorkerService(true);
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest request) {
          if (!request.hasRegistration()) {
            events.add(request);
            return;
          }
          if (closeBeforeRegistration) {
            responseObserver.onCompleted();
            return;
          }
          worker.set(request.getRegistration().getWorker());
          responseObserver.onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
          registered.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
          // The control-plane assertions observe queued events instead.
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<UploadSegmentRequest> uploadSegment(
        StreamObserver<UploadSegmentResponse> responseObserver) {
      return new StreamObserver<>() {
        private SegmentUploadMetadata metadata;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void onNext(UploadSegmentRequest request) {
          if (request.hasMetadata()) {
            metadata = request.getMetadata();
          }
          if (request.hasData()) {
            bytes.writeBytes(request.getData().toByteArray());
          }
        }

        @Override
        public void onError(Throwable failure) {
          // Failed uploads are not recorded or acknowledged.
        }

        @Override
        public void onCompleted() {
          uploads.add(new UploadedSegment(metadata, bytes.toByteArray()));
          pendingUpload = responseObserver;
          pendingLength = bytes.size();
          uploadReceived.countDown();
          if (acknowledgeUploads) {
            responseObserver.onNext(
                UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(bytes.size()).build());
            responseObserver.onCompleted();
          }
        }
      };
    }

    private void send(EstablishWorkerSessionResponse response) throws InterruptedException {
      assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
      responses.onNext(response);
    }

    private WorkerIdentity registeredWorker() {
      return worker.get();
    }

    private void sendStart(WorkerIdentity target, VariantJob job) throws InterruptedException {
      send(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(StartVariantCommand.newBuilder().setTarget(target).setJob(job))
              .build());
    }

    private void sendStop(WorkerIdentity target, VariantJob job) throws InterruptedException {
      send(
          EstablishWorkerSessionResponse.newBuilder()
              .setStopVariant(
                  StopVariantCommand.newBuilder()
                      .setTarget(target)
                      .setJobAttemptId(job.getJobAttemptId()))
              .build());
    }

    private void awaitStarted(VariantJob job) throws InterruptedException {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.hasJobAttemptStarted())
          .as("the next worker event must acknowledge the ordering-barrier start command")
          .isTrue();
      assertThat(event.getJobAttemptStarted().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
    }

    private void awaitStopped(VariantJob job) throws InterruptedException {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.hasJobAttemptStopped()).isTrue();
      assertThat(event.getJobAttemptStopped().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
    }

    private void awaitCompleted(VariantJob job) throws InterruptedException {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.hasJobAttemptCompleted()).isTrue();
      assertThat(event.getJobAttemptCompleted().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
    }

    private void failSession() {
      responses.onError(
          Status.UNAVAILABLE.withDescription("control plane stopped").asRuntimeException());
    }

    private JobAttemptFailed awaitFailure() throws InterruptedException {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.hasJobAttemptFailed()).isTrue();
      return event.getJobAttemptFailed();
    }
  }

  private static final class TestServer implements AutoCloseable {

    private final ControllableWorkerService service;
    private Server server;

    private TestServer(ControllableWorkerService service) {
      this.service = service;
    }

    private void start() throws Exception {
      server = NettyServerBuilder.forPort(0).addService(service).build().start();
    }

    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      if (server == null) {
        return;
      }
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private record UploadedSegment(SegmentUploadMetadata metadata, byte[] bytes) {}

  private static final class EndingProcessManager extends FakeSegmentProducingFfmpegProcessManager {
    private EndingProcessManager(Map<String, byte[]> segments) {
      super(segments);
    }

    @Override
    public Process startProcess(
        UUID session, String variant, List<String> command, Path directory) {
      var process = super.startProcess(session, variant, command, directory);
      stopProcess(session, variant);
      return process;
    }
  }

  private static final class DisconnectingProcessManager extends FakeFfmpegProcessManager {
    private final ControllableWorkerService controlPlane;
    private final CompletableFuture<Boolean> outputExistedAtTermination = new CompletableFuture<>();
    private Path outputDirectory;

    private DisconnectingProcessManager(ControllableWorkerService controlPlane) {
      this.controlPlane = controlPlane;
    }

    @Override
    public Process startProcess(
        UUID session, String variant, List<String> command, Path directory) {
      outputDirectory = directory;
      controlPlane.failSession();
      return super.startProcess(session, variant, command, directory);
    }

    @Override
    public void stopProcess(UUID session, String variant) {
      var exists = Files.exists(outputDirectory);
      super.stopProcess(session, variant);
      outputExistedAtTermination.complete(exists);
    }
  }

  private static final class FirstStartFailingProcessManager extends FakeFfmpegProcessManager {
    private boolean first = true;

    @Override
    public Process startProcess(
        UUID session, String variant, List<String> command, Path directory) {
      if (first) {
        first = false;
        throw new IllegalStateException("FFmpeg failed to start");
      }
      return super.startProcess(session, variant, command, directory);
    }
  }

  private static final class WorkerLogCapture extends AppenderBase<ILoggingEvent>
      implements AutoCloseable {
    private final CompletableFuture<ILoggingEvent> event = new CompletableFuture<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TranscodeWorker.class);
    private final Level previous = logger.getLevel();
    private final String message;

    private WorkerLogCapture(String message) {
      this.message = message;
      logger.setLevel(Level.DEBUG);
      start();
      logger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent loggingEvent) {
      if (loggingEvent.getFormattedMessage().contains(message)) {
        event.complete(loggingEvent);
      }
    }

    @Override
    public void close() {
      logger.detachAppender(this);
      logger.setLevel(previous);
      stop();
    }
  }
}
