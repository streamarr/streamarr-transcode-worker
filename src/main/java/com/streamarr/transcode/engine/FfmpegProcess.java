package com.streamarr.transcode.engine;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One job attempt's FFmpeg. The producer asks it to quit or to terminate and destroys it once it
 * outlives the grace period, because a hung FFmpeg can ignore termination. Its error output is
 * drained throughout, so that a non-zero exit can be explained.
 */
sealed interface FfmpegProcess permits SingleFfmpegProcess, PipedFfmpegProcesses {

  /**
   * Starts FFmpeg for the job attempt.
   *
   * @param gracePeriod how long FFmpeg may take to exit once its output ends and the sink has
   *     accepted its last segment, after a stop asks it to quit, and after the producer asks it to
   *     terminate
   * @throws TranscodeException when FFmpeg cannot be started
   */
  @Builder(buildMethodName = "start")
  private static FfmpegProcess launch(
      @NonNull ProcessLauncher launcher,
      @NonNull FfmpegPipeline pipeline,
      @NonNull UUID jobAttemptId,
      @NonNull Duration gracePeriod) {
    ProcessStarter starter = command -> started(launcher, command, jobAttemptId);
    return switch (pipeline) {
      case FfmpegPipeline.SingleProcess(var command) ->
          new SingleFfmpegProcess(starter.start(command), gracePeriod);
      case FfmpegPipeline.RpuMovingPipe pipe ->
          PipedFfmpegProcesses.start(starter, pipe, gracePeriod);
    };
  }

  private static Process started(
      ProcessLauncher launcher, List<String> command, UUID jobAttemptId) {
    try {
      return launcher.launch(command, jobAttemptId);
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }
  }

  /** Starts one FFmpeg process of the attempt; throws {@link TranscodeException} when it cannot. */
  @FunctionalInterface
  interface ProcessStarter {
    Process start(List<String> command);
  }

  long pid();

  Duration gracePeriod();

  /** The fragmented MP4 that the attempt writes. */
  InputStream output();

  void requestQuit();

  void requestTermination();

  /** Waits for FFmpeg to exit; an interrupt of the waiting thread destroys FFmpeg at once. */
  void awaitExitEndingItOnInterrupt();

  /** Waits for FFmpeg to exit within the grace period, and destroys it when it has not. */
  default void awaitExitWithinGracePeriod() {
    if (!tryAwaitExitWithinGracePeriod()) {
      endForcibly();
    }
  }

  /** False when FFmpeg has not exited within the grace period, or an interrupt ended the wait. */
  boolean tryAwaitExitWithinGracePeriod();

  /** Destroys FFmpeg and returns once it has exited. */
  void endForcibly();

  /**
   * The outcome that the exited FFmpeg's exit status decides. A non-zero exit explains whatever the
   * output lacks, so it takes precedence over the outcome of a clean exit.
   */
  AttemptOutcome outcomeAtExit(AttemptOutcome outcomeOnCleanExit);

  /** Reads FFmpeg's output to the end, which lets FFmpeg flush its last fragment and exit. */
  void discardRemainingOutput();

  void stopDrainingErrorOutput();
}
