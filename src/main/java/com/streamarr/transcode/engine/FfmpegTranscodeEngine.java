package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FfmpegTranscodeEngine {

  /** How long a stop waits for FFmpeg to exit after asking it to quit. */
  private static final Duration STOP_GRACE_PERIOD = Duration.ofSeconds(5);

  private final FfmpegCommandBuilder commandBuilder;
  private final FfmpegProcessManager processManager;
  private final TranscodeCapabilityService capabilityService;
  private final ProcessLauncher launcher;

  public FfmpegTranscodeEngine(
      FfmpegCommandBuilder commandBuilder,
      FfmpegProcessManager processManager,
      TranscodeCapabilityService capabilityService) {
    this(commandBuilder, processManager, capabilityService, new ProcessBuilderLauncher());
  }

  @Builder
  private FfmpegTranscodeEngine(
      @NonNull FfmpegCommandBuilder commandBuilder,
      @NonNull FfmpegProcessManager processManager,
      @NonNull TranscodeCapabilityService capabilityService,
      @NonNull ProcessLauncher launcher) {
    this.commandBuilder = commandBuilder;
    this.processManager = processManager;
    this.capabilityService = capabilityService;
    this.launcher = launcher;
  }

  /**
   * Starts FFmpeg for the job attempt and a producer that delivers its initialization segment and
   * media segments to the sink.
   *
   * @throws TranscodeException when FFmpeg is unavailable or cannot be started
   */
  public Producer startProducer(TranscodeRequest request, SegmentSink sink) {
    requireAvailableFfmpeg();
    var job = TranscodeJob.builder().request(request).videoEncoder(resolveEncoder(request)).build();
    return Producer.builder()
        .launcher(launcher)
        .command(commandBuilder.buildCommand(job))
        .jobAttemptId(request.attemptId())
        .periodSeconds(request.targetSegmentDuration())
        .startSequenceNumber(request.startSequenceNumber())
        .gracePeriod(STOP_GRACE_PERIOD)
        .sink(sink)
        .start();
  }

  public TranscodeHandle start(TranscodeRequest request, Path outputDirectory) {
    requireAvailableFfmpeg();
    var job =
        TranscodeJob.builder()
            .request(request)
            .videoEncoder(resolveEncoder(request))
            .outputDir(outputDirectory)
            .build();
    var command = commandBuilder.buildCommand(job);

    log.debug("FFmpeg command for session {}: {}", request.sessionId(), String.join(" ", command));

    var process =
        processManager.startProcess(
            request.sessionId(), request.variantLabel(), command, job.outputDir());

    log.info(
        "Started transcode for session {} variant {} (encoder: {}, PID: {})",
        request.sessionId(),
        request.variantLabel(),
        job.videoEncoder(),
        process.pid());

    return new TranscodeHandle(
        process.pid(), request.attemptId(), TranscodeStatus.ACTIVE, request.startSequenceNumber());
  }

  public void stop(UUID sessionId) {
    processManager.stopProcess(sessionId);
  }

  public void stop(UUID sessionId, String variantLabel) {
    processManager.stopProcess(sessionId, variantLabel);
  }

  public boolean isRunning(UUID sessionId, String variantLabel) {
    return processManager.isRunning(sessionId, variantLabel);
  }

  public boolean isHealthy() {
    return capabilityService.isFfmpegAvailable();
  }

  private void requireAvailableFfmpeg() {
    if (!capabilityService.isFfmpegAvailable()) {
      throw new TranscodeException(
          "FFmpeg is unavailable: " + capabilityService.getUnavailableReason());
    }
  }

  private String resolveEncoder(TranscodeRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      return "copy";
    }

    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }
}
