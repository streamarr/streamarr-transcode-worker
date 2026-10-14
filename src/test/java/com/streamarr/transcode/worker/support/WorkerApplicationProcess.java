package com.streamarr.transcode.worker.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;

/**
 * A worker application running in a child JVM. Each wait races its outcome against the child's
 * exit, so a child that dies first fails the wait at once with its exit code and log tail. The
 * waits have no time limit of their own; the test class bounds them with {@code @Timeout}.
 */
public final class WorkerApplicationProcess implements AutoCloseable {

  private static final int LOG_TAIL_LINES = 40;
  private static final Duration SHUTDOWN_HANG_GUARD = Duration.ofMinutes(1);

  private final Process process;
  private final Path log;
  private boolean waitInterrupted;

  private WorkerApplicationProcess(Process process, Path log) {
    this.process = process;
    this.log = log;
  }

  public static WorkerApplicationProcess start(@NonNull ProcessBuilder command, @NonNull Path log)
      throws IOException {
    var process = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    return new WorkerApplicationProcess(process, log);
  }

  public <T> T awaitBeforeExit(CompletableFuture<T> outcome, String expectation)
      throws InterruptedException, ExecutionException {
    awaitInterruptibly(() -> CompletableFuture.anyOf(outcome, process.onExit()).get());
    if (outcome.isDone()) {
      return outcome.get();
    }

    throw new AssertionError(
        "The worker application exited with code %d before %s%n%s"
            .formatted(process.exitValue(), expectation, logTail()));
  }

  public WorkerRegistration awaitRegistration(WorkerApplicationControlPlane controlPlane)
      throws InterruptedException, ExecutionException {
    return awaitBeforeExit(controlPlane.registration(), "it registered");
  }

  public ProbeAttemptResult awaitProbeResult(WorkerApplicationControlPlane controlPlane)
      throws InterruptedException, ExecutionException {
    return awaitBeforeExit(controlPlane.probeResult(), "it returned a probe result");
  }

  public int awaitExitBeforeRegistration(WorkerApplicationControlPlane controlPlane)
      throws InterruptedException, ExecutionException {
    var registration = controlPlane.registration();
    awaitInterruptibly(() -> CompletableFuture.anyOf(registration, process.onExit()).get());
    if (registration.isDone()) {
      throw new AssertionError(
          "The worker application registered although startup should refuse%n%s"
              .formatted(logTail()));
    }

    return process.exitValue();
  }

  public int awaitExit() throws InterruptedException {
    return awaitInterruptibly(process::waitFor);
  }

  /**
   * Stops the child with SIGTERM and waits for it to exit. A child that outlasts the hang guard is
   * killed. When an earlier wait was interrupted, the child is killed at once.
   */
  @Override
  public void close() {
    if (waitInterrupted) {
      kill();
      return;
    }

    process.destroy();
    try {
      // The guard outlasts Spring's 30 s shutdown phase, so a progressing shutdown writes coverage.
      if (process.waitFor(SHUTDOWN_HANG_GUARD)) {
        return;
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    kill();
  }

  // An interrupted wait clears the interrupt flag, so close() keeps the cancellation here instead.
  private <T, X extends Exception> T awaitInterruptibly(InterruptibleWait<T, X> wait)
      throws InterruptedException, X {
    try {
      return wait.await();
    } catch (InterruptedException interruption) {
      waitInterrupted = true;
      throw interruption;
    }
  }

  private void kill() {
    process.destroyForcibly();
    process.onExit().join();
  }

  private String logTail() {
    try {
      var lines = new String(Files.readAllBytes(log), UTF_8).lines().toList();
      return String.join(
          System.lineSeparator(),
          lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
    } catch (IOException exception) {
      return "The worker log could not be read: " + exception;
    }
  }

  @FunctionalInterface
  private interface InterruptibleWait<T, X extends Exception> {
    T await() throws InterruptedException, X;
  }
}
