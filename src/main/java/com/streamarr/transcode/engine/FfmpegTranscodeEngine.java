package com.streamarr.transcode.engine;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FfmpegTranscodeEngine {

  // How long a stop waits for FFmpeg to exit after asking it to quit.
  private static final Duration STOP_GRACE_PERIOD = Duration.ofSeconds(5);

  /** How long FFmpeg may write nothing while its producer reads its output, unless configured. */
  public static final Duration DEFAULT_ENCODER_STALL_TIMEOUT = Duration.ofSeconds(30);

  private final FfmpegCommandBuilder commandBuilder;
  private final TranscodeCapabilityService capabilityService;
  private final ProcessLauncher launcher;
  private final Duration encoderStallTimeout;

  public FfmpegTranscodeEngine(
      FfmpegCommandBuilder commandBuilder, TranscodeCapabilityService capabilityService) {
    this(
        commandBuilder,
        capabilityService,
        new ProcessBuilderLauncher(),
        DEFAULT_ENCODER_STALL_TIMEOUT);
  }

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

  /**
   * Starts FFmpeg for the job attempt and a producer that delivers its initialization segment and
   * media segments to the sink.
   *
   * @throws TranscodeException when FFmpeg is unavailable or cannot be started
   */
  public Producer startProducer(TranscodeRequest request, SegmentSink sink) {
    requireAvailableFfmpeg();
    var job = TranscodeJob.builder().request(request).videoEncoder(resolveEncoder(request)).build();
    var command = commandBuilder.buildCommand(job);
    log.debug(
        "FFmpeg command for job attempt {}: {}", request.attemptId(), String.join(" ", command));
    var producer =
        Producer.builder()
            .launcher(launcher)
            .command(command)
            .jobAttemptId(request.attemptId())
            .periodSeconds(request.targetSegmentDuration())
            .startSequenceNumber(request.startSequenceNumber())
            .gracePeriod(STOP_GRACE_PERIOD)
            .stallTimeout(encoderStallTimeout)
            .sink(sink)
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
    if (!request.transcodeDecision().transcodeMode().encodesVideo()) {
      return "copy";
    }

    return capabilityService.resolveEncoder(request.transcodeDecision().videoCodecFamily());
  }

  public static class FfmpegTranscodeEngineBuilder {
    private Duration encoderStallTimeout = DEFAULT_ENCODER_STALL_TIMEOUT;
  }
}
