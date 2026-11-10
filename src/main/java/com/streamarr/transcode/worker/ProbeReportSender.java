package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the probe reports of one worker session, each in a report call of its own on a virtual
 * thread (ADR 0062). A report whose call fails is logged and discarded: the worker never sends a
 * report twice, and the server runs the probe again. This object's monitor guards the open calls,
 * and no thread waits under it, so a probe's thread, the control stream's thread and a holder of
 * the worker monitor can each hand over a report or shut the sender down without waiting for the
 * server.
 */
@Slf4j
final class ProbeReportSender implements AutoCloseable {

  private final ProbeReportCall.Opener opener;
  private final ExecutorService executor;
  private final Duration readinessTimeout;
  private final Duration acknowledgementTimeout;
  private final Set<ProbeReportCall> open = new HashSet<>();
  private boolean shutdown;

  @Builder(access = AccessLevel.PACKAGE)
  private ProbeReportSender(
      @NonNull ProbeReportCall.Opener opener,
      @NonNull ExecutorService executor,
      @NonNull Duration readinessTimeout,
      @NonNull Duration acknowledgementTimeout) {
    this.opener = opener;
    this.executor = executor;
    this.readinessTimeout = readinessTimeout;
    this.acknowledgementTimeout = acknowledgementTimeout;
  }

  /** Takes a probe attempt's report to send. A sender that has shut down discards it. */
  synchronized void submit(@NonNull ProbeAttemptReport report) {
    if (shutdown) {
      return;
    }

    var call = new ProbeReportCall(report, readinessTimeout, acknowledgementTimeout);
    open.add(call);
    executor.execute(() -> send(call));
  }

  private void send(ProbeReportCall call) {
    try {
      if (!call.send(opener)) {
        log.debug(
            "Discarding the report of probe attempt {}: its worker session has ended",
            call.probeAttemptId());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logDiscarded(call, e);
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      logDiscarded(call, e);
    } finally {
      ended(call);
    }
  }

  // A shutdown cancels the open calls, which is no failure of theirs.
  private void logDiscarded(ProbeReportCall call, Exception failure) {
    if (hasShutDown()) {
      log.debug(
          "Discarding the report of probe attempt {}: its worker session has ended",
          call.probeAttemptId());
      return;
    }

    log.warn(
        "Discarding the report of probe attempt {}: its report call failed",
        call.probeAttemptId(),
        failure);
  }

  private synchronized boolean hasShutDown() {
    return shutdown;
  }

  private synchronized void ended(ProbeReportCall call) {
    open.remove(call);
  }

  /**
   * Cancels the open report calls and takes no further report. The session that produced them has
   * ended, so none of them is ever sent.
   */
  synchronized void shutdown() {
    shutdown = true;
    var sessionEnded = new CancellationException("The worker session ended");
    open.forEach(call -> call.cancel(sessionEnded));
    executor.shutdownNow();
  }

  /** Shuts the sender down and waits for its report threads to end. */
  @Override
  public void close() {
    shutdown();
    executor.close();
  }
}
