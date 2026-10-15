package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.Builder;

public final class ControlledProbeProcess extends Process {

  private final String stdout;
  private final int exitCode;
  private final boolean deferredTermination;
  private final CompletableFuture<Void> started = new CompletableFuture<>();
  private final CompletableFuture<Void> terminationRequested = new CompletableFuture<>();
  private final CompletableFuture<Void> awaitingExit = new CompletableFuture<>();
  private final CompletableFuture<Process> terminated =
      new CompletableFuture<>() {
        @Override
        public Process join() {
          awaitingExit.complete(null);
          return super.join();
        }
      };

  @Builder
  private ControlledProbeProcess(
      String stdout, int exitCode, boolean running, boolean deferredTermination) {
    this.stdout = stdout;
    this.exitCode = exitCode;
    this.deferredTermination = deferredTermination;
    if (!running) {
      finish();
    }
  }

  /** Waits until the probe task waits for this process. */
  public void awaitStarted(CompletableFuture<?> probeTask)
      throws InterruptedException, ExecutionException {
    awaitBeforeTaskEnds(started, probeTask, "waited for the process");
  }

  public void awaitTerminationRequested(CompletableFuture<?> probeTask)
      throws InterruptedException, ExecutionException {
    awaitBeforeTaskEnds(terminationRequested, probeTask, "requested termination");
  }

  /** Waits until the probe task waits for this process to exit after a termination request. */
  public void awaitExitWait(CompletableFuture<?> probeTask)
      throws InterruptedException, ExecutionException {
    awaitBeforeTaskEnds(awaitingExit, probeTask, "waited for the process to exit");
  }

  // Fails at once when the probe task ends first, because the signal can no longer arrive.
  private static void awaitBeforeTaskEnds(
      CompletableFuture<Void> signal, CompletableFuture<?> probeTask, String expectation)
      throws InterruptedException, ExecutionException {
    CompletableFuture.anyOf(signal, probeTask).get();
    assertThat(signal).as("the probe task %s before it ended", expectation).isDone();
  }

  public void finish() {
    terminated.complete(this);
  }

  @Override
  public OutputStream getOutputStream() {
    return OutputStream.nullOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    started.complete(null);
    try {
      terminated.get();
      if (Thread.interrupted()) {
        throw new InterruptedException("Probe wait interrupted");
      }

      return exitCode;
    } catch (ExecutionException exception) {
      throw new AssertionError(exception);
    }
  }

  @Override
  public int exitValue() {
    if (isAlive()) {
      throw new IllegalThreadStateException("Probe is still running");
    }

    return exitCode;
  }

  @Override
  public boolean isAlive() {
    return !terminated.isDone();
  }

  @Override
  public void destroy() {
    terminationRequested.complete(null);
    if (!deferredTermination) {
      finish();
    }
  }

  @Override
  public Process destroyForcibly() {
    destroy();
    return this;
  }

  @Override
  public CompletableFuture<Process> onExit() {
    return terminated;
  }
}
