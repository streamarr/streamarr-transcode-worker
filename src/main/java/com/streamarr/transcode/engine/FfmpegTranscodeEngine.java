package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FfmpegTranscodeEngine {

  // How long FFmpeg may take to exit once its output ends and its last segment is accepted, once a
  // stop asks it to quit, and after the producer asks it to terminate, before the producer ends it.
  private static final Duration GRACE_PERIOD = Duration.ofSeconds(5);

  /** How long FFmpeg may write nothing while its producer reads its output, unless configured. */
  public static final Duration DEFAULT_ENCODER_STALL_TIMEOUT = Duration.ofSeconds(30);

  private final FfmpegCommandBuilder commandBuilder;
  private final TranscodeCapabilityService capabilityService;
  private final ProcessLauncher launcher;
  private final Duration encoderStallTimeout;
  private final ProducerPauses producerPauses = new ProducerPauses();

  /**
   * @param encoderStallTimeout how long FFmpeg may write nothing to its standard output while its
   *     producer reads it before the producer fails the attempt
   */
  @Builder
  private FfmpegTranscodeEngine(
      @NonNull FfmpegCommandBuilder commandBuilder,
      @NonNull TranscodeCapabilityService capabilityService,
      @NonNull ProcessLauncher launcher,
      @NonNull Duration encoderStallTimeout) {
    this.commandBuilder = commandBuilder;
    this.capabilityService = capabilityService;
    this.launcher = launcher;
    this.encoderStallTimeout = encoderStallTimeout;
  }

  /** The pauses and holds of every producer that this engine started. */
  public ProducerPauses producerPauses() {
    return producerPauses;
  }

  /**
   * Starts FFmpeg for the job attempt and a producer that delivers its initialization segment and
   * media segments to the sink, holding FFmpeg's output within the worker's memory budget.
   *
   * @throws TranscodeException when FFmpeg is unavailable or cannot be started
   * @throws UnadvertisedCodecFamilyException when the job attempt encodes a codec family that the
   *     worker does not advertise
   */
  public Producer startProducer(
      TranscodeRequest request, SegmentSink sink, SegmentMemoryBudget memoryBudget) {
    return startProducer(request, encoderBackendOf(request), sink, memoryBudget);
  }

  /**
   * Starts FFmpeg for the job attempt on the encoder backend that {@link #encoderBackendOf}
   * resolved for it, so that a hardware encoder withdrawn meanwhile cannot change the encoder that
   * the attempt reports.
   */
  public Producer startProducer(
      TranscodeRequest request,
      Optional<EncoderBackend> encoderBackend,
      SegmentSink sink,
      SegmentMemoryBudget memoryBudget) {
    requireAvailableFfmpeg();
    var videoEncoder = encoderBackend.map(EncoderBackend::encoder).orElse("copy");
    var job = TranscodeJob.builder().request(request).videoEncoder(videoEncoder).build();
    var pipeline = commandBuilder.buildPipeline(job);
    log.debug("FFmpeg command for job attempt {}: {}", request.attemptId(), pipeline.commandLine());
    var producer =
        Producer.builder()
            .launcher(launcher)
            .pipeline(pipeline)
            .jobAttemptId(request.attemptId())
            .intervals(request.segmentIntervals())
            .startSequenceNumber(request.startSequenceNumber())
            .mediaSegmentCount(request.mediaSegmentCount())
            .copiesAudio(request.transcodeDecision().audioDecision().mode() == AudioMode.COPY)
            .gracePeriod(GRACE_PERIOD)
            .stallTimeout(encoderStallTimeout)
            .encodedFrameRate(encodedFrameRateOf(request))
            .videoDecision(request.transcodeDecision().videoDecision())
            .requiresSoundTrack(namesItsAudioStream(request))
            .paddedAudioEnd(paddedAudioEndOf(request))
            .memoryBudget(memoryBudget)
            .pauses(producerPauses)
            .sink(sink)
            .clock(MonotonicClock.system())
            .start();
    log.info(
        "Started transcode for session {} variant {} job attempt {} (encoder: {}, PID: {})",
        request.sessionId(),
        request.variantLabel(),
        request.attemptId(),
        job.videoEncoder(),
        producer.pid());
    return producer;
  }

  /**
   * Where a held encode that starts after segment 0 reads from, so that it decodes the video's last
   * picture even when its seek position lies after the video's last keyframe (ADR 0037, early
   * ends). Empty when the attempt keeps its own seek position: a keyframe presents between that
   * position and the first segment's start, or the attempt is no held encode with a seek position.
   *
   * @param bytesRead takes the bytes of each packet that the search reads, as it reads them
   * @throws NoKeyframeBeforeReadBackException when the video has no keyframe before the seek
   *     position
   * @throws TranscodeException when a scan's FFmpeg cannot start or exits with an error
   */
  public Optional<Duration> heldPictureReadPosition(
      TranscodeRequest request, LongConsumer bytesRead) throws InterruptedException {
    var seekPosition = request.seekPosition();
    if (seekPosition.isEmpty() || request.startSequenceNumber() == 0 || !holdsLastFrame(request)) {
      return Optional.empty();
    }

    var firstSegmentStart =
        Duration.ofNanos(
            request
                .segmentIntervals()
                .startOf(request.startSequenceNumber(), Duration.ofSeconds(1).toNanos()));
    var scan =
        KeyframeScan.builder()
            .commandBuilder(commandBuilder)
            .launcher(launcher)
            .request(request)
            .bytesRead(bytesRead)
            .build();
    return LastKeyframeSearch.readPosition(seekPosition.get(), firstSegmentStart, scan);
  }

  private static boolean holdsLastFrame(TranscodeRequest request) {
    return request.transcodeDecision().videoDecision() instanceof VideoDecision.Encode encode
        && encode.holdsLastFrameToAdvertisedEnd();
  }

  private void requireAvailableFfmpeg() {
    if (!capabilityService.isFfmpegAvailable()) {
      throw new TranscodeException(
          "FFmpeg is unavailable: " + capabilityService.getUnavailableReason());
    }
  }

  /**
   * The encoder backend that the job attempt runs; empty when it copies the video.
   *
   * @throws TranscodeException when FFmpeg is unavailable
   * @throws UnadvertisedCodecFamilyException when the job attempt encodes a codec family that the
   *     worker does not advertise
   */
  public Optional<EncoderBackend> encoderBackendOf(TranscodeRequest request) {
    requireAvailableFfmpeg();
    if (!request.transcodeDecision().transcodeMode().encodesVideo()) {
      return Optional.empty();
    }

    var codecFamily = request.transcodeDecision().videoCodecFamily();
    var resolved =
        asksForTheSoftwareEncoder(request)
            ? capabilityService.resolveSoftwareEncoder(codecFamily)
            : capabilityService.resolveEncoder(codecFamily);
    return Optional.of(
        resolved.orElseThrow(() -> new UnadvertisedCodecFamilyException(codecFamily)));
  }

  private static boolean asksForTheSoftwareEncoder(TranscodeRequest request) {
    return request.transcodeDecision().videoDecision() instanceof VideoDecision.Encode encode
        && encode.softwareEncoder();
  }

  /** The encoder backends that this engine runs, one for each codec family it can encode. */
  public List<WorkerVerifiedEncoder> workerVerifiedEncoders() {
    return capabilityService.workerVerifiedEncoders();
  }

  /** The software encoder of each codec family that a job can ask for (ADR 0044). */
  public List<WorkerVerifiedEncoder> softwareEncoders() {
    return capabilityService.softwareEncoders();
  }

  /**
   * Stops offering a hardware encoder that failed during a stream. Returns whether the encoders
   * that this engine runs changed.
   */
  public boolean withdraw(EncoderBackend failed) {
    return capabilityService.withdraw(failed);
  }

  /** Whether a withdrawn hardware encoder waits for its test encode to pass again. */
  public boolean hasWithdrawnEncoders() {
    return capabilityService.hasWithdrawnEncoders();
  }

  /**
   * Offers again each withdrawn hardware encoder whose test encode passes. Returns whether the
   * encoders that this engine runs changed.
   */
  public boolean recheckWithdrawnEncoders() {
    return capabilityService.recheckWithdrawnEncoders();
  }

  private static boolean namesItsAudioStream(TranscodeRequest request) {
    var audio = request.transcodeDecision().audioDecision();
    return audio.mode() != AudioMode.NONE && audio.streamIndex().isPresent();
  }

  private static Optional<Duration> paddedAudioEndOf(TranscodeRequest request) {
    var audio = request.transcodeDecision().audioDecision();
    if (!audio.padsWithSilenceToAdvertisedEnd()) {
      return Optional.empty();
    }

    return request.advertisedEnd();
  }

  // An encode forces the probed frame rate on its output; a copy keeps the source's durations.
  private static Optional<OutputFrameRate> encodedFrameRateOf(TranscodeRequest request) {
    if (!request.transcodeDecision().transcodeMode().encodesVideo()) {
      return Optional.empty();
    }

    return request.outputFrameRate();
  }
}
