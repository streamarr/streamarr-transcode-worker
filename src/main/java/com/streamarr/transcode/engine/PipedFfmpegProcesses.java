package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.FfmpegProcess.ProcessStarter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A job attempt's FFmpeg that runs as two processes: one copies the source into MPEG-TS, and one
 * encodes that copy, which a virtual thread passes from the first to the second through {@link
 * TransportStreamRpuMover}. The encoding FFmpeg writes the attempt's output. A stop asks the
 * copying FFmpeg to quit, and the encoding FFmpeg then finishes at the end of its input;
 * termination and destruction end both.
 */
final class PipedFfmpegProcesses implements FfmpegProcess {

  private static final String COPYING_FFMPEG = "The FFmpeg that copies the source";

  private final Process copy;
  private final Process encode;
  private final StderrDrainer copyErrorOutput;
  private final StderrDrainer encodeErrorOutput;
  private final Duration gracePeriod;
  private final AtomicReference<Optional<IOException>> transferFailure =
      new AtomicReference<>(Optional.empty());
  private final Thread transfer;

  private PipedFfmpegProcesses(Process copy, Process encode, Duration gracePeriod) {
    this.copy = copy;
    this.encode = encode;
    this.copyErrorOutput = new StderrDrainer(copy.getErrorStream());
    this.encodeErrorOutput = new StderrDrainer(encode.getErrorStream());
    this.gracePeriod = gracePeriod;
    this.transfer = Thread.ofVirtual().name("ffmpeg-copy-transfer").start(this::transferCopy);
  }

  /**
   * Starts the copying FFmpeg and then the encoding FFmpeg, and destroys the copying one when the
   * encoding one cannot be started.
   */
  static PipedFfmpegProcesses start(
      ProcessStarter starter, FfmpegPipeline.TransportStreamPipe pipe, Duration gracePeriod) {
    var copy = starter.start(pipe.copyCommand());
    try {
      return new PipedFfmpegProcesses(copy, starter.start(pipe.encodeCommand()), gracePeriod);
    } catch (RuntimeException e) {
      copy.destroyForcibly();
      throw e;
    }
  }

  // The copy ends when the copying FFmpeg exits, and the end of the encoding FFmpeg's input lets it
  // finish. Once the encoding FFmpeg stops reading, the copying FFmpeg could only block on a full
  // pipe, so it is asked to terminate.
  private void transferCopy() {
    try (InputStream copied = copy.getInputStream();
        OutputStream encoded = encode.getOutputStream()) {
      TransportStreamRpuMover.copy(copied, encoded);
    } catch (IOException e) {
      transferFailure.set(Optional.of(e));
      copy.destroy();
    }
  }

  @Override
  public long pid() {
    return encode.pid();
  }

  @Override
  public Duration gracePeriod() {
    return gracePeriod;
  }

  @Override
  public InputStream output() {
    return encode.getInputStream();
  }

  // The encoding FFmpeg reads media from its standard input, so only the copying FFmpeg takes q.
  @Override
  public void requestQuit() {
    try {
      var input = copy.getOutputStream();
      input.write('q');
      input.flush();
    } catch (IOException _) {
      // The copying FFmpeg has already closed its input; the exit wait observes it ending.
    }
  }

  @Override
  public void requestTermination() {
    copy.destroy();
    encode.destroy();
  }

  @Override
  public void awaitExitEndingItOnInterrupt() {
    try {
      encode.waitFor();
      copy.waitFor();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      copy.destroyForcibly();
      encode.destroyForcibly();
    }
  }

  // Both FFmpegs and the transfer between them end within one grace period.
  @Override
  public boolean tryAwaitExitWithinGracePeriod() {
    var deadline = System.nanoTime() + gracePeriod.toNanos();
    try {
      return encode.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)
          && copy.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)
          && transfer.join(Duration.ofNanos(remainingNanos(deadline)));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static long remainingNanos(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  @Override
  public void endForcibly() {
    copy.destroyForcibly();
    encode.destroyForcibly();
    copy.onExit().join();
    encode.onExit().join();
  }

  /**
   * The encoding FFmpeg's exit decides first, as a single FFmpeg's would. A copy that did not reach
   * the encoding FFmpeg whole, or a copying FFmpeg that exited with an error, fails an attempt
   * whose encoding FFmpeg exited cleanly, since its output then lacks the rest of the source.
   */
  @Override
  public AttemptOutcome outcomeAtExit(AttemptOutcome outcomeOnCleanExit) {
    var encodeExitCode = encode.onExit().join().exitValue();
    if (encodeExitCode != 0) {
      return new Failed(
          ProducerFailure.PROCESS_EXITED_WITH_ERROR,
          encodeErrorOutput.exitDetail("FFmpeg", encodeExitCode));
    }

    var failedTransfer = transferFailure.get();
    if (failedTransfer.isPresent()) {
      return new Failed(
          ProducerFailure.OUTPUT_UNREADABLE,
          "The copy of the source did not reach the FFmpeg that encodes it: "
              + failedTransfer.orElseThrow());
    }

    var copyExitCode = copy.onExit().join().exitValue();
    if (copyExitCode != 0) {
      return new Failed(
          ProducerFailure.PROCESS_EXITED_WITH_ERROR,
          copyErrorOutput.exitDetail(COPYING_FFMPEG, copyExitCode));
    }

    return outcomeOnCleanExit;
  }

  @Override
  public void discardRemainingOutput() {
    try {
      encode.getInputStream().transferTo(OutputStream.nullOutputStream());
    } catch (IOException _) {
      // Nothing read after the attempt's outcome was decided matters.
    }
  }

  @Override
  public void stopDrainingErrorOutput() {
    copyErrorOutput.close();
    encodeErrorOutput.close();
  }
}
