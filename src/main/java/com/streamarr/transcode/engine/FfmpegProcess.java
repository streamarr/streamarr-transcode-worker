package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;

/**
 * One job attempt's FFmpeg process. The producer asks it to quit or to terminate and destroys it
 * once it outlives the grace period, because a hung FFmpeg can ignore termination. Its error output
 * is drained throughout, so that a non-zero exit can be explained.
 */
final class FfmpegProcess {

  private static final Duration ERROR_OUTPUT_WAIT = Duration.ofSeconds(1);
  private static final int ERROR_OUTPUT_DETAIL_LIMIT = 2000;

  private final Process process;
  private final StderrDrainer errorOutput;
  private final Duration gracePeriod;

  private FfmpegProcess(Process process, Duration gracePeriod) {
    this.process = process;
    this.errorOutput = new StderrDrainer(process.getErrorStream());
    this.gracePeriod = gracePeriod;
  }

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
      @NonNull List<String> command,
      @NonNull UUID jobAttemptId,
      @NonNull Duration gracePeriod) {
    Process process;
    try {
      process = launcher.launch(command, jobAttemptId);
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }

    return new FfmpegProcess(process, gracePeriod);
  }

  long pid() {
    return process.pid();
  }

  Duration gracePeriod() {
    return gracePeriod;
  }

  InputStream output() {
    return process.getInputStream();
  }

  void requestQuit() {
    try {
      var input = process.getOutputStream();
      input.write('q');
      input.flush();
    } catch (IOException _) {
      // FFmpeg has already closed its input; the exit wait observes it ending.
    }
  }

  void requestTermination() {
    process.destroy();
  }

  /** Waits for FFmpeg to exit; an interrupt of the waiting thread destroys FFmpeg at once. */
  void awaitExitEndingItOnInterrupt() {
    try {
      process.waitFor();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** Waits for FFmpeg to exit within the grace period, and destroys it when it has not. */
  void awaitExitWithinGracePeriod() {
    if (!tryAwaitExitWithinGracePeriod()) {
      endForcibly();
    }
  }

  /** False when FFmpeg has not exited within the grace period, or an interrupt ended the wait. */
  boolean tryAwaitExitWithinGracePeriod() {
    try {
      return process.waitFor(gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Destroys FFmpeg and returns once it has exited. */
  void endForcibly() {
    process.destroyForcibly();
    process.onExit().join();
  }

  /**
   * The outcome that the exited FFmpeg's exit status decides. A non-zero exit explains whatever the
   * output lacks, so it takes precedence over the outcome of a clean exit.
   */
  AttemptOutcome outcomeAtExit(AttemptOutcome outcomeOnCleanExit) {
    var exitCode = process.onExit().join().exitValue();
    if (exitCode != 0) {
      return new Failed(ProducerFailure.PROCESS_EXITED_WITH_ERROR, exitDetail(exitCode));
    }

    return outcomeOnCleanExit;
  }

  // FFmpeg reports why it failed at the end of its error output.
  private String exitDetail(int exitCode) {
    var recentErrorOutput = String.join("\n", errorOutput.awaitRecentOutput(ERROR_OUTPUT_WAIT));
    var tailStart = Math.max(0, recentErrorOutput.length() - ERROR_OUTPUT_DETAIL_LIMIT);
    return "FFmpeg exited with exit code "
        + exitCode
        + ": "
        + recentErrorOutput.substring(tailStart);
  }

  /** Reads FFmpeg's output to the end, which lets FFmpeg flush its last fragment and exit. */
  void discardRemainingOutput() {
    try {
      process.getInputStream().transferTo(OutputStream.nullOutputStream());
    } catch (IOException _) {
      // Nothing read after the attempt's outcome was decided matters.
    }
  }

  void stopDrainingErrorOutput() {
    errorOutput.close();
  }
}
