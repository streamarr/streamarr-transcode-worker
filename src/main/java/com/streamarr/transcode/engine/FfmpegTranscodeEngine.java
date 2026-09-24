package com.streamarr.transcode.engine;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

public class FfmpegTranscodeEngine {

  /** How long a stop waits for FFmpeg to exit after asking it to quit. */
  private static final Duration STOP_GRACE_PERIOD = Duration.ofSeconds(5);

  private final FfmpegCommandBuilder commandBuilder;
  private final TranscodeCapabilityService capabilityService;
  private final ProcessLauncher launcher;

  public FfmpegTranscodeEngine(
      FfmpegCommandBuilder commandBuilder, TranscodeCapabilityService capabilityService) {
    this(commandBuilder, capabilityService, new ProcessBuilderLauncher());
  }

  @Builder
  private FfmpegTranscodeEngine(
      @NonNull FfmpegCommandBuilder commandBuilder,
      @NonNull TranscodeCapabilityService capabilityService,
      @NonNull ProcessLauncher launcher) {
    this.commandBuilder = commandBuilder;
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
