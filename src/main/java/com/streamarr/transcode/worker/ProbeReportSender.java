package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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
 * thread (ADR 0062). It keeps at most the report calls open that the server accepts from the worker
 * at once, and its other reports wait in a {@link ProbeReportQueue}. A report whose call fails is
 * logged and discarded: the worker never sends a report twice, and the server runs the probe again.
 * This object's monitor guards the waiting reports and the open calls, and no thread waits under
 * it, so a probe's thread, the control stream's thread and a holder of the worker monitor can each
 * hand over a report or shut the sender down without waiting for the server.
 */
@Slf4j
final class ProbeReportSender implements AutoCloseable {

  private final ProbeReportCall.Opener opener;
  private final ExecutorService executor;
  private final Duration readinessTimeout;
  private final Duration acknowledgementTimeout;
  private final ProbeReportQueue waiting = new ProbeReportQueue();
  private final Set<ProbeReportCall> open = new HashSet<>();
  // The most calls open at once; 0 until the server states a limit, and when it states none.
  private long limit;
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

  /**
   * Takes the number of report calls that the server accepts from the worker at once, which the
   * server states when it accepts the worker session. A value of 0 states no limit.
   */
  synchronized void limit(int probeReportLimit) {
    limit = Integer.toUnsignedLong(probeReportLimit);
    openWaiting();
  }

  /**
   * Takes a probe attempt's report to send, which waits in this order while the sender holds its
   * limit of open calls. A sender that has shut down discards the report.
   */
  synchronized void submit(
      @NonNull ProbeAttemptReport report, @NonNull ProbeReportQueue.Order order) {
    if (shutdown) {
      return;
    }

    waiting.add(report, order);
    openWaiting();
  }

  /**
   * Discards the report of a probe attempt that the server canceled: a waiting report never opens a
   * report call, and an open report call ends. The caller answers the cancel command with the
   * event, so this method waits for nothing.
   */
  synchronized void discard(@NonNull UUID probeAttemptId) {
    waiting.discard(probeAttemptId);
    var canceled = new CancellationException("The server canceled the probe attempt");
    open.stream()
        .filter(call -> call.probeAttemptId().equals(probeAttemptId))
        .forEach(call -> call.cancel(canceled));
  }

  /**
   * Moves this probe attempt's report, when it still waits, to the reports of canceled probes. The
   * server ends the worker session when a canceled probe's report is late.
   */
  synchronized void sendFirst(@NonNull UUID probeAttemptId) {
    waiting.moveToCanceled(probeAttemptId);
  }

  // Holds this monitor.
  private void openWaiting() {
    for (var order : ProbeReportQueue.Order.values()) {
      openWaiting(order);
    }
  }

  // Holds this monitor.
  private void openWaiting(ProbeReportQueue.Order order) {
    while (limit == 0 || open.size() < limit) {
      var next = waiting.poll(order);
      if (next.isEmpty()) {
        return;
      }

      var call = new ProbeReportCall(next.get(), readinessTimeout, acknowledgementTimeout);
      open.add(call);
      executor.execute(() -> send(call));
    }
  }

  private void send(ProbeReportCall call) {
    try {
      if (!call.send(opener)) {
        log.debug(
            "Discarding the report of probe attempt {}: its worker session has ended, or the"
                + " server canceled the attempt before the report call opened",
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
    if (!shutdown) {
      openWaiting();
    }
  }

  /**
   * Cancels the open report calls and discards the waiting reports. The session that produced them
   * has ended, so none of them is ever sent.
   */
  synchronized void shutdown() {
    shutdown = true;
    waiting.clear();
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
