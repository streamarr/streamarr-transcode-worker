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
import lombok.extern.slf4j.Slf4j;

/**
 * A job attempt's FFmpeg that runs as two processes: one copies the source into fragmented MP4, and
 * one encodes that copy, which a virtual thread passes from the first to the second through {@link
 * FragmentedMp4RpuMover}. The encoding FFmpeg writes the attempt's output. A stop asks the copying
 * FFmpeg to quit, and the encoding FFmpeg then finishes at the end of its input; termination and
 * destruction end both.
 */
@Slf4j
final class PipedFfmpegProcesses implements FfmpegProcess {

  private static final String COPYING_FFMPEG = "The FFmpeg that copies the source";

  private final Process copy;
  private final Process encode;
  private final StderrDrainer copyErrorOutput;
  private final StderrDrainer encodeErrorOutput;
  private final Duration gracePeriod;
  private final RpuMove rpuMove;
  private final AtomicReference<Optional<IOException>> transferFailure =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<FragmentedMp4Exception>> refusedCopy =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<Throwable>> unexpectedTransferFailure =
      new AtomicReference<>(Optional.empty());
  private final Thread transfer;

  private PipedFfmpegProcesses(
      Process copy, Process encode, RpuMove rpuMove, Duration gracePeriod) {
    this.copy = copy;
    this.encode = encode;
    this.rpuMove = rpuMove;
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
      ProcessStarter starter, FfmpegPipeline.RpuMovingPipe pipe, Duration gracePeriod) {
    var copy = starter.start(pipe.copyCommand());
    try {
      return new PipedFfmpegProcesses(
          copy, starter.start(pipe.encodeCommand()), pipe.rpuMove(), gracePeriod);
    } catch (RuntimeException e) {
      copy.destroyForcibly();
      throw e;
    }
  }

  // The copy ends when the copying FFmpeg exits, and the end of the encoding FFmpeg's input lets it
  // finish. Once the encoding FFmpeg stops reading, or the mover refuses the copy, the copying
  // FFmpeg could only block on a full pipe, so it is asked to terminate. A failure is recorded
  // before the encoding FFmpeg's input ends, so that its exit never settles the attempt first.
  private void transferCopy() {
    var encoded = encode.getOutputStream();
    try (InputStream copied = copy.getInputStream()) {
      FragmentedMp4RpuMover.copy(copied, encoded, rpuMove);
    } catch (IOException e) {
      transferFailure.set(Optional.of(e));
      copy.destroy();
    } catch (FragmentedMp4Exception e) {
      refusedCopy.set(Optional.of(e));
      copy.destroy();
    } catch (RuntimeException | Error e) {
      log.error("The transfer of the copy of the source failed unexpectedly", e);
      unexpectedTransferFailure.set(Optional.of(e));
      copy.destroy();
    } finally {
      endInput(encoded);
    }
  }

  private static void endInput(OutputStream encoded) {
    try {
      encoded.close();
    } catch (IOException _) {
      // The encoding FFmpeg has already closed its input; its exit settles what it read.
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

  // Process.destroy closes the process's standard input after signalling it, and that close waits
  // for any write in progress: the transfer's write to an encoding FFmpeg that reads nothing. Its
  // handle only signals it, so the grace period and the forced end that follow stay reachable.
  @Override
  public void requestTermination() {
    copy.destroy();
    encode.toHandle().destroy();
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
   * A transfer that failed unexpectedly, or a copy that the mover refused, decides first, since the
   * encoding FFmpeg's exit then only follows the cut in its input. The encoding FFmpeg's exit
   * decides next, as a single FFmpeg's would. A copy that did not reach the encoding FFmpeg whole,
   * or a copying FFmpeg that exited with an error, fails an attempt whose encoding FFmpeg exited
   * cleanly, since its output then lacks the rest of the source. The copying FFmpeg's error
   * explains a copy that ended inside a unit, so it decides before that end.
   */
  @Override
  public AttemptOutcome outcomeAtExit(AttemptOutcome outcomeOnCleanExit) {
    var encodeExitCode = encode.onExit().join().exitValue();
    var unexpected = unexpectedTransferFailure.get();
    if (unexpected.isPresent()) {
      return new Failed(ProducerFailure.UNEXPECTED_ERROR, unexpected.orElseThrow().toString());
    }

    var refused = refusedCopy.get().filter(failure -> !endsEarly(failure));
    if (refused.isPresent()) {
      return refusal(refused.orElseThrow());
    }

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

    return refusedCopy
        .get()
        .<AttemptOutcome>map(PipedFfmpegProcesses::refusal)
        .orElse(outcomeOnCleanExit);
  }

  private static boolean endsEarly(FragmentedMp4Exception failure) {
    return ProducerFailure.of(failure.getReason()) == ProducerFailure.TRUNCATED_OUTPUT;
  }

  private static Failed refusal(FragmentedMp4Exception failure) {
    return new Failed(
        ProducerFailure.of(failure.getReason()),
        "The copy of the source cannot be passed to the FFmpeg that encodes it: "
            + failure.getMessage());
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
