package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.encodedRecordingVideo;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.AudioDecision;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
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
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ControlPlaneServer;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Upload Protocol Integration Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class TranscodeWorkerUploadProtocolIT {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  // Short enough that the cancellation test outlives it.
  private static final Duration ACKNOWLEDGEMENT_TIMEOUT = Duration.ofSeconds(5);

  // Longer than the class timeout, so a failure that a test sees cannot come from this timeout.
  private static final Duration ACKNOWLEDGEMENT_TIMEOUT_BEYOND_TEST = Duration.ofMinutes(10);

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should cancel the upload RPC when the acknowledgement times out")
  void shouldCancelUploadRpcWhenAcknowledgementTimesOut() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var service = ControllableUploadService.leavesUploadRpcOpen();
    var job = variantJob();

    // The worker cancels the upload before it reports the failure, and both calls share one
    // connection. The cancellation's frame therefore arrives first, and this server handles it
    // before the failure.
    try (var server = ControlPlaneServer.withCallbacksInFrameOrder(service);
        var worker = worker(engine(launcher), mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      service.awaitUploadCompleted();
      service.awaitFailure();
      assertEnded(launcher.process(fromProto(job.getJobAttemptId())));
      assertThat(service.uploadCancelled)
          .as("abandoned upload must be cancelled once the acknowledgement times out")
          .isCompleted();
    }
  }

  @Test
  @DisplayName(
      "Should fail a variant when the server acknowledges fewer bytes than the worker uploaded")
  void shouldFailVariantWhenServerAcknowledgesFewerBytesThanWorkerUploaded() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var service = ControllableUploadService.acknowledgesFewerBytes();
    var job = variantJob();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(engine(launcher), mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertEnded(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName(
      "Should fail a variant promptly when the upload stream closes before acknowledgement")
  void shouldFailVariantPromptlyWhenUploadStreamClosesBeforeAcknowledgement() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var service = ControllableUploadService.closesWithoutResponse();
    var job = variantJob();
    var configuration =
        configurationBuilder(mediaRoot)
            .uploadAcknowledgementTimeout(ACKNOWLEDGEMENT_TIMEOUT_BEYOND_TEST)
            .build();

    try (var server = new ControlPlaneServer(service);
        var worker =
            TranscodeWorker.builder()
                .configuration(configuration)
                .engine(engine(launcher))
                .ffprobe(fileContentsProducer())
                .build()) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      var failure = service.awaitFailure();
      assertThat(service.uploadCompleted)
          .as("the upload completed before the failure")
          .isCompleted();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertEnded(launcher.process(fromProto(job.getJobAttemptId())));
    }
  }

  @Test
  @DisplayName(
      "Should report startup failure when FFmpeg becomes incompatible after worker registration")
  void shouldReportStartupFailureWhenFfmpegBecomesIncompatibleAfterWorkerRegistration()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = ControllableUploadService.leavesUploadRpcOpen();
    var job = variantJob();

    try (var server = new ControlPlaneServer(service);
        var worker = worker(unavailableEngine(), mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
    }
  }

  // FFmpeg is still writing when the upload fails, so the worker has to end it. The worker reports
  // the failure only after FFmpeg has exited.
  private static void assertEnded(ScriptedProcess process) {
    assertThat(process.isAlive()).as("FFmpeg has exited").isFalse();
    assertThat(process.wasDestroyedForcibly()).isTrue();
  }

  private TranscodeWorker worker(FfmpegTranscodeEngine engine, Path mediaRoot) {
    return TranscodeWorker.builder()
        .configuration(configurationBuilder(mediaRoot).build())
        .engine(engine)
        .ffprobe(fileContentsProducer())
        .build();
  }

  private static TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder
      configurationBuilder(Path mediaRoot) {
    return TranscodeWorkerConfiguration.builder()
        .workerId(WORKER_ID)
        .bootId(UUID.randomUUID())
        .availableSlots(1)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
        .uploadAcknowledgementTimeout(ACKNOWLEDGEMENT_TIMEOUT);
  }

  private FfmpegTranscodeEngine unavailableEngine() {
    var capabilities =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new IOException("FFmpeg disappeared");
            });
    capabilities.detectCapabilities();
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(capabilities)
        .launcher(ScriptedProcessLauncher.running())
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
        .build();
  }

  private VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
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

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  // Each awaited future fails as soon as its outcome can no longer arrive: when the worker session
  // ends, or when the job attempt ends first.
  private static final class ControllableUploadService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final UploadResponseBehavior uploadResponseBehavior;
    private final AtomicReference<StreamObserver<EstablishWorkerSessionResponse>> control =
        new AtomicReference<>();
    private final CompletableFuture<WorkerIdentity> registration = new CompletableFuture<>();
    private final CompletableFuture<Void> uploadCompleted = new CompletableFuture<>();
    private final CompletableFuture<Void> uploadCancelled = new CompletableFuture<>();
    private final CompletableFuture<JobAttemptFailed> failure = new CompletableFuture<>();

    private ControllableUploadService(UploadResponseBehavior uploadResponseBehavior) {
      this.uploadResponseBehavior = uploadResponseBehavior;
    }

    private static ControllableUploadService leavesUploadRpcOpen() {
      return new ControllableUploadService(UploadResponseBehavior.LEAVES_RPC_OPEN);
    }

    private static ControllableUploadService acknowledgesFewerBytes() {
      return new ControllableUploadService(UploadResponseBehavior.ACKNOWLEDGES_FEWER_BYTES);
    }

    private static ControllableUploadService closesWithoutResponse() {
      return new ControllableUploadService(UploadResponseBehavior.CLOSES_WITHOUT_RESPONSE);
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      control.set(responseObserver);
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest request) {
          if (request.hasRegistration()) {
            registration.complete(request.getRegistration().getWorker());
            responseObserver.onNext(
                EstablishWorkerSessionResponse.newBuilder()
                    .setSessionAccepted(
                        WorkerSessionAccepted.newBuilder()
                            .setWorkerSessionId(uuidStatic(UUID.randomUUID())))
                    .build());
            return;
          }

          if (request.hasJobAttemptFailed()) {
            failure.complete(request.getJobAttemptFailed());
          }

          // Every event after the start ends the attempt.
          if (!request.hasJobAttemptStarted()) {
            endAttempt(request.getEventCase());
          }
        }

        @Override
        public void onError(Throwable throwable) {
          endSession();
        }

        @Override
        public void onCompleted() {
          endSession();
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<UploadSegmentRequest> uploadSegment(
        StreamObserver<UploadSegmentResponse> responseObserver) {
      ((ServerCallStreamObserver<UploadSegmentResponse>) responseObserver)
          .setOnCancelHandler(() -> uploadCancelled.complete(null));
      return new StreamObserver<>() {
        @Override
        public void onNext(UploadSegmentRequest request) {
          // Uploaded frames are irrelevant; only cancellation matters.
        }

        @Override
        public void onError(Throwable throwable) {
          uploadCancelled.complete(null);
        }

        @Override
        public void onCompleted() {
          uploadCompleted.complete(null);
          switch (uploadResponseBehavior) {
            case LEAVES_RPC_OPEN -> {
              // Keep the response open so the client must cancel the timed-out upload.
            }
            case ACKNOWLEDGES_FEWER_BYTES -> {
              responseObserver.onNext(
                  UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(0).build());
              responseObserver.onCompleted();
            }
            case CLOSES_WITHOUT_RESPONSE -> responseObserver.onCompleted();
          }
        }
      };
    }

    private void endAttempt(EventCase ending) {
      uploadCompleted.completeExceptionally(
          new AssertionError("The worker sent %s before an upload completed".formatted(ending)));
      failure.completeExceptionally(
          new AssertionError("The worker sent %s instead of a failure".formatted(ending)));
    }

    private void endSession() {
      registration.completeExceptionally(sessionEndedBefore("the worker registered"));
      uploadCompleted.completeExceptionally(sessionEndedBefore("an upload completed"));
      failure.completeExceptionally(sessionEndedBefore("the worker reported a failure"));
    }

    private static AssertionError sessionEndedBefore(String expectation) {
      return new AssertionError("The worker session ended before " + expectation);
    }

    // The service accepts a session only after it records the registration, so the registration is
    // known once the worker's start() has returned.
    private void dispatch(VariantJob job) {
      control
          .get()
          .onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setStartVariant(
                      StartVariantCommand.newBuilder()
                          .setTarget(registration.resultNow())
                          .setJob(job))
                  .build());
    }

    private void awaitUploadCompleted() throws InterruptedException, ExecutionException {
      uploadCompleted.get();
    }

    private JobAttemptFailed awaitFailure() throws InterruptedException, ExecutionException {
      return failure.get();
    }

    private static Uuid uuidStatic(UUID value) {
      return Uuid.newBuilder()
          .setMostSignificantBits(value.getMostSignificantBits())
          .setLeastSignificantBits(value.getLeastSignificantBits())
          .build();
    }

    private enum UploadResponseBehavior {
      LEAVES_RPC_OPEN,
      ACKNOWLEDGES_FEWER_BYTES,
      CLOSES_WITHOUT_RESPONSE
    }
  }
}
