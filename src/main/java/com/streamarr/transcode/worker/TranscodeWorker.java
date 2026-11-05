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
import build.buf.gen.streamarr.transcode.v1.JobAttemptProgress;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStopped;
import build.buf.gen.streamarr.transcode.v1.ProbeCapacityChanged;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.SegmentContentType;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.SourceEndedEarly;
import build.buf.gen.streamarr.transcode.v1.SourceStreamEnd;
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
import com.streamarr.transcode.engine.DeliveryCancellation;
import com.streamarr.transcode.engine.EncoderBackend;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.engine.NoKeyframeBeforeReadBackException;
import com.streamarr.transcode.engine.ProducedSegment;
import com.streamarr.transcode.engine.Producer;
import com.streamarr.transcode.engine.ProducerFailure;
import com.streamarr.transcode.engine.SegmentMemoryBudget;
import com.streamarr.transcode.engine.SourceAudio;
import com.streamarr.transcode.engine.SourceEnd;
import com.streamarr.transcode.engine.Speaker;
import com.streamarr.transcode.engine.SpeakerLayout;
import com.streamarr.transcode.engine.TranscodeRequest;
import com.streamarr.transcode.engine.UnadvertisedCodecFamilyException;
import com.streamarr.transcode.probe.DownmixLevels;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeAttemptReport;
import com.streamarr.transcode.protocol.ProtoUuid;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TranscodeWorker implements AutoCloseable {

  private static final int CONNECTION_TIMEOUT_SECONDS = 5;
  private static final int UPLOAD_MESSAGE_BYTES = 64 * 1024;
  private static final Duration PROBE_SUMMARY_INTERVAL = Duration.ofMinutes(1);
  private static final Duration PROBE_CAPACITY_REFRESH_INTERVAL = Duration.ofSeconds(1);
  private static final Duration JOB_ATTEMPT_PROGRESS_INTERVAL = Duration.ofSeconds(1);
  // The heap that the worker keeps for everything besides FFmpeg's output.
  private static final long HEAP_RESERVE_BYTES = 512L * 1024 * 1024;

  private final TranscodeWorkerConfiguration configuration;
  private final FfmpegTranscodeEngine engine;
  private final WorkerMediaSourceResolver sources;
  private final WorkerVariantJobMapper jobMapper;
  private final FfprobeExecutor ffprobe;
  private final WorkerRuntime runtime;
  private final SegmentMemoryBudget memoryBudget;
  private final int maximumCopies;
  private final SegmentMemoryBudget copyMemoryBudget;
  private final ProbeSummary probeSummary;
  private final ProbeCapacities probeCapacities;
  // Changed under this monitor, and concurrent so that copyFullness reads them without it: a start
  // holds the monitor while it resolves the source and launches FFmpeg, which a stalled mount can
  // block.
  private final Map<UUID, ActiveAttempt> activeAttempts = new ConcurrentHashMap<>();
  private final Map<UUID, PendingRead> pendingReads = new ConcurrentHashMap<>();
  private final Set<Thread> stopThreads = new HashSet<>();

  private ManagedChannel channel;
  private StreamObserver<EstablishWorkerSessionRequest> requests;
  private WorkerSessionAccepted workerSession;
  private volatile SegmentUploadLimit uploadLimit = new SegmentUploadLimit(0);
  private WorkerProbeSession probeSession;
  private ScheduledExecutorService sessionTimers;
  private Optional<GrantedCapacities> sentCapacities = Optional.empty();
  private CompletableFuture<Void> disconnected;
  private final AtomicReference<WorkerResponseObserver> responseObserver = new AtomicReference<>();

  @Builder
  private TranscodeWorker(
      @NonNull TranscodeWorkerConfiguration configuration,
      @NonNull FfmpegTranscodeEngine engine,
      @NonNull FfprobeExecutor ffprobe,
      @NonNull WorkerRuntime runtime,
      ProbeSummary probeSummary,
      long maximumHeapBytes,
      @NonNull MonotonicClock clock) {
    this.configuration = configuration;
    this.engine = engine;
    this.ffprobe = ffprobe;
    this.runtime = runtime;
    this.probeSummary = probeSummary == null ? new ProbeSummary(clock) : probeSummary;
    this.probeCapacities =
        ProbeCapacities.builder()
            .clock(clock)
            .backgroundProbeCapacity(configuration.backgroundProbeCapacity())
            .onChange(this::sendProbeCapacityChange)
            .build();
    memoryBudget = SegmentMemoryBudget.forSlots(configuration.availableSlots());
    maximumCopies = copiesWithin(maximumHeapBytes, configuration.availableSlots());
    copyMemoryBudget = SegmentMemoryBudget.forCopies(maximumCopies);
    log.info(
        "The worker runs up to {} copies at once: what its {} MiB heap holds after the encodes'"
            + " memory and a reserve",
        maximumCopies,
        maximumHeapBytes / (1024 * 1024));
    sources = new WorkerMediaSourceResolver(configuration.sourceNamespaces());
    jobMapper = new WorkerVariantJobMapper(sources);
  }

  /**
   * A builder for a worker that connects over gRPC and holds the copies that fit in the JVM's
   * maximum heap, which the JVM sizes from the container's memory limit.
   */
  public static TranscodeWorkerBuilder builder() {
    return new TranscodeWorkerBuilder()
        .runtime(new GrpcWorkerRuntime())
        .maximumHeapBytes(Runtime.getRuntime().maxMemory());
  }

  // Copies hold FFmpeg's output in the heap, beside the encodes' memory and the reserve.
  private static int copiesWithin(long heapBytes, int slots) {
    var copyBytes = heapBytes - HEAP_RESERVE_BYTES - slots * SegmentMemoryBudget.PRODUCER_BYTES;
    return Math.clamp(copyBytes / SegmentMemoryBudget.PRODUCER_BYTES, 0, Integer.MAX_VALUE);
  }

  /**
   * The share of the copies that the worker's heap holds that run now, from 0 to 1; 1 when the heap
   * holds none, so an autoscaler reads a worker that cannot copy as full.
   */
  public double copyFullness() {
    if (maximumCopies == 0) {
      return 1;
    }

    return (double) placesInUse(true) / maximumCopies;
  }

  /**
   * The segments that wait for one of the upload calls that the server accepts from the worker at
   * once to end.
   */
  public int queuedSegmentUploads() {
    return uploadLimit.queued();
  }

  /**
   * Connects to the server and waits until it accepts the worker session. The wait spans a server
   * that is still starting, up to the configured session acceptance timeout, and ends early when
   * the worker closes.
   *
   * @throws SessionAcceptanceTimeoutException when the server has not accepted the session in time
   */
  public void start(String host, int port)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    var timeout = configuration.sessionAcceptanceTimeout();
    log.info("Waiting for the Streamarr server at {}:{} to accept the worker session", host, port);
    // Waits without the monitor, so that close() can end the wait.
    var accepted = openSession(host, port);
    try {
      accepted.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException _) {
      throw new SessionAcceptanceTimeoutException(host, port, timeout);
    }
  }

  private synchronized CompletableFuture<WorkerSessionAccepted> openSession(String host, int port)
      throws IOException {
    if (channel != null) {
      throw new IllegalStateException("Transcode worker is already started");
    }

    channel = runtime.openChannel(configuration, InetSocketAddress.createUnresolved(host, port));
    var accepted = new CompletableFuture<WorkerSessionAccepted>();
    var sessionRequests = new AtomicReference<StreamObserver<EstablishWorkerSessionRequest>>();
    probeSession =
        WorkerProbeSession.builder()
            .ffprobe(ffprobe)
            .sources(sources)
            .reports(report -> sendProbeResult(sessionRequests.get(), report))
            .executor(runtime.newProbeScope())
            .capacities(probeCapacities)
            .summary(probeSummary)
            .build();
    sessionTimers =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("session-timers").factory());
    sessionTimers.scheduleAtFixedRate(
        this::logProbeSummary,
        PROBE_SUMMARY_INTERVAL.toMillis(),
        PROBE_SUMMARY_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    sessionTimers.scheduleAtFixedRate(
        probeCapacities::reopenKeyframePacketScansWhenIdle,
        PROBE_CAPACITY_REFRESH_INTERVAL.toMillis(),
        PROBE_CAPACITY_REFRESH_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    // A fixed delay keeps a second between reports even after the monitor delayed one.
    sessionTimers.scheduleWithFixedDelay(
        this::reportJobAttemptProgress,
        JOB_ATTEMPT_PROGRESS_INTERVAL.toMillis(),
        JOB_ATTEMPT_PROGRESS_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    disconnected = new CompletableFuture<>();
    var observer = new WorkerResponseObserver(accepted, probeSession);
    responseObserver.set(observer);
    // Waiting for the channel to become ready lets the worker start before the server listens.
    requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .withWaitForReady()
            .establishWorkerSession(observer);
    sessionRequests.set(requests);
    send(registration());
    return accepted;
  }

  public boolean hasAcceptedSession() {
    var observer = responseObserver.get();
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
    capabilities.addProbeVersions(FfprobeExecutor.PROBE_VERSION);
    capabilities.addMeasurementVersions(FfprobeExecutor.MEASUREMENT_VERSION);
    capabilities.addAllBitstreamRewrites(BitstreamRewrites.declared());
    capabilities.addAllVideoCopyFeatures(PlannedCopies.declaredFeatures());
    engine.workerVerifiedEncoders().stream()
        .map(WorkerVideoEncoders::toProto)
        .forEach(capabilities::addVideoEncoders);
    configuration.sourceNamespaces().keySet().stream()
        .map(ProtoUuid::toProto)
        .forEach(capabilities::addSourceNamespaceIds);
    var granted = probeCapacities.granted();
    sentCapacities = Optional.of(granted);
    var registration =
        WorkerRegistration.newBuilder()
            .setWorker(identity())
            .setCapabilities(capabilities)
            .setAvailableSlots(configuration.availableSlots())
            .setBackgroundProbeCapacity(granted.backgroundProbeCapacity())
            .setPlaybackProbeCapacity(granted.playbackProbeCapacity())
            .setKeyframePacketScanCapacity(granted.keyframePacketScanCapacity())
            .setBackgroundProbeCapacityControl(granted.backgroundProbeCapacityControl());
    return EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build();
  }

  // Reads the capacities under the worker monitor that orders every message, so the last message
  // sent always carries the latest values, whichever thread changed them.
  private synchronized void sendProbeCapacityChange() {
    var granted = probeCapacities.granted();
    if (requests == null || sentCapacities.isEmpty() || sentCapacities.get().equals(granted)) {
      return;
    }

    sentCapacities = Optional.of(granted);
    tryReport(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeCapacityChanged(
                ProbeCapacityChanged.newBuilder()
                    .setBackgroundProbeCapacity(granted.backgroundProbeCapacity())
                    .setKeyframePacketScanCapacity(granted.keyframePacketScanCapacity()))
            .build());
  }

  private void logProbeSummary() {
    probeSummary
        .takeInterval()
        .ifPresent(
            interval ->
                log.info(
                    "Probes in the last {}: {} started, {} finished, failures {}, average time {}"
                        + " ms, {} running; {} playback slots, capacities {}; {} brakes",
                    PROBE_SUMMARY_INTERVAL,
                    interval.started(),
                    interval.finished(),
                    interval.failures(),
                    interval.averageDuration().toMillis(),
                    interval.running(),
                    configuration.availableSlots(),
                    probeCapacities.granted(),
                    probeCapacities.takeBrakeCount()));
  }

  private WorkerIdentity identity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(configuration.workerId()))
        .setBootId(toProto(configuration.bootId()))
        .build();
  }

  private synchronized void sendProbeResult(
      StreamObserver<EstablishWorkerSessionRequest> sessionRequests, ProbeAttemptReport report) {
    if (requests == null || requests != sessionRequests) {
      return;
    }

    report
        .keyframePositionsParts()
        .forEach(
            part ->
                send(
                    EstablishWorkerSessionRequest.newBuilder()
                        .setProbeKeyframePositionsPart(part)
                        .build()));
    report
        .streamMeasurementParts()
        .forEach(
            part ->
                send(
                    EstablishWorkerSessionRequest.newBuilder()
                        .setProbeStreamMeasurementPart(part)
                        .build()));
    send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(report.result()).build());
  }

  @SuppressWarnings("java:S3398") // Job lifecycle belongs to the worker, not its gRPC observer.
  private void startVariant(StartVariantCommand command, WorkerProbeSession session) {
    var job = command.getJob();
    if (!isRunnableHere(command)) {
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      return;
    }

    if (WorkerVariantJobMapper.namesAudioLayout(job)
        || WorkerVariantJobMapper.holdsTheLastFrameFromASeek(job)) {
      startReading(command, session);
      return;
    }

    startWith(command, new ChosenAudio.NotRead(), Optional.empty());
  }

  // The stop waits for FFmpeg to exit, so it runs without the monitor that uploads need.
  private void startWith(
      StartVariantCommand command, ChosenAudio chosenAudio, Optional<Duration> readPosition) {
    if (startAttempt(command, chosenAudio, readPosition) instanceof Orphaned(var producer)) {
      producer.stop();
    }
  }

  // A job that names its audio's output layout needs the source layout and sample facts (ADR 0050),
  // and a held encode from a seek may need to search for the video's last keyframe (ADR 0037).
  // Both read on the attempt's own thread, so the control stream keeps delivering commands while
  // they read: a stop for the attempt or the session's end interrupts the read. The attempt counts
  // against the probe capacities from the read's start, since the read loads the host as the
  // encode does. A start that its session's stream delivers after the session ended starts no
  // read: no cleanup of that session would end it.
  private synchronized void startReading(StartVariantCommand command, WorkerProbeSession session) {
    var jobAttemptId = fromProto(command.getJob().getJobAttemptId());
    if (probeSession != session || requests == null) {
      log.debug("Ignoring job attempt {}: its worker session has ended", jobAttemptId);
      return;
    }

    if (refuseWhenFull(command.getJob())) {
      return;
    }

    var searchProgress = new KeyframeSearchProgress();
    var reading =
        Thread.ofVirtual()
            .name("read-source-" + jobAttemptId)
            .unstarted(() -> readAndStart(command, searchProgress));
    pendingReads.put(jobAttemptId, new PendingRead(command.getJob(), reading, searchProgress));
    probeCapacities.jobAttemptStarted(jobAttemptId);
    reading.start();
  }

  private void readAndStart(StartVariantCommand command, KeyframeSearchProgress searchProgress) {
    var job = command.getJob();
    ChosenAudio chosenAudio = new ChosenAudio.NotRead();
    if (WorkerVariantJobMapper.namesAudioLayout(job)) {
      chosenAudio = readChosenAudio(job);
    }

    Optional<Duration> readPosition;
    try {
      readPosition = heldPictureReadPosition(job, chosenAudio, searchProgress);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return;
    } catch (NoKeyframeBeforeReadBackException e) {
      failAfterRead(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED, e);
      return;
    } catch (RuntimeException e) {
      failAfterRead(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED, e);
      return;
    }

    if (startAfterRead(command, chosenAudio, readPosition) instanceof Orphaned(var producer)) {
      producer.stop();
    }
  }

  // A job that the mapper refuses, or whose audio could not be read, is refused when the attempt
  // starts, as without a search.
  private Optional<Duration> heldPictureReadPosition(
      VariantJob job, ChosenAudio chosenAudio, KeyframeSearchProgress searchProgress)
      throws InterruptedException {
    if (!WorkerVariantJobMapper.holdsTheLastFrameFromASeek(job)
        || chosenAudio instanceof ChosenAudio.NotAnAudioStream
        || chosenAudio instanceof ChosenAudio.Unreadable) {
      return Optional.empty();
    }

    TranscodeRequest request;
    try {
      request = jobMapper.map(job, sourceAudioOf(chosenAudio));
    } catch (InvalidJobSpecificationException _) {
      return Optional.empty();
    }

    return engine.heldPictureReadPosition(request, searchProgress::add);
  }

  // A stop or the session's end that claimed the attempt during the read already settled it.
  private synchronized AttemptStart startAfterRead(
      StartVariantCommand command, ChosenAudio chosenAudio, Optional<Duration> readPosition) {
    var jobAttemptId = fromProto(command.getJob().getJobAttemptId());
    if (pendingReads.remove(jobAttemptId) == null) {
      return new Refused();
    }

    var start = startAttempt(command, chosenAudio, readPosition);
    if (start instanceof Refused) {
      probeCapacities.jobAttemptEnded(jobAttemptId);
    }

    return start;
  }

  private synchronized void failAfterRead(
      VariantJob job, JobAttemptFailure failure, RuntimeException cause) {
    var jobAttemptId = fromProto(job.getJobAttemptId());
    if (pendingReads.remove(jobAttemptId) == null) {
      return;
    }

    probeCapacities.jobAttemptEnded(jobAttemptId);
    log.error(
        "The search for the video's last keyframe of job attempt {} failed", jobAttemptId, cause);
    reportFailure(job, failure);
  }

  // ffprobe runs on a thread of its own, so the wait for it ends at the timeout even when the
  // process cannot exit, as one blocked on an unresponsive mount cannot; the interrupt then asks
  // the process to die.
  private ChosenAudio readChosenAudio(VariantJob job) {
    var read = new FutureTask<>(() -> readSourceAudio(job));
    Thread.ofVirtual().name("ffprobe-audio-" + fromProto(job.getJobAttemptId())).start(read);
    var timeout = configuration.audioStreamReadTimeout();
    try {
      return new ChosenAudio.Read(read.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ChosenAudio.Unreadable(e);
    } catch (ExecutionException e) {
      return chosenAudioAfter(e, job);
    } catch (TimeoutException e) {
      return new ChosenAudio.Unreadable(e);
    } finally {
      read.cancel(true);
    }
  }

  // The mapper refuses the job for the same reason. A source without the audio stream that the
  // job names makes the job invalid; a job that names none fails as it did before names existed.
  private static ChosenAudio chosenAudioAfter(ExecutionException failure, VariantJob job) {
    return switch (failure.getCause()) {
      case InvalidJobSpecificationException _ -> new ChosenAudio.NotRead();
      case FfprobeExecutor.NotAnAudioStreamException _
          when job.getDecision().getAudio().hasStreamIndex() ->
          new ChosenAudio.NotAnAudioStream();
      case null, default -> new ChosenAudio.Unreadable(failure);
    };
  }

  private SourceAudio readSourceAudio(VariantJob job)
      throws ExecutionException, InterruptedException {
    var output = WorkerVariantJobMapper.audioLayoutOf(job).orElseThrow();
    var source = sources.resolve(job.getSource());
    var specifier = WorkerVariantJobMapper.audioStreamSpecifierOf(job);
    var stream = ffprobe.readChosenAudio(source, specifier);
    var levels =
        mixesWithLoRoLevels(stream, output)
            ? Optional.of(ffprobe.readDownmixLevels(source, specifier))
            : Optional.<DownmixLevels>empty();
    return ChosenAudio.factsOf(stream, levels);
  }

  // ADR 0050: a stereo or mono downmix of AC-3 or E-AC-3 mixes its centre and surrounds at the
  // levels that the track signals. FFmpeg's decoder exports them only for a track with more than
  // two channels besides the low-frequency channel, which is the only kind that has either.
  private static boolean mixesWithLoRoLevels(ProbeStreamInfo stream, SpeakerLayout output) {
    var codec = stream.getCodec();
    if (!"ac3".equals(codec) && !"eac3".equals(codec) || output.channelCount() > 2) {
      return false;
    }

    var mainChannels =
        ChosenAudio.factsOf(stream, Optional.empty())
            .speakerLayout()
            .map(
                layout ->
                    layout.speakers().stream()
                        .filter(speaker -> speaker != Speaker.LFE && speaker != Speaker.LFE2)
                        .count())
            .orElse((long) stream.getChannels());
    return mainChannels > 2;
  }

  private synchronized AttemptStart startAttempt(
      StartVariantCommand command, ChosenAudio chosenAudio, Optional<Duration> readPosition) {
    var job = command.getJob();
    if (refuseWhenFull(job)) {
      return new Refused();
    }

    if (chosenAudio instanceof ChosenAudio.NotAnAudioStream) {
      log.warn(
          "Refusing job attempt {}: the stream that it names as its audio is not an audio stream",
          fromProto(job.getJobAttemptId()));
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      return new Refused();
    }

    if (chosenAudio instanceof ChosenAudio.Unreadable(var failure)) {
      log.error(
          "Failed to read the audio stream of job attempt {}",
          fromProto(job.getJobAttemptId()),
          failure);
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return new Refused();
    }

    TranscodeRequest request;
    Producer producer;
    try {
      var mapped = jobMapper.map(job, sourceAudioOf(chosenAudio));
      request =
          readPosition
              .map(position -> mapped.toBuilder().seekPosition(Optional.of(position)).build())
              .orElse(mapped);
      producer =
          engine.startProducer(
              request,
              (segment, cancellation) -> deliverToServer(job, segment, cancellation),
              memoryBudgetFor(WorkerVariantJobMapper.copiesVideo(job)));
    } catch (InvalidJobSpecificationException | UnadvertisedCodecFamilyException e) {
      log.warn("Refusing job attempt {}: {}", fromProto(job.getJobAttemptId()), e.getMessage());
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      return new Refused();
    } catch (RuntimeException e) {
      logStartupFailure(job, e);
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return new Refused();
    }

    var attempt = new ActiveAttempt(job, producer);
    var jobAttemptId = fromProto(job.getJobAttemptId());
    activeAttempts.put(jobAttemptId, attempt);
    probeCapacities.jobAttemptStarted(jobAttemptId);
    producer.outcome().whenComplete((_, _) -> probeCapacities.jobAttemptEnded(jobAttemptId));
    try {
      send(jobAttemptStarted(job.getJobAttemptId(), engine.encoderBackendOf(request)));
    } catch (RuntimeException e) {
      logStartupFailure(job, e);
      claim(attempt);
      reportFailure(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
      return new Orphaned(producer);
    }

    producer.outcome().thenAccept(outcome -> settleAttempt(attempt, outcome));
    return new Started();
  }

  private static Optional<SourceAudio> sourceAudioOf(ChosenAudio chosenAudio) {
    return chosenAudio instanceof ChosenAudio.Read(var facts)
        ? Optional.of(facts)
        : Optional.empty();
  }

  // Encodes share the slots' memory and copies their own, so a copy never waits for room that
  // only an encode can free.
  private SegmentMemoryBudget memoryBudgetFor(boolean copiesVideo) {
    if (copiesVideo) {
      return copyMemoryBudget;
    }

    return memoryBudget;
  }

  // Holds this monitor. Encodes take the advertised slots, and copies the places that the heap
  // holds. Only active attempts and audio reads hold either: the server freed a stopped attempt's
  // slot when it sent the stop, and the worker's memory budgets charge its producer until it has
  // released its segments. A refused copy is reported as a full copy cap, and a refused encode as
  // a startup failure.
  private boolean refuseWhenFull(VariantJob job) {
    var copiesVideo = WorkerVariantJobMapper.copiesVideo(job);
    var kind = "encodes";
    var capacity = configuration.availableSlots();
    var refusal = JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED;
    if (copiesVideo) {
      kind = "copies";
      capacity = maximumCopies;
      refusal = JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED;
    }

    if (placesInUse(copiesVideo) < capacity) {
      return false;
    }

    log.warn(
        "Refusing job attempt {}: the worker already runs the {} {} that it can",
        fromProto(job.getJobAttemptId()),
        capacity,
        kind);
    reportFailure(job, refusal);
    return true;
  }

  // The active attempts and audio reads that copy the video, or those that do not.
  private long placesInUse(boolean copiesVideo) {
    var attempts = activeAttempts.values().stream().map(ActiveAttempt::job);
    var reads = pendingReads.values().stream().map(PendingRead::job);
    return Stream.concat(attempts, reads)
        .filter(job -> WorkerVariantJobMapper.copiesVideo(job) == copiesVideo)
        .count();
  }

  private boolean isRunnableHere(StartVariantCommand command) {
    var job = command.getJob();
    return command.getTarget().equals(identity())
        && job.getDecision().getContainer() == ContainerFormat.CONTAINER_FORMAT_FMP4
        && hasUsableFrameRate(job)
        && hasEncoderPresetForVideoEncode(job)
        && startsAtAnAdvertisedMediaSegment(job);
  }

  // A job that copies the video ignores its preset.
  private static boolean hasEncoderPresetForVideoEncode(VariantJob job) {
    return !WorkerVariantJobMapper.encodesVideo(job)
        || WorkerVariantJobMapper.hasEncoderPreset(job);
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

    var frameRate = job.getExecution().getOutputFrameRate();
    return frameRate.getNumerator() != 0 && frameRate.getDenominator() != 0;
  }

  private static void logStartupFailure(VariantJob job, RuntimeException failure) {
    log.error(
        "Failed to start variant {} of stream session {}",
        job.getVariant().getVariantLabel(),
        fromProto(job.getStreamSessionId()),
        failure);
  }

  // The attempt's producer delivers each segment here and waits until the server accepts it.
  private void deliverToServer(
      VariantJob job, ProducedSegment segment, DeliveryCancellation cancellation) {
    if (segment instanceof ProducedSegment.Media media) {
      probeCapacities.mediaSegmentProduced(fromProto(job.getJobAttemptId()), media.timing());
    }

    try {
      uploadSegment(job, segment, cancellation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WorkerJobException("Interrupted while uploading " + segment, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new WorkerJobException("Upload of " + segment + " failed", e);
    }
  }

  private void uploadSegment(
      VariantJob job, ProducedSegment segment, DeliveryCancellation cancellation)
      throws InterruptedException, ExecutionException, TimeoutException {
    var upload =
        new SegmentUpload(
            configuration.uploadReadinessTimeout(), configuration.uploadAcknowledgementTimeout());
    var turn = uploadLimit.turn();
    cancellation.onCancel(
        () -> {
          turn.cancel();
          upload.cancel(new CancellationException("The job attempt stopped"));
        });
    // A segment that waits for its turn stays the producer's delivery in flight, so its bytes stay
    // charged to the memory budgets.
    try (var _ = turn.await(configuration.uploadAcknowledgementTimeout())) {
      sendSegment(openUpload(job, upload), upload, segment);
    }
  }

  private static void sendSegment(
      SegmentUploadMetadata.Builder metadataBuilder, SegmentUpload upload, ProducedSegment segment)
      throws InterruptedException, ExecutionException, TimeoutException {
    metadataBuilder
        .setSegmentName(segment.name())
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4)
        .setContentLengthBytes(segment.byteLength());
    if (segment instanceof ProducedSegment.Media media) {
      metadataBuilder
          .setMediaDuration(protoDuration(media.timing().mediaDuration()))
          .setProductionTime(protoDuration(media.timing().productionTime()));
    }

    var metadata = metadataBuilder.build();
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

  // The contract's Duration shares its simple name with java.time.Duration.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.google.protobuf.Duration protoDuration(Duration duration) {
    return com.google.protobuf.Duration.newBuilder()
        .setSeconds(duration.getSeconds())
        .setNanos(duration.getNano())
        .build();
  }

  private static void sendContent(SegmentUpload upload, ProducedSegment segment)
      throws InterruptedException, TimeoutException {
    var pending = ByteString.EMPTY;
    for (var part : segment.content()) {
      // The producer never changes a segment's bytes after delivering it, so wrapping them copies
      // nothing.
      pending =
          sendWholeDataMessages(upload, pending.concat(UnsafeByteOperations.unsafeWrap(part)));
    }

    if (!pending.isEmpty()) {
      upload.send(dataMessage(pending));
    }
  }

  // Sends every full-sized data message and returns the remainder.
  private static ByteString sendWholeDataMessages(SegmentUpload upload, ByteString content)
      throws InterruptedException, TimeoutException {
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

  // Reports how far each active attempt's output has reached since its previous report, so the
  // server sees progress inside a long merged segment before it closes. An attempt leaves the
  // active attempts under this monitor before its outcome is reported, so no report follows it.
  private synchronized void reportJobAttemptProgress() {
    pendingReads
        .values()
        .forEach(
            pending ->
                pending
                    .searchProgress()
                    .takeAdvancedBytesRead()
                    .ifPresent(
                        bytes ->
                            tryReport(
                                keyframeSearchProgress(pending.job().getJobAttemptId(), bytes))));
    activeAttempts
        .values()
        .forEach(
            attempt ->
                attempt
                    .producer()
                    .takeAdvancedOutputEnd()
                    .ifPresent(
                        end ->
                            tryReport(jobAttemptProgress(attempt.job().getJobAttemptId(), end))));
  }

  // A held encode's search for the video's last keyframe ends before its producer starts.
  private static EstablishWorkerSessionRequest keyframeSearchProgress(
      Uuid jobAttemptId, long bytesRead) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptProgress(
            JobAttemptProgress.newBuilder()
                .setJobAttemptId(jobAttemptId)
                .setKeyframeSearchBytesRead(bytesRead))
        .build();
  }

  private static EstablishWorkerSessionRequest jobAttemptProgress(
      Uuid jobAttemptId, Duration outputEnd) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptProgress(
            JobAttemptProgress.newBuilder()
                .setJobAttemptId(jobAttemptId)
                .setOutputEnd(protoDuration(outputEnd)))
        .build();
  }

  // Reports the producer's outcome unless a stop or the session's end has already claimed it.
  private synchronized void settleAttempt(ActiveAttempt attempt, AttemptOutcome outcome) {
    var job = attempt.job();
    if (!activeAttempts.remove(fromProto(job.getJobAttemptId()), attempt)) {
      return;
    }

    tryReport(reportOf(job, outcome));
  }

  private static EstablishWorkerSessionRequest reportOf(VariantJob job, AttemptOutcome outcome) {
    return switch (outcome) {
      case Completed(var droppedLateAudioSampleCount, var sourceEnd) ->
          completion(job, droppedLateAudioSampleCount, sourceEnd);
      case Failed(var reason, var detail) -> transcodeFailure(job, reason, detail);
      case Stopped _ -> jobAttemptStopped(job.getJobAttemptId());
    };
  }

  private static EstablishWorkerSessionRequest completion(
      VariantJob job, long droppedLateAudioSampleCount, SourceEnd sourceEnd) {
    if (droppedLateAudioSampleCount > 0) {
      log.info(
          "Variant {} of stream session {} dropped {} audio samples that arrived after their media"
              + " segment was delivered",
          job.getVariant().getVariantLabel(),
          fromProto(job.getStreamSessionId()),
          droppedLateAudioSampleCount);
    }

    return jobAttemptCompleted(job.getJobAttemptId(), sourceEnd);
  }

  private static EstablishWorkerSessionRequest transcodeFailure(
      VariantJob job, ProducerFailure reason, String detail) {
    log.warn(
        "Variant {} of stream session {} failed ({}): {}",
        job.getVariant().getVariantLabel(),
        fromProto(job.getStreamSessionId()),
        reason,
        detail);
    return jobAttemptFailed(job, jobAttemptFailureOf(reason));
  }

  // The server does not replace an attempt whose output differs from the job: the same decision on
  // the same encoder backend produces the same output. Nor one whose job names a stream that is not
  // an audio stream as its audio. Nor one whose output outgrew the segment cap: in a stream session
  // with merged segments every replacement repeats it, so the server falls back to an encode on the
  // plain grid instead.
  private static JobAttemptFailure jobAttemptFailureOf(ProducerFailure reason) {
    return switch (reason) {
      case OUTPUT_MISMATCH -> JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH;
      case NOT_AN_AUDIO_STREAM -> JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION;
      case SEGMENT_CAP_EXCEEDED -> JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED;
      case PROCESS_EXITED_WITH_ERROR,
          COPY_UNIT_TOO_LARGE,
          NO_MEDIA_SEGMENT,
          SEGMENT_NOT_ACCEPTED,
          TRUNCATED_OUTPUT,
          SKIPPED_SEGMENT_NUMBER,
          SHORT_VIDEO_SAMPLE,
          MALFORMED_OUTPUT,
          OUTPUT_UNREADABLE,
          ENCODER_STALLED,
          PROCESS_DID_NOT_EXIT,
          UNEXPECTED_ERROR ->
          JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED;
    };
  }

  // Claims the attempt so that its producer's own outcome is never reported, and finishes its stop
  // on its own thread: the stop waits for FFmpeg to exit, which must hold neither the control
  // stream nor the monitor that uploads need. close() waits for every stop in progress.
  @SuppressWarnings("java:S3398") // Job lifecycle belongs to the worker, not its gRPC observer.
  private synchronized void stopVariant(StopVariantCommand command) {
    if (!command.getTarget().equals(identity())) {
      return;
    }

    var attempt = activeAttempts.get(fromProto(command.getJobAttemptId()));
    if (attempt == null) {
      stopReading(command);
      return;
    }

    claim(attempt);
    var stop = new ClaimedStop(attempt, requests);
    var stopping =
        Thread.ofVirtual()
            .name("stop-" + fromProto(command.getJobAttemptId()))
            .unstarted(() -> finishStop(stop));
    stopThreads.add(stopping);
    stopping.start();
  }

  // An attempt that is still reading its audio stream has no producer, so its stop is settled at
  // once; the interrupt ends the read.
  private synchronized void stopReading(StopVariantCommand command) {
    var pending = pendingReads.remove(fromProto(command.getJobAttemptId()));
    if (pending == null) {
      return;
    }

    pending.thread().interrupt();
    probeCapacities.jobAttemptEnded(fromProto(command.getJobAttemptId()));
    tryReport(jobAttemptStopped(pending.job().getJobAttemptId()));
  }

  // The producer settles the stop unless it had already recorded another outcome, which the worker
  // then reports instead: a failure recorded before the stop stays a failure.
  private void finishStop(ClaimedStop stop) {
    try {
      var producer = stop.attempt().producer();
      producer.stop();
      reportClaimed(stop, producer.outcome().join());
    } catch (RuntimeException e) {
      log.error(
          "Stopping job attempt {} failed", fromProto(stop.attempt().job().getJobAttemptId()), e);
    } finally {
      releaseStop();
    }
  }

  private synchronized void releaseStop() {
    stopThreads.remove(Thread.currentThread());
  }

  private synchronized List<Thread> stopsInProgress() {
    return List.copyOf(stopThreads);
  }

  private void awaitStopsInProgress() {
    stopsInProgress().forEach(this::awaitStop);
  }

  private void awaitStop(Thread stopping) {
    try {
      stopping.join();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  // A stop that outlives its session reports nothing; the server learned of the end on disconnect.
  private synchronized void reportClaimed(ClaimedStop stop, AttemptOutcome outcome) {
    if (requests != stop.session()) {
      return;
    }

    tryReport(reportOf(stop.attempt().job(), outcome));
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

  private static EstablishWorkerSessionRequest jobAttemptStarted(
      Uuid jobAttemptId, Optional<EncoderBackend> encoderBackend) {
    var started = JobAttemptStarted.newBuilder().setJobAttemptId(jobAttemptId);
    encoderBackend.map(WorkerVideoEncoders::toProto).ifPresent(started::setEncoderBackend);
    return EstablishWorkerSessionRequest.newBuilder().setJobAttemptStarted(started).build();
  }

  private static EstablishWorkerSessionRequest jobAttemptCompleted(
      Uuid jobAttemptId, SourceEnd sourceEnd) {
    var completed = JobAttemptCompleted.newBuilder().setJobAttemptId(jobAttemptId);
    sourceEnd.streamEnds().stream()
        .map(TranscodeWorker::sourceStreamEnd)
        .forEach(completed::addStreamEnds);
    sourceEnd
        .endedEarly()
        .map(TranscodeWorker::sourceEndedEarly)
        .ifPresent(completed::setSourceEndedEarly);
    return EstablishWorkerSessionRequest.newBuilder().setJobAttemptCompleted(completed).build();
  }

  private static SourceStreamEnd sourceStreamEnd(SourceEnd.StreamEnd streamEnd) {
    return SourceStreamEnd.newBuilder()
        .setStreamIndex(streamEnd.streamIndex())
        .setEndTime(protoDuration(streamEnd.endTime()))
        .build();
  }

  private static SourceEndedEarly sourceEndedEarly(SourceEnd.EndedEarly endedEarly) {
    return SourceEndedEarly.newBuilder()
        .setLastSegmentWithSourceMedia(endedEarly.lastSegmentWithSourceMedia())
        .addAllStreamIndexesWithMediaAfterLastSegment(
            endedEarly.streamIndexesWithMediaAfterLastSegment())
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

  @SuppressWarnings("java:S3398") // Session lifecycle belongs to the worker, not its gRPC observer.
  private void endSession(WorkerProbeSession sessionProbes) {
    stopAll(claimSessionAttempts(sessionProbes));
  }

  @SuppressWarnings("java:S3398") // The fence shares the worker monitor with close() and start().
  private synchronized List<Producer> claimSessionAttempts(WorkerProbeSession sessionProbes) {
    if (probeSession != sessionProbes) {
      return List.of();
    }

    requests = null;
    workerSession = null;
    sessionProbes.shutdown();

    if (!activeAttempts.isEmpty()) {
      log.warn(
          "Worker session ended with {} abandoned job attempt(s): {}",
          activeAttempts.size(),
          activeAttempts.keySet());
    }

    return claimActiveAttempts();
  }

  // An attempt that is still reading its audio stream ends with the read, before any report.
  private synchronized List<Producer> claimActiveAttempts() {
    pendingReads.values().forEach(pending -> pending.thread().interrupt());
    pendingReads.keySet().forEach(probeCapacities::jobAttemptEnded);
    pendingReads.clear();
    var attempts = List.copyOf(activeAttempts.values());
    attempts.forEach(this::claim);
    return attempts.stream().map(ActiveAttempt::producer).toList();
  }

  // The producer records the stop while the worker still holds the attempt, so a delivery that the
  // worker refuses once the attempt is no longer active can only fail after the stop, which the
  // producer then never reports.
  private synchronized void claim(ActiveAttempt attempt) {
    attempt.producer().requestStop();
    activeAttempts.remove(fromProto(attempt.job().getJobAttemptId()));
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
    awaitStopsInProgress();
    // Probe completion needs the worker monitor, so join only after closeConnection releases it.
    closeConnection()
        .ifPresent(
            closed -> {
              stopAll(closed.attempts());
              awaitStopsInProgress();
              closed.probes().close();
            });
  }

  // Returns the closed session's probes and any attempt that started after the stops began.
  private synchronized Optional<ClosedSession> closeConnection() {
    responseObserver.set(null);

    if (channel == null) {
      return Optional.empty();
    }

    probeSession.shutdown();
    sessionTimers.shutdownNow();
    sessionTimers = null;
    var attempts = claimActiveAttempts();
    try {
      if (requests != null) {
        requests.onCompleted();
      }
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
        uploadLimit = new SegmentUploadLimit(workerSession.getSegmentUploadLimit());
        sessionAccepted = true;
        accepted.complete(workerSession);
        return;
      }

      if (response.hasStartVariant()) {
        startVariant(response.getStartVariant(), sessionProbes);
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

      sessionProbes.cancel(fromProto(command.getProbeAttemptId()), command.getReason());
    }

    @Override
    public void onError(Throwable throwable) {
      sessionAccepted = false;
      accepted.completeExceptionally(throwable);
      endSession(sessionProbes);
      sessionDisconnected.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      sessionAccepted = false;
      if (!accepted.isDone()) {
        accepted.completeExceptionally(new IllegalStateException("Worker session closed"));
      }
      endSession(sessionProbes);
      sessionDisconnected.complete(null);
    }
  }

  // One segment's upload call, which sends a message only when the call is ready for it. It fails
  // when the call is not ready within the readiness timeout, or when the server has not
  // acknowledged the segment within the acknowledgement timeout of the first message sent. Every
  // call method runs under this object's monitor, so a cancellation from the thread that stops the
  // attempt never interleaves with a send.
  private static final class SegmentUpload
      implements ClientResponseObserver<UploadSegmentRequest, UploadSegmentResponse> {

    private final CompletableFuture<UploadSegmentResponse> response = new CompletableFuture<>();
    private final Duration readinessTimeout;
    private final Duration acknowledgementTimeout;
    private ClientCallStreamObserver<UploadSegmentRequest> call;

    // Guarded by this monitor.
    private OptionalLong acknowledgementDeadline = OptionalLong.empty();

    private SegmentUpload(Duration readinessTimeout, Duration acknowledgementTimeout) {
      this.readinessTimeout = readinessTimeout;
      this.acknowledgementTimeout = acknowledgementTimeout;
    }

    @Override
    public synchronized void beforeStart(ClientCallStreamObserver<UploadSegmentRequest> call) {
      this.call = call;
      call.setOnReadyHandler(this::signalReadiness);
    }

    // Sends nothing once the server has answered or the upload was cancelled; that decides it.
    private synchronized void send(UploadSegmentRequest message)
        throws InterruptedException, TimeoutException {
      var readinessDeadline = System.nanoTime() + readinessTimeout.toNanos();
      while (!call.isReady() && !response.isDone()) {
        TimeUnit.NANOSECONDS.timedWait(this, nanosUntilNextDeadline(readinessDeadline));
      }

      if (response.isDone()) {
        return;
      }

      if (acknowledgementDeadline.isEmpty()) {
        acknowledgementDeadline =
            OptionalLong.of(System.nanoTime() + acknowledgementTimeout.toNanos());
      }

      call.onNext(message);
    }

    // Holds this monitor. The time left before the first of the readiness deadline and the
    // acknowledgement deadline passes.
    private long nanosUntilNextDeadline(long readinessDeadline) throws TimeoutException {
      var now = System.nanoTime();
      var untilReady = readinessDeadline - now;
      if (untilReady <= 0) {
        throw new TimeoutException(
            "The upload was not ready for its next message within " + readinessTimeout);
      }

      return Math.min(untilReady, nanosUntilAcknowledgementDeadline(now));
    }

    // Holds this monitor.
    private long nanosUntilAcknowledgementDeadline(long now) throws TimeoutException {
      if (acknowledgementDeadline.isEmpty()) {
        return Long.MAX_VALUE;
      }

      var untilAcknowledged = acknowledgementDeadline.getAsLong() - now;
      if (untilAcknowledged <= 0) {
        throw acknowledgementTimedOut();
      }

      return untilAcknowledged;
    }

    private TimeoutException acknowledgementTimedOut() {
      return new TimeoutException(
          "The server did not acknowledge the upload within "
              + acknowledgementTimeout
              + " of its first message");
    }

    private synchronized void signalReadiness() {
      notifyAll();
    }

    private synchronized void finish() {
      if (!response.isDone()) {
        call.onCompleted();
      }
    }

    private UploadSegmentResponse awaitAcknowledgement()
        throws InterruptedException, ExecutionException, TimeoutException {
      try {
        return response.get(nanosUntilAcknowledgementDeadline(), TimeUnit.NANOSECONDS);
      } catch (TimeoutException _) {
        throw acknowledgementTimedOut();
      }
    }

    private synchronized long nanosUntilAcknowledgementDeadline() throws TimeoutException {
      return nanosUntilAcknowledgementDeadline(System.nanoTime());
    }

    // A stop before the call opens has no call to cancel; the producer then never sends on it.
    private synchronized void cancel(Throwable failure) {
      response.completeExceptionally(failure);
      if (call != null) {
        call.cancel("Segment upload failed", failure);
      }

      notifyAll();
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

  private record ClosedSession(WorkerProbeSession probes, List<Producer> attempts) {}

  private record ActiveAttempt(VariantJob job, Producer producer) {}

  private record ClaimedStop(
      ActiveAttempt attempt, StreamObserver<EstablishWorkerSessionRequest> session) {}

  /**
   * An attempt whose thread reads the job's audio stream, or searches the source for the video's
   * last keyframe, before the attempt starts FFmpeg.
   */
  private record PendingRead(
      VariantJob job, Thread thread, KeyframeSearchProgress searchProgress) {}

  private sealed interface AttemptStart {}

  private record Started() implements AttemptStart {}

  private record Refused() implements AttemptStart {}

  // The producer started, but the worker could not report the start and must stop it.
  private record Orphaned(Producer producer) implements AttemptStart {}
}
