package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING_CODEC_STRING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.uploadNames;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engineBuilder;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copiedVideo;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copyJobBuilderFor;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.encodedRecordingVideo;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import build.buf.gen.streamarr.transcode.v1.AudioDecision;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EncoderKind;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import build.buf.gen.streamarr.transcode.v1.SegmentContentType;
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
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VariantSpec;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoEncoderBackend;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import build.buf.gen.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ControlPlaneServer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Control Plane Integration Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class TranscodeWorkerControlPlaneIT {

  // The scripted FFmpeg lists this encoder in -encoders and passes its test encode.
  private static final String H264_NVENC_ENCODERS =
      "V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)\n";

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should advertise the software encoders when the worker has no hardware encoder")
  void shouldAdvertiseTheSoftwareEncodersWhenTheWorkerHasNoHardwareEncoder() throws Exception {
    var service = new ControllableWorkerService();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot())) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(service.registration().getCapabilities().getVideoEncodersList())
          .containsExactlyInAnyOrder(
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true),
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "libx265")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true),
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true));
    }
  }

  @Test
  @DisplayName(
      "Should advertise the software encoder when the hardware encoder that passes its test encode"
          + " does not force the job's codec profile and level")
  void
      shouldAdvertiseTheSoftwareEncoderWhenTheHardwareEncoderThatPassesItsTestEncodeDoesNotForceTheJobsCodecProfileAndLevel()
          throws Exception {
    var service = new ControllableWorkerService();

    try (var server = new ControlPlaneServer(service);
        var worker = hardwareWorker(preparedMediaRoot(), ScriptedProcessLauncher.running())) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(service.registration().getCapabilities().getVideoEncodersList())
          .containsExactlyInAnyOrder(
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true),
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "libx265")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true),
              workerVerifiedEncoder(
                  backend(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")
                      .setKind(EncoderKind.ENCODER_KIND_SOFTWARE),
                  true));
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "h264, VIDEO_CODEC_FAMILY_H264",
    "hevc, VIDEO_CODEC_FAMILY_HEVC",
    "av1, VIDEO_CODEC_FAMILY_AV1"
  })
  @DisplayName("Should name the advertised encoder backend when an encode starts")
  void shouldNameTheAdvertisedEncoderBackendWhenAnEncodeStarts(
      String codecFamily, VideoCodecFamily advertisedCodecFamily) throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();
    var encode =
        job.toBuilder()
            .setDecision(
                job.getDecision().toBuilder()
                    .setVideoCodecFamily(codecFamily)
                    .setVideo(levelFortyEncodeOf(advertisedCodecFamily)))
            .build();

    try (var server = new ControlPlaneServer(service);
        var worker = hardwareWorker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), encode);
      var started = service.awaitStarted(encode);

      var advertised = service.advertisedBackend(advertisedCodecFamily);
      assertThat(started.getEncoderBackend()).isEqualTo(advertised);
      assertThat(launcher.command(fromProto(encode.getJobAttemptId())))
          .containsSubsequence("-c:v", advertised.getEncoder());
    }
  }

  // A level 4.0 encode that names the codec family, with the tier that HEVC and AV1 carry.
  private static VideoDecision.Builder levelFortyEncodeOf(VideoCodecFamily codecFamily) {
    var encode = encodedRecordingVideo().setCodecFamily(codecFamily).setLevel(40);
    return switch (codecFamily) {
      case VIDEO_CODEC_FAMILY_H264 -> encode.setCodecString("avc1.640028");
      case VIDEO_CODEC_FAMILY_HEVC ->
          encode
              .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
              .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
              .setCodecString("hvc1.1.6.L120.90");
      case VIDEO_CODEC_FAMILY_AV1 ->
          encode
              .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
              .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
              .setCodecString("av01.0.08M.08");
      default -> throw new AssertionError("No encode is listed for " + codecFamily);
    };
  }

  @Test
  @DisplayName("Should refuse an encode when the worker advertises no encoder for its codec family")
  void shouldRefuseAnEncodeWhenTheWorkerAdvertisesNoEncoderForItsCodecFamily() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();
    var encode =
        job.toBuilder()
            .setDecision(job.getDecision().toBuilder().setVideoCodecFamily("vp9"))
            .build();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), encode);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(encode.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  @Test
  @DisplayName("Should name no encoder backend when a stream copy starts")
  void shouldNameNoEncoderBackendWhenAStreamCopyStarts() throws Exception {
    var service = new ControllableWorkerService();
    var job = variantJob();
    var copy =
        job.toBuilder()
            .setDecision(
                job.getDecision().toBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
                    .setVideo(copiedVideo(ENCODED_RECORDING_CODEC_STRING)))
            .build();

    try (var server = new ControlPlaneServer(service);
        var worker = hardwareWorker(preparedMediaRoot(), ScriptedProcessLauncher.running())) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), copy);
      var started = service.awaitStarted(copy);

      assertThat(started.hasEncoderBackend()).isFalse();
    }
  }

  @Test
  @DisplayName("Should fail startup when the control stream closes before registration is accepted")
  void shouldFailStartupWhenControlStreamClosesBeforeRegistrationIsAccepted() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var service = ControllableWorkerService.closingBeforeRegistration();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(mediaRoot)) {
      server.start();

      assertThatThrownBy(() -> worker.start("localhost", server.port()))
          .isInstanceOf(ExecutionException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Worker session closed");
    }
  }

  @Test
  @DisplayName("Should keep handling commands when the control plane sends an unknown command")
  void shouldKeepHandlingCommandsWhenControlPlaneSendsUnknownCommand() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.send(EstablishWorkerSessionResponse.getDefaultInstance());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);

      assertThat(worker.hasAcceptedSession()).isTrue();
      assertThat(launcher.process(fromProto(job.getJobAttemptId())).isAlive()).isTrue();
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
    try (var server = new ControlPlaneServer(service);
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
  @DisplayName("Should ask the active FFmpeg to quit when the worker closes")
  void shouldAskTheActiveFfmpegToQuitWhenTheWorkerCloses() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);

      worker.close();

      assertQuitGracefully(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName("Should preserve the other variant when the control plane stops one attempt")
  void shouldPreserveOtherVariantWhenControlPlaneStopsOneAttempt() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var stopped = variantJob();
    var surviving =
        stopped.toBuilder()
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setVariant(stopped.getVariant().toBuilder().setVariantLabel("1080p"))
            .build();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), stopped);
      service.awaitStarted(stopped);
      service.sendStart(service.registeredWorker(), surviving);
      service.awaitStarted(surviving);

      service.sendStop(service.registeredWorker(), stopped);
      service.awaitStopped(stopped);

      assertQuitGracefully(launcher.process(fromProto(stopped.getJobAttemptId())));
      assertThat(launcher.process(fromProto(surviving.getJobAttemptId())).isAlive()).isTrue();
    }
  }

  @Test
  @DisplayName("Should preserve command order when replacing the same variant repeatedly")
  void shouldPreserveCommandOrderWhenReplacingSameVariantRepeatedly() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var current = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher, 1)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), current);
      service.awaitStarted(current);

      var replaced = new ArrayList<Uuid>();
      var replacementAttempts = new ArrayList<Uuid>();
      for (var index = 0; index < 100; index++) {
        service.sendStop(service.registeredWorker(), current);
        var next = current.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build();
        service.sendStart(service.registeredWorker(), next);
        replaced.add(current.getJobAttemptId());
        replacementAttempts.add(next.getJobAttemptId());
        current = next;
      }

      // Each stop reports once FFmpeg has exited, on its own thread, so a replaced attempt's stop
      // and its successor's start reach the control plane in either order. With one slot, a start
      // handled before the stop that precedes it would be refused.
      var events = service.awaitEvents(replaced.size() + replacementAttempts.size());
      assertThat(events)
          .filteredOn(EstablishWorkerSessionRequest::hasJobAttemptStopped)
          .extracting(event -> event.getJobAttemptStopped().getJobAttemptId())
          .containsExactlyInAnyOrderElementsOf(replaced);
      assertThat(events)
          .filteredOn(EstablishWorkerSessionRequest::hasJobAttemptStarted)
          .extracting(event -> event.getJobAttemptStarted().getJobAttemptId())
          .containsExactlyInAnyOrderElementsOf(replacementAttempts);
      assertThat(launcher.process(fromProto(current.getJobAttemptId())).isAlive()).isTrue();
    }
  }

  @Test
  @DisplayName("Should upload the requested sequence when resuming a job mid-timeline")
  void shouldUploadRequestedSequenceWhenResumingJobMidTimeline() throws Exception {
    var service = new ControllableWorkerService();
    var recording = recording("07-copy-seek30.fmp4");
    var resumedBuilder = copyJobBuilderFor(recording);
    resumedBuilder.getExecutionBuilder().setStartSequenceNumber(recording.startSequenceNumber());
    var resumed = resumedBuilder.build();
    try (var server = new ControlPlaneServer(service);
        var worker =
            worker(preparedMediaRoot(), ScriptedProcessLauncher.writing(recording.file()))) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), resumed);
      service.awaitStarted(resumed);
      service.awaitCompleted(resumed);

      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording))
          .startsWith("init.mp4", "segment5.m4s");
      assertThat(service.uploads)
          .allSatisfy(
              upload ->
                  assertThat(upload.metadata().getJobAttemptId())
                      .isEqualTo(resumed.getJobAttemptId()));
    }
  }

  @Test
  @DisplayName("Should fail the attempt when FFmpeg exits cleanly without a media segment")
  void shouldFailTheAttemptWhenFfmpegExitsCleanlyWithoutAMediaSegment() throws Exception {
    var service = new ControllableWorkerService();
    var recording = recording(ENCODED_RECORDING);
    var initializationOnly =
        Arrays.copyOf(bytesOf(ENCODED_RECORDING), recording.initializationSegment().byteLength());
    var launcher =
        new ScriptedProcessLauncher(
            _ -> ScriptedProcess.builder().output(initializationOnly).build());
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      var failure = service.awaitFailure();

      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4");
    }
  }

  @Test
  @DisplayName(
      "Should report an output mismatch and upload nothing when a copy's initialization segment"
          + " declares another codec string than the job")
  void
      shouldReportAnOutputMismatchAndUploadNothingWhenACopysInitializationSegmentDeclaresAnotherCodecStringThanTheJob()
          throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJobBuilder().build();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), withCopiedVideo(job, "hvc1.1.6.L120.90"));
      service.awaitStarted(job);
      var failure = service.awaitFailure();

      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
      assertThat(service.uploads).isEmpty();
    }
  }

  private static VariantJob withCopiedVideo(VariantJob job, String codecString) {
    var decision = job.getDecision().toBuilder().setVideo(copiedVideo(codecString));
    return job.toBuilder().setDecision(decision).build();
  }

  @Test
  @DisplayName("Should upload every segment when FFmpeg exits before the worker reads its output")
  void shouldUploadEverySegmentWhenFfmpegExitsBeforeTheWorkerReadsItsOutput() throws Exception {
    var service = new ControllableWorkerService();
    var recording = recording(ENCODED_RECORDING);
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .exitTiming(ExitTiming.AT_LAUNCH)
                    .build());
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      service.awaitCompleted(job);

      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
    }
  }

  @Test
  @DisplayName(
      "Should report an invalid specification without launching FFmpeg when the job carries no"
          + " video decision")
  void shouldReportAnInvalidSpecificationWithoutLaunchingFfmpegWhenTheJobCarriesNoVideoDecision()
      throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();
    var withoutVideoDecision =
        job.toBuilder().setDecision(job.getDecision().toBuilder().clearVideo()).build();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), withoutVideoDecision);
      var failure = service.awaitFailure();

      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  @Test
  @DisplayName("Should accept the next valid job when a media process fails to start")
  void shouldAcceptNextValidJobWhenMediaProcessFailsToStart() throws Exception {
    var service = new ControllableWorkerService();
    var firstLaunch = new AtomicBoolean(true);
    var launcher =
        new ScriptedProcessLauncher(
            _ -> {
              if (firstLaunch.getAndSet(false)) {
                throw new IOException("FFmpeg failed to start");
              }

              return ScriptedProcessLauncher.runningProcessBuilder().build();
            });
    var failed = variantJob();
    var next = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), failed);
      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(failed.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);

      service.sendStart(service.registeredWorker(), next);
      service.awaitStarted(next);

      assertThat(launcher.process(fromProto(next.getJobAttemptId())).isAlive()).isTrue();
      assertThat(launcher.hasLaunched(fromProto(failed.getJobAttemptId()))).isFalse();
    }
  }

  @Test
  @DisplayName("Should accept a valid job when earlier decisions contain invalid enum values")
  void shouldAcceptValidJobWhenEarlierDecisionsContainInvalidEnumValues() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      for (var malformed : malformedVariantJobs()) {
        service.sendStart(service.registeredWorker(), malformed);
        assertThat(service.awaitFailure().getJobAttemptId()).isEqualTo(malformed.getJobAttemptId());
        assertThat(launcher.hasLaunched(fromProto(malformed.getJobAttemptId()))).isFalse();
      }

      var valid = variantJob();
      service.sendStart(service.registeredWorker(), valid);
      service.awaitStarted(valid);

      assertThat(launcher.process(fromProto(valid.getJobAttemptId())).isAlive()).isTrue();
    }
  }

  @Test
  @DisplayName("Should ask the active FFmpeg to quit when the control plane fails")
  void shouldAskTheActiveFfmpegToQuitWhenTheControlPlaneFails() throws Exception {
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);

      service.failSession();

      assertThatThrownBy(worker::awaitDisconnection)
          .isInstanceOf(WorkerJobException.class)
          .hasMessage("Worker session failed")
          .hasRootCauseInstanceOf(StatusRuntimeException.class);
      assertQuitGracefully(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName("Should ask FFmpeg to quit gracefully when the worker closes during an upload")
  void shouldAskFfmpegToQuitGracefullyWhenTheWorkerClosesDuringAnUpload() throws Exception {
    var service = new ControllableWorkerService();
    service.acknowledgeUploads = false;
    var launcher =
        new ScriptedProcessLauncher(
            _ ->
                ScriptedProcess.builder()
                    .output(bytesOf(ENCODED_RECORDING))
                    .exitTiming(ExitTiming.AT_QUIT)
                    .build());
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      service.awaitFirstUpload();

      worker.close();

      assertQuitGracefully(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName("Should ask FFmpeg to quit when the control plane disconnects while it starts")
  void shouldAskFfmpegToQuitWhenTheControlPlaneDisconnectsWhileItStarts() throws Exception {
    var service = new ControllableWorkerService();
    var launcher =
        new ScriptedProcessLauncher(
            _ -> {
              service.failSession();
              return ScriptedProcessLauncher.runningProcessBuilder().build();
            });
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);

      assertThatThrownBy(worker::awaitDisconnection).isInstanceOf(WorkerJobException.class);
      assertQuitGracefully(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName(
      "Should read no further output when the control plane has not acknowledged a segment")
  void shouldReadNoFurtherOutputWhenTheControlPlaneHasNotAcknowledgedASegment() throws Exception {
    var service = new ControllableWorkerService();
    service.acknowledgeUploads = false;
    var recording = recording(ENCODED_RECORDING);
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker = worker(preparedMediaRoot(), launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      service.awaitFirstUpload();
      var process = launcher.process(fromProto(job.getJobAttemptId()));

      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4");
      assertThat(process.hasReadToEndOfOutput()).isFalse();

      service.acknowledgeUploads = true;
      service.acknowledgePendingUpload();
      service.awaitCompleted(job);

      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
    }
  }

  @Test
  @DisplayName("Should complete the attempt when the control plane acknowledges every segment")
  void shouldCompleteTheAttemptWhenTheControlPlaneAcknowledgesEverySegment() throws Exception {
    var service = new ControllableWorkerService();
    var recording = recording(ENCODED_RECORDING);
    var job = variantJob();
    try (var server = new ControlPlaneServer(service);
        var worker =
            worker(preparedMediaRoot(), ScriptedProcessLauncher.writing(ENCODED_RECORDING))) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(service.registeredWorker(), job);
      service.awaitStarted(job);
      service.awaitCompleted(job);

      assertThat(service.uploads)
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactlyElementsOf(uploadNames(recording));
      assertThat(service.uploads)
          .allSatisfy(
              upload -> {
                assertThat(upload.metadata().getContentType())
                    .isEqualTo(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4);
                assertThat(upload.metadata().getContentLengthBytes())
                    .isEqualTo(upload.bytes().length);
              });
      var uploaded = new ByteArrayOutputStream();
      service.uploads.forEach(upload -> uploaded.writeBytes(upload.bytes()));
      assertThat(uploaded.toByteArray()).isEqualTo(bytesOf(ENCODED_RECORDING));
    }
  }

  // Every caller first waits for close(), the stop event, or the disconnection. The worker
  // finishes each of them only after FFmpeg has exited.
  private static void assertQuitGracefully(ScriptedProcess process) {
    assertThat(process.isAlive()).as("FFmpeg has exited").isFalse();
    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(process.wasDestroyedForcibly()).isFalse();
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
    var launcher = ScriptedProcessLauncher.running();
    var job = variantJob();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(mediaRoot, launcher)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(changeTarget.apply(service.registeredWorker()), job);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  private void assertIgnoresStopCommand(UnaryOperator<WorkerIdentity> changeTarget)
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = new ControllableWorkerService();
    var launcher = ScriptedProcessLauncher.running();
    var protectedJob = variantJob();
    var orderingBarrierJob = variantJob();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(mediaRoot, launcher)) {
      server.start();
      worker.start("localhost", server.port());
      service.sendStart(service.registeredWorker(), protectedJob);
      service.awaitStarted(protectedJob);

      service.sendStop(changeTarget.apply(service.registeredWorker()), protectedJob);
      service.sendStart(service.registeredWorker(), orderingBarrierJob);
      service.awaitStarted(orderingBarrierJob);

      assertThat(launcher.process(fromProto(protectedJob.getJobAttemptId())).isAlive())
          .as("a command for another worker must not stop this worker's active attempt")
          .isTrue();
    }
  }

  private TranscodeWorker worker(Path mediaRoot) {
    return worker(mediaRoot, ScriptedProcessLauncher.running());
  }

  // Room for the two attempts that some tests run at once.
  private TranscodeWorker worker(Path mediaRoot, ScriptedProcessLauncher launcher) {
    return worker(mediaRoot, launcher, 2);
  }

  private TranscodeWorker worker(Path mediaRoot, ScriptedProcessLauncher launcher, int slots) {
    return worker(mediaRoot, engine(launcher), slots);
  }

  private TranscodeWorker hardwareWorker(Path mediaRoot, ScriptedProcessLauncher launcher) {
    return worker(mediaRoot, engineBuilder(launcher, H264_NVENC_ENCODERS).build(), 1);
  }

  private TranscodeWorker worker(Path mediaRoot, FfmpegTranscodeEngine engine, int slots) {
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(slots)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .build();
    return TranscodeWorker.builder()
        .configuration(configuration)
        .engine(engine)
        .ffprobe(fileContentsProducer())
        .build();
  }

  private static VideoEncoderBackend.Builder backend(VideoCodecFamily codecFamily, String encoder) {
    return VideoEncoderBackend.newBuilder().setCodecFamily(codecFamily).setEncoder(encoder);
  }

  private static WorkerVerifiedVideoEncoder workerVerifiedEncoder(
      VideoEncoderBackend.Builder backend, boolean keyframeVerified) {
    return WorkerVerifiedVideoEncoder.newBuilder()
        .setBackend(backend)
        .setKeyframeVerified(keyframeVerified)
        .build();
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
                .setVideo(encodedRecordingVideo())
                .setAudio(
                    AudioDecision.newBuilder()
                        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                        .setCodec("aac")
                        .setChannels(2)
                        .setBitrateBitsPerSecond(128_000))
                .setSubtitle(
                    SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4))
        .setVariant(
            VariantSpec.newBuilder()
                .setVariantLabel("720p")
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(5_000_000)
                .setEncoderPreset(EncoderPreset.ENCODER_PRESET_FASTER))
        .setExecution(
            TranscodeExecution.newBuilder()
                .setTargetSegmentDurationSeconds(6)
                .setFramerate(23.976)
                .setMediaSegmentCount(11))
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

  private static final class ControllableWorkerService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final boolean closeBeforeRegistration;
    private final CountDownLatch registered = new CountDownLatch(1);
    private final AtomicReference<WorkerRegistration> registration = new AtomicReference<>();
    // The service's monitor guards these fields. Each wait for a worker message waits on it.
    private final List<EstablishWorkerSessionRequest> events = new ArrayList<>();
    private int eventsTaken;
    private boolean sessionEnded;
    private StreamObserver<EstablishWorkerSessionResponse> responses;
    private final ConcurrentLinkedQueue<UploadedSegment> uploads = new ConcurrentLinkedQueue<>();
    private volatile boolean acknowledgeUploads = true;
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
            receiveEvent(request);
            return;
          }
          if (closeBeforeRegistration) {
            responseObserver.onCompleted();
            return;
          }
          registration.set(request.getRegistration());
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
          markSessionEnded();
        }

        @Override
        public void onCompleted() {
          markSessionEnded();
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
          pendingUpload = responseObserver;
          pendingLength = bytes.size();
          receiveUpload(new UploadedSegment(metadata, bytes.toByteArray()));
          if (acknowledgeUploads) {
            responseObserver.onNext(
                UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(bytes.size()).build());
            responseObserver.onCompleted();
          }
        }
      };
    }

    private synchronized void receiveEvent(EstablishWorkerSessionRequest event) {
      events.add(event);
      notifyAll();
    }

    private synchronized void receiveUpload(UploadedSegment upload) {
      uploads.add(upload);
      notifyAll();
    }

    private synchronized void markSessionEnded() {
      sessionEnded = true;
      notifyAll();
    }

    private synchronized boolean hasUntakenEvent() {
      return eventsTaken < events.size();
    }

    // Fails at once when the session ends, because the awaited event can no longer arrive.
    private synchronized EstablishWorkerSessionRequest takeEvent(String expected)
        throws InterruptedException {
      while (!hasUntakenEvent() && !sessionEnded) {
        wait();
      }

      if (!hasUntakenEvent()) {
        fail("The worker session ended before %s, after %s worker events", expected, eventsTaken);
      }

      return events.get(eventsTaken++);
    }

    // Fails at once when the worker sends an event or the session ends first. After the start, the
    // only event the worker can send ends the attempt, and no upload follows it.
    private synchronized void awaitFirstUpload() throws InterruptedException {
      while (uploads.isEmpty() && !hasUntakenEvent() && !sessionEnded) {
        wait();
      }

      if (!uploads.isEmpty()) {
        return;
      }

      if (hasUntakenEvent()) {
        fail(
            "The worker sent %s before its first segment upload",
            events.get(eventsTaken).getEventCase());
      }

      fail("The worker session ended before the first segment upload");
    }

    private void send(EstablishWorkerSessionResponse response) throws InterruptedException {
      registered.await();
      responses.onNext(response);
    }

    private WorkerIdentity registeredWorker() {
      return registration.get().getWorker();
    }

    private WorkerRegistration registration() {
      return registration.get();
    }

    private VideoEncoderBackend advertisedBackend(VideoCodecFamily codecFamily) {
      return registration().getCapabilities().getVideoEncodersList().stream()
          .map(WorkerVerifiedVideoEncoder::getBackend)
          .filter(backend -> backend.getCodecFamily() == codecFamily)
          .findFirst()
          .orElseThrow();
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

    private JobAttemptStarted awaitStarted(VariantJob job) throws InterruptedException {
      var event = takeEvent("the start of an attempt");
      assertThat(event.hasJobAttemptStarted())
          .as("the next worker event must acknowledge the ordering-barrier start command")
          .isTrue();
      assertThat(event.getJobAttemptStarted().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      return event.getJobAttemptStarted();
    }

    private List<EstablishWorkerSessionRequest> awaitEvents(int count) throws InterruptedException {
      var received = new ArrayList<EstablishWorkerSessionRequest>();
      while (received.size() < count) {
        received.add(takeEvent("worker event %s of %s".formatted(received.size() + 1, count)));
      }

      return received;
    }

    private void awaitStopped(VariantJob job) throws InterruptedException {
      var event = takeEvent("the stop of an attempt");
      assertThat(event.hasJobAttemptStopped()).isTrue();
      assertThat(event.getJobAttemptStopped().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
    }

    private void awaitCompleted(VariantJob job) throws InterruptedException {
      var event = takeEvent("the completion of an attempt");
      assertThat(event.hasJobAttemptCompleted()).isTrue();
      assertThat(event.getJobAttemptCompleted().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
    }

    private void failSession() {
      responses.onError(
          Status.UNAVAILABLE.withDescription("control plane stopped").asRuntimeException());
      markSessionEnded();
    }

    private JobAttemptFailed awaitFailure() throws InterruptedException {
      var event = takeEvent("the failure of an attempt");
      assertThat(event.hasJobAttemptFailed()).isTrue();
      return event.getJobAttemptFailed();
    }
  }

  private record UploadedSegment(SegmentUploadMetadata metadata, byte[] bytes) {}
}
