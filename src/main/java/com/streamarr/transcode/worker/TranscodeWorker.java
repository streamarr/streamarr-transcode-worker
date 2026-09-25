package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.CancelProbeCommand;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStopped;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.SegmentContentType;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.StopVariantCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerCapabilities;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import com.streamarr.transcode.engine.AttemptOutcome;
import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.ProducedSegment;
import com.streamarr.transcode.engine.Producer;
import com.streamarr.transcode.engine.ProducerFailure;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.protocol.ProtoUuid;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TranscodeWorker implements AutoCloseable {

  private static final int CONNECTION_TIMEOUT_SECONDS = 5;
  private static final int UPLOAD_MESSAGE_BYTES = 64 * 1024;

  private final TranscodeWorkerConfiguration configuration;
  private final FfmpegTranscodeEngine engine;
  private final WorkerMediaSourceResolver sources;
  private final WorkerVariantJobMapper jobMapper;
  private final Optional<FfprobeExecutor> ffprobe;
  private final WorkerRuntime runtime;
  private final Map<UUID, Producer> activeAttempts = new HashMap<>();

  private ManagedChannel channel;
  private StreamObserver<EstablishWorkerSessionRequest> requests;
  private WorkerSessionAccepted workerSession;
  private WorkerProbeSession probeSession;
  private CompletableFuture<Void> disconnected;
  private volatile WorkerResponseObserver responseObserver;

  public TranscodeWorker(TranscodeWorkerConfiguration configuration, FfmpegTranscodeEngine engine) {
    this(configuration, engine, Optional.empty(), new GrpcWorkerRuntime());
  }

  public TranscodeWorker(
      TranscodeWorkerConfiguration configuration,
      FfmpegTranscodeEngine engine,
      @NonNull FfprobeExecutor ffprobe) {
    this(configuration, engine, Optional.of(ffprobe), new GrpcWorkerRuntime());
  }

  @Builder
  private TranscodeWorker(
      @NonNull TranscodeWorkerConfiguration configuration,
      @NonNull FfmpegTranscodeEngine engine,
      @NonNull Optional<FfprobeExecutor> ffprobe,
      @NonNull WorkerRuntime runtime) {
    this.configuration = configuration;
    this.engine = engine;
    this.ffprobe = ffprobe;
    this.runtime = runtime;
    sources = new WorkerMediaSourceResolver(configuration.sourceNamespaces());
    jobMapper = new WorkerVariantJobMapper(sources);
  }

  public synchronized void start(String host, int port)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    if (channel != null) {
      throw new IllegalStateException("Transcode worker is already started");
    }

    var channelBuilder =
        runtime.channelBuilder(configuration, InetSocketAddress.createUnresolved(host, port));
    // Client keepalive detects a half-open control-plane connection (server power loss, dropped
    // NAT mapping); without it an idle worker would wait on a dead session until TCP gives up.
    channel =
        channelBuilder
            .keepAliveTime(configuration.keepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveTimeout(configuration.keepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .build();
    var accepted = new CompletableFuture<WorkerSessionAccepted>();
    var sessionRequests = new AtomicReference<StreamObserver<EstablishWorkerSessionRequest>>();
    probeSession =
        WorkerProbeSession.builder()
            .ffprobe(ffprobe)
            .sources(sources)
            .results(result -> sendProbeResult(sessionRequests.get(), result))
            .executor(runtime.newProbeScope())
            .build();
    disconnected = new CompletableFuture<>();
    responseObserver = new WorkerResponseObserver(accepted, probeSession);
    requests = TranscodeWorkerServiceGrpc.newStub(channel).establishWorkerSession(responseObserver);
    sessionRequests.set(requests);
    send(registration());
    accepted.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  public boolean hasAcceptedSession() {
    var observer = responseObserver;
    return observer != null && observer.sessionAccepted && !observer.sessionDisconnected.isDone();
  }

  public void awaitDisconnection() throws InterruptedException {
    try {
      disconnected.get();
    } catch (ExecutionException e) {
      throw new WorkerJobException("Worker session failed", e);
    }
  }

  private EstablishWorkerSessionRequest registration() {
    var capabilities = WorkerCapabilities.newBuilder();
    ffprobe.ifPresent(_ -> capabilities.addProbeVersions(FfprobeExecutor.PROBE_VERSION));
    configuration.sourceNamespaces().keySet().stream()
        .map(ProtoUuid::toProto)
        .forEach(capabilities::addSourceNamespaceIds);
    var registration =
        WorkerRegistration.newBuilder()
            .setWorker(identity())
            .setCapabilities(capabilities)
            .setAvailableSlots(configuration.availableSlots());
    return EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build();
  }

  private WorkerIdentity identity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(configuration.workerId()))
        .setBootId(toProto(configuration.bootId()))
        .build();
  }

  private synchronized void sendProbeResult(
      StreamObserver<EstablishWorkerSessionRequest> sessionRequests, ProbeAttemptResult result) {
    if (requests == null || requests != sessionRequests) {
      return;
    }

    send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());
  }

  @SuppressWarnings("java:S3398") // Job lifecycle belongs to the worker, not its gRPC observer.
  private void startVariant(StartVariantCommand command) {
    // The stop waits for FFmpeg to exit, so it runs without the monitor that uploads need.
    if (startAttempt(command) instanceof Orphaned(var producer)) {
      producer.stop();
    }
  }

  private synchronized AttemptStart startAttempt(StartVariantCommand command) {
    var job = command.getJob();
    if (!isRunnableHere(command)) {
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      return new Refused();
    }

    Producer producer;
    try {
      producer = engine.startProducer(jobMapper.map(job), segment -> deliverToServer(job, segment));
    } catch (RuntimeException e) {
      logStartupFailure(job, e);
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return new Refused();
    }

    activeAttempts.put(fromProto(job.getJobAttemptId()), producer);
    try {
      send(jobAttemptStarted(job.getJobAttemptId()));
    } catch (RuntimeException e) {
      logStartupFailure(job, e);
      activeAttempts.remove(fromProto(job.getJobAttemptId()));
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return new Orphaned(producer);
    }

    producer.outcome().thenAccept(outcome -> settleAttempt(job, producer, outcome));
    return new Started();
  }

  private boolean isRunnableHere(StartVariantCommand command) {
    var job = command.getJob();
    return command.getTarget().equals(identity())
        && job.getDecision().getContainer() == ContainerFormat.CONTAINER_FORMAT_FMP4
        && hasUsableFrameRate(job)
        && startsAtAnAdvertisedMediaSegment(job);
  }

  // The server advertises the variant's media segment count to every job attempt; zero is unset.
  private static boolean startsAtAnAdvertisedMediaSegment(VariantJob job) {
    var execution = job.getExecution();
    return execution.getMediaSegmentCount() > execution.getStartSequenceNumber();
  }

  // The worker encodes video at the probed frame rate and counts its GOP from that rate.
  private static boolean hasUsableFrameRate(VariantJob job) {
    if (!WorkerVariantJobMapper.encodesVideo(job)) {
      return true;
    }

    var framerate = job.getExecution().getFramerate();
    return framerate > 0 && Double.isFinite(framerate);
  }

  private static void logStartupFailure(VariantJob job, RuntimeException failure) {
    log.error(
        "Failed to start variant {} of stream session {}",
        job.getVariant().getVariantLabel(),
        fromProto(job.getStreamSessionId()),
        failure);
  }

  // The attempt's producer delivers each segment here and waits until the server accepts it.
  private void deliverToServer(VariantJob job, ProducedSegment segment) {
    try {
      uploadSegment(job, segment);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WorkerJobException("Interrupted while uploading " + segment, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new WorkerJobException("Upload of " + segment + " failed", e);
    }
  }

  private void uploadSegment(VariantJob job, ProducedSegment segment)
      throws InterruptedException, ExecutionException, TimeoutException {
    var upload = new SegmentUpload();
    var metadata =
        openUpload(job, upload)
            .setSegmentName(segment.name())
            .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4)
            .setContentLengthBytes(segment.byteLength())
            .build();
    UploadSegmentResponse accepted;
    try {
      upload.send(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());
      sendContent(upload, segment);
      upload.finish();
      accepted = upload.awaitAcknowledgement();
    } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
      upload.cancel(e);
      throw e;
    }

    if (accepted.getAcceptedLengthBytes() != segment.byteLength()) {
      throw new WorkerJobException("Server accepted an incomplete segment");
    }
  }

  // Opens the upload call while the attempt is still active in this session.
  private synchronized SegmentUploadMetadata.Builder openUpload(
      VariantJob job, SegmentUpload upload) {
    if (!activeAttempts.containsKey(fromProto(job.getJobAttemptId()))) {
      throw new WorkerJobException("Job attempt is no longer active");
    }

    TranscodeWorkerServiceGrpc.newStub(channel).uploadSegment(upload);
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(workerSession.getWorkerSessionId())
        .setWorker(identity())
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel());
  }

  private static void sendContent(SegmentUpload upload, ProducedSegment segment)
      throws InterruptedException {
    var pending = ByteString.EMPTY;
    for (var box : segment.content()) {
      // The producer never changes a segment it delivered, so wrapping its boxes copies nothing.
      pending = sendWholeDataMessages(upload, pending.concat(UnsafeByteOperations.unsafeWrap(box)));
    }

    if (!pending.isEmpty()) {
      upload.send(dataMessage(pending));
    }
  }

  // Sends every full-sized data message and returns the remainder.
  private static ByteString sendWholeDataMessages(SegmentUpload upload, ByteString content)
      throws InterruptedException {
    var remaining = content;
    while (remaining.size() >= UPLOAD_MESSAGE_BYTES) {
      upload.send(dataMessage(remaining.substring(0, UPLOAD_MESSAGE_BYTES)));
      remaining = remaining.substring(UPLOAD_MESSAGE_BYTES);
    }

    return remaining;
  }

  private static UploadSegmentRequest dataMessage(ByteString data) {
    return UploadSegmentRequest.newBuilder().setData(data).build();
  }

  // Reports the producer's outcome unless a stop or the session's end has already claimed it.
  private synchronized void settleAttempt(
      VariantJob job, Producer producer, AttemptOutcome outcome) {
    if (!activeAttempts.remove(fromProto(job.getJobAttemptId()), producer)) {
      return;
    }

    tryReport(
        switch (outcome) {
          case Completed _ -> jobAttemptCompleted(job.getJobAttemptId());
          case Failed(var reason, var detail) -> transcodeFailure(job, reason, detail);
          case Stopped _ -> jobAttemptStopped(job.getJobAttemptId());
        });
  }

  private static EstablishWorkerSessionRequest transcodeFailure(
      VariantJob job, ProducerFailure reason, String detail) {
    log.warn(
        "Variant {} of stream session {} failed ({}): {}",
        job.getVariant().getVariantLabel(),
        fromProto(job.getStreamSessionId()),
        reason,
        detail);
    return jobAttemptFailed(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
  }

  @SuppressWarnings("java:S3398") // Job lifecycle belongs to the worker, not its gRPC observer.
  private void stopVariant(StopVariantCommand command) {
    // The stop waits for FFmpeg to exit, so it runs without the monitor that uploads need.
    claimStoppedAttempt(command)
        .ifPresent(
            stop -> {
              stop.producer().stop();
              reportStopped(stop, command.getJobAttemptId());
            });
  }

  // Claims the attempt so that its producer's own outcome is never reported.
  private synchronized Optional<ClaimedStop> claimStoppedAttempt(StopVariantCommand command) {
    if (!command.getTarget().equals(identity())) {
      return Optional.empty();
    }

    return Optional.ofNullable(activeAttempts.remove(fromProto(command.getJobAttemptId())))
        .map(producer -> new ClaimedStop(producer, requests));
  }

  // A stop that outlives its session reports nothing; the server learned of the end on disconnect.
  private synchronized void reportStopped(ClaimedStop stop, Uuid jobAttemptId) {
    if (requests != stop.session()) {
      return;
    }

    tryReport(jobAttemptStopped(jobAttemptId));
  }

  private void reportFailure(VariantJob job, JobAttemptFailure failure) {
    tryReport(jobAttemptFailed(job, failure));
  }

  private void tryReport(EstablishWorkerSessionRequest report) {
    try {
      send(report);
    } catch (RuntimeException e) {
      // The control stream is gone (shutdown or connection loss). The server learns of the
      // attempt's end from the disconnect itself, so the lost report costs precision, not safety.
      log.debug("Could not report {}", report, e);
    }
  }

  private static EstablishWorkerSessionRequest jobAttemptStarted(Uuid jobAttemptId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptStarted(JobAttemptStarted.newBuilder().setJobAttemptId(jobAttemptId))
        .build();
  }

  private static EstablishWorkerSessionRequest jobAttemptCompleted(Uuid jobAttemptId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptCompleted(JobAttemptCompleted.newBuilder().setJobAttemptId(jobAttemptId))
        .build();
  }

  private static EstablishWorkerSessionRequest jobAttemptStopped(Uuid jobAttemptId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptStopped(JobAttemptStopped.newBuilder().setJobAttemptId(jobAttemptId))
        .build();
  }

  private static EstablishWorkerSessionRequest jobAttemptFailed(
      VariantJob job, JobAttemptFailure failure) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptFailed(
            JobAttemptFailed.newBuilder()
                .setJobAttemptId(job.getJobAttemptId())
                .setFailure(failure))
        .build();
  }

  private synchronized void send(EstablishWorkerSessionRequest request) {
    if (requests == null) {
      throw new IllegalStateException("Worker session is closed");
    }
    requests.onNext(request);
  }

  @SuppressWarnings("java:S3398") // Job lifecycle belongs to the worker, not its gRPC observer.
  private void endSession(WorkerProbeSession sessionProbes) {
    stopAll(claimSessionAttempts(sessionProbes));
  }

  @SuppressWarnings("java:S3398") // The fence shares the worker monitor with close() and start().
  private synchronized List<Producer> claimSessionAttempts(WorkerProbeSession sessionProbes) {
    if (probeSession != sessionProbes) {
      return List.of();
    }

    if (!activeAttempts.isEmpty()) {
      log.warn(
          "Worker session ended with {} abandoned job attempt(s): {}",
          activeAttempts.size(),
          activeAttempts.keySet());
    }

    return claimActiveAttempts();
  }

  private synchronized List<Producer> claimActiveAttempts() {
    var producers = List.copyOf(activeAttempts.values());
    activeAttempts.clear();
    return producers;
  }

  // Stops the producers together and returns once every FFmpeg has exited.
  private static void stopAll(List<Producer> producers) {
    try (var stops = Executors.newVirtualThreadPerTaskExecutor()) {
      producers.forEach(producer -> stops.execute(producer::stop));
    }
  }

  @Override
  public void close() {
    // Stopping first lets FFmpeg quit while the session can still carry its uploads.
    stopAll(claimActiveAttempts());
    // Probe completion needs the worker monitor, so join only after closeConnection releases it.
    closeConnection()
        .ifPresent(
            closed -> {
              stopAll(closed.attempts());
              closed.probes().close();
            });
  }

  // Returns the closed session's probes and any attempt that started after the stops began.
  private synchronized Optional<ClosedSession> closeConnection() {
    responseObserver = null;

    if (channel == null) {
      return Optional.empty();
    }

    probeSession.shutdown();
    var attempts = claimActiveAttempts();
    try {
      requests.onCompleted();
    } catch (RuntimeException _) {
      // The session stream already failed; shutdown proceeds regardless.
    }
    channel.shutdownNow();
    try {
      if (!channel.awaitTermination(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("gRPC channel did not terminate within {}s", CONNECTION_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    disconnected.complete(null);
    requests = null;
    workerSession = null;
    channel = null;
    var closedProbes = probeSession;
    probeSession = null;
    return Optional.of(new ClosedSession(closedProbes, attempts));
  }

  private final class WorkerResponseObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CompletableFuture<WorkerSessionAccepted> accepted;
    private volatile boolean sessionAccepted;
    private final WorkerProbeSession sessionProbes;
    private final CompletableFuture<Void> sessionDisconnected = disconnected;

    private WorkerResponseObserver(
        CompletableFuture<WorkerSessionAccepted> accepted, WorkerProbeSession sessionProbes) {
      this.accepted = accepted;
      this.sessionProbes = sessionProbes;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse response) {
      if (response.hasSessionAccepted()) {
        workerSession = response.getSessionAccepted();
        sessionAccepted = true;
        accepted.complete(workerSession);
        return;
      }

      if (response.hasStartVariant()) {
        startVariant(response.getStartVariant());
        return;
      }

      if (response.hasStartProbe()) {
        startProbe(response.getStartProbe());
        return;
      }

      if (response.hasCancelProbe()) {
        cancelProbe(response.getCancelProbe());
        return;
      }

      if (response.hasStopVariant()) {
        stopVariant(response.getStopVariant());
        return;
      }

      log.warn("Worker received unexpected control command {}", response.getCommandCase());
    }

    private void startProbe(StartProbeCommand command) {
      if (!command.getTarget().equals(identity())) {
        log.warn(
            "Ignoring probe {} addressed to worker {} boot {}",
            fromProto(command.getRequest().getProbeAttemptId()),
            fromProto(command.getTarget().getWorkerId()),
            fromProto(command.getTarget().getBootId()));
        return;
      }

      sessionProbes.start(command.getRequest());
    }

    private void cancelProbe(CancelProbeCommand command) {
      if (!command.getTarget().equals(identity())) {
        return;
      }

      sessionProbes.cancel(fromProto(command.getProbeAttemptId()));
    }

    @Override
    public void onError(Throwable throwable) {
      sessionAccepted = false;
      accepted.completeExceptionally(throwable);
      sessionProbes.shutdown();
      endSession(sessionProbes);
      sessionDisconnected.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      sessionAccepted = false;
      sessionProbes.shutdown();
      if (!accepted.isDone()) {
        accepted.completeExceptionally(new IllegalStateException("Worker session closed"));
      }
      endSession(sessionProbes);
      sessionDisconnected.complete(null);
    }
  }

  // One segment's upload call, which sends a message only when the call is ready for it.
  private static final class SegmentUpload
      implements ClientResponseObserver<UploadSegmentRequest, UploadSegmentResponse> {

    private final CompletableFuture<UploadSegmentResponse> response = new CompletableFuture<>();
    private final Object readiness = new Object();
    private ClientCallStreamObserver<UploadSegmentRequest> call;

    @Override
    public void beforeStart(ClientCallStreamObserver<UploadSegmentRequest> call) {
      this.call = call;
      call.setOnReadyHandler(this::signalReadiness);
    }

    // Sends nothing once the server has answered; the answer decides the upload.
    private void send(UploadSegmentRequest message) throws InterruptedException {
      awaitReadiness();
      if (!response.isDone()) {
        call.onNext(message);
      }
    }

    private void awaitReadiness() throws InterruptedException {
      synchronized (readiness) {
        while (!call.isReady() && !response.isDone()) {
          readiness.wait();
        }
      }
    }

    private void signalReadiness() {
      synchronized (readiness) {
        readiness.notifyAll();
      }
    }

    private void finish() {
      if (!response.isDone()) {
        call.onCompleted();
      }
    }

    private UploadSegmentResponse awaitAcknowledgement()
        throws InterruptedException, ExecutionException, TimeoutException {
      return response.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void cancel(Throwable failure) {
      call.cancel("Segment upload failed", failure);
    }

    @Override
    public void onNext(UploadSegmentResponse value) {
      response.complete(value);
      signalReadiness();
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
      signalReadiness();
    }

    @Override
    public void onCompleted() {
      // gRPC routes a client-streaming close without its single response through onError.
    }
  }

  public static class TranscodeWorkerBuilder {
    private Optional<FfprobeExecutor> ffprobe = Optional.empty();
    private WorkerRuntime runtime = new GrpcWorkerRuntime();

    public TranscodeWorkerBuilder ffprobe(@NonNull FfprobeExecutor executor) {
      ffprobe = Optional.of(executor);
      return this;
    }
  }

  private record ClosedSession(WorkerProbeSession probes, List<Producer> attempts) {}

  private record ClaimedStop(
      Producer producer, StreamObserver<EstablishWorkerSessionRequest> session) {}

  private sealed interface AttemptStart {}

  private record Started() implements AttemptStart {}

  private record Refused() implements AttemptStart {}

  // The producer started, but the worker could not report the start and must stop it.
  private record Orphaned(Producer producer) implements AttemptStart {}
}
