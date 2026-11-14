package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** A job attempt's FFmpeg that runs as one process. */
final class SingleFfmpegProcess implements FfmpegProcess {

  private final Process process;
  private final StderrDrainer errorOutput;
  private final Duration gracePeriod;

  SingleFfmpegProcess(Process process, Duration gracePeriod) {
    this.process = process;
    this.errorOutput = new StderrDrainer(process.getErrorStream());
    this.gracePeriod = gracePeriod;
  }

  @Override
  public long pid() {
    return process.pid();
  }

  @Override
  public List<Process> processes() {
    return List.of(process);
  }

  @Override
  public Duration gracePeriod() {
    return gracePeriod;
  }

  @Override
  public InputStream output() {
    return process.getInputStream();
  }

  @Override
  public void requestQuit() {
    try {
      var input = process.getOutputStream();
      input.write('q');
      input.flush();
    } catch (IOException _) {
      // FFmpeg has already closed its input; the exit wait observes it ending.
    }
  }

  @Override
  public void requestTermination() {
    process.destroy();
  }

  @Override
  public void awaitExitEndingItOnInterrupt() {
    try {
      process.waitFor();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  @Override
  public boolean tryAwaitExitWithinGracePeriod() {
    try {
      return process.waitFor(gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void endForcibly() {
    process.destroyForcibly();
    process.onExit().join();
  }

  @Override
  public Optional<Failed> failureAtExit() {
    var exitCode = process.onExit().join().exitValue();
    if (exitCode == 0) {
      return Optional.empty();
    }

    return Optional.of(
        new Failed(
            ProducerFailure.PROCESS_EXITED_WITH_ERROR, errorOutput.exitDetail("FFmpeg", exitCode)));
  }

  @Override
  public void discardRemainingOutput() {
    try {
      process.getInputStream().transferTo(OutputStream.nullOutputStream());
    } catch (IOException _) {
      // Nothing read after the attempt's outcome was decided matters.
    }
  }

  @Override
  public List<ObservedSourceStream> observedSourceStreams() {
    return errorOutput.observedSourceStreams();
  }

  @Override
  public void stopDrainingErrorOutput() {
    errorOutput.close();
  }
}
