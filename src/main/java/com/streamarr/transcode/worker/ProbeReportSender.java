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
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the probe reports of one worker session, each in a report call of its own on a virtual
 * thread (ADR 0062). It keeps at most the report calls open that the server accepts from the worker
 * at once, and its other reports wait in a {@link ProbeReportQueue}. A report whose call ends
 * without the server's answer is logged and discarded, and one event tells the server that the
 * probe attempt ended without a report: the worker never sends a report twice, and the server runs
 * the probe again. A probe attempt that the server canceled has no report, so {@link #discard}
 * drops its waiting report or ends its open call. This object's monitor guards the waiting reports
 * and the open calls, and no thread waits under it, so a probe's thread, the control stream's
 * thread and a holder of the worker monitor can each hand over a report, discard one or shut the
 * sender down without waiting for the server. The event takes the worker monitor, so a report
 * thread sends it only after it has released this object's monitor.
 */
@Slf4j
final class ProbeReportSender implements AutoCloseable {

  private final ProbeReportCall.Opener opener;
  private final Consumer<UUID> endedWithoutReport;
  private final ExecutorService executor;
  private final Duration readinessTimeout;
  private final Duration acknowledgementTimeout;
  private final ProbeReportQueue waiting = new ProbeReportQueue();
  private final Set<ProbeReportCall> open = new HashSet<>();
  // The open calls that a cancel command ended. The event that answers the command stands for the
  // end of each of them.
  private final Set<ProbeReportCall> canceledByServer = new HashSet<>();
  // The most calls open at once; 0 until the server states a limit, and when it states none.
  private long limit;
  private boolean shutdown;

  @Builder(access = AccessLevel.PACKAGE)
  private ProbeReportSender(
      @NonNull ProbeReportCall.Opener opener,
      @NonNull Consumer<UUID> endedWithoutReport,
      @NonNull ExecutorService executor,
      @NonNull Duration readinessTimeout,
      @NonNull Duration acknowledgementTimeout) {
    this.opener = opener;
    this.endedWithoutReport = endedWithoutReport;
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
   * report call, and an open report call ends without an event of its own. The caller answers the
   * cancel command with the event, so this method waits for nothing.
   */
  synchronized void discard(@NonNull UUID probeAttemptId) {
    waiting.discard(probeAttemptId);
    var canceled = new CancellationException("The server canceled the probe attempt");
    open.stream()
        .filter(call -> call.probeAttemptId().equals(probeAttemptId))
        .forEach(
            call -> {
              canceledByServer.add(call);
              call.cancel(canceled);
            });
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

  // The event leaves after this object's monitor is released, because sending it takes the worker
  // monitor.
  private void send(ProbeReportCall call) {
    var withoutAnswer = true;
    try {
      withoutAnswer = endsWithoutAnswer(call);
    } finally {
      if (ended(call, withoutAnswer)) {
        endedWithoutReport.accept(call.probeAttemptId());
      }
    }
  }

  // Returns whether the call opened and then ended without the server's answer.
  private boolean endsWithoutAnswer(ProbeReportCall call) {
    try {
      if (!call.send(opener)) {
        log.debug(
            "Discarding the report of probe attempt {}: its worker session has ended, or the"
                + " server canceled the attempt before the report call opened",
            call.probeAttemptId());
      }

      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logDiscarded(call, e);
      return true;
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      logDiscarded(call, e);
      return true;
    }
  }

  // A shutdown and a cancel command each cancel open calls, which is no failure of theirs.
  private void logDiscarded(ProbeReportCall call, Exception failure) {
    if (wasCanceled(call)) {
      log.debug(
          "Discarding the report of probe attempt {}: its worker session has ended, or the server"
              + " canceled the attempt",
          call.probeAttemptId());
      return;
    }

    log.warn(
        "Discarding the report of probe attempt {}: its report call failed",
        call.probeAttemptId(),
        failure);
  }

  private synchronized boolean wasCanceled(ProbeReportCall call) {
    return shutdown || canceledByServer.contains(call);
  }

  // Frees the call's place and decides in the same section whether the call's end sends the
  // event. A cancel command therefore either finds the call open, and its event then stands for
  // the call's end, or finds no call, and the call's end has decided for itself.
  private synchronized boolean ended(ProbeReportCall call, boolean withoutAnswer) {
    open.remove(call);
    var answeredByCancel = canceledByServer.remove(call);
    if (shutdown) {
      return false;
    }

    openWaiting();
    return withoutAnswer && !answeredByCancel;
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
