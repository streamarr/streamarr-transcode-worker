package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeCancelReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeKind;
import com.streamarr.transcode.probe.ProbeReports;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder(access = AccessLevel.PACKAGE)
final class WorkerProbeSession implements AutoCloseable {

  @NonNull private final FfprobeExecutor ffprobe;
  @NonNull private final WorkerMediaSourceResolver sources;
  @NonNull private final ProbeReportSender reports;
  @NonNull private final ExecutorService executor;
  @NonNull private final ProbeSummary summary;
  @NonNull private final ProbeCapacities capacities;
  // Sends the event that a probe attempt has ended without a probe report. It takes the worker
  // monitor, so it never runs under this object's monitor.
  @NonNull private final Consumer<UUID> endedWithoutReport;
  private final Map<UUID, ProbeAttempt> attempts = new HashMap<>();
  // No limit until the server states its limits, which it does before it starts any probe.
  private final AtomicReference<ProbeReportLimits> limits =
      new AtomicReference<>(ProbeReportLimits.NONE);

  /** Takes the limits on probe reports that the server stated when it accepted the session. */
  void accepted(WorkerSessionAccepted accepted) {
    limits.set(ProbeReportLimits.of(accepted));
    reports.limit(accepted.getProbeReportLimit());
  }

  // A refused probe ends on the control stream's thread, which hands the report over and never
  // waits for the server.
  void start(ProbeRequest request) {
    if (tryAdmit(request) instanceof Admission.Refused(var report)) {
      summary.finished(fromProto(request.getProbeAttemptId()), report.getResult());
      reports.submit(report, waitingOrderOf(request));
    }
  }

  // A scan with playback priority is no playback probe, so its report takes no kept report call.
  private static ProbeReportQueue.Order waitingOrderOf(ProbeRequest request) {
    if (ProbeKind.of(request) == ProbeKind.PLAYBACK) {
      return ProbeReportQueue.Order.PLAYBACK;
    }

    return ProbeReportQueue.Order.OTHER;
  }

  private synchronized Admission tryAdmit(ProbeRequest request) {
    var attemptId = fromProto(request.getProbeAttemptId());
    if (executor.isShutdown()) {
      return new Admission.Ignored();
    }

    if (attempts.containsKey(attemptId)) {
      log.warn("Ignoring duplicate probe attempt {}", attemptId);
      return new Admission.Ignored();
    }

    // The server sends a probe only against a free unit, so a probe finds none only when the
    // worker lowered the capacity before the server learned of the change.
    var kind = ProbeKind.of(request);
    var counted = capacities.tryAcquire(kind);
    if (counted.isEmpty()) {
      log.debug("Refusing probe {}: the {} capacity has no free unit", attemptId, kind);
      return new Admission.Refused(
          ProbeReports.failed(request, ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY));
    }

    var attempt = new ProbeAttempt(request, counted.get());
    attempts.put(attemptId, attempt);
    summary.started(attemptId);
    executor.execute(attempt);
    return new Admission.Started();
  }

  private sealed interface Admission {
    record Started() implements Admission {}

    record Ignored() implements Admission {}

    record Refused(ProbeAttemptReport report) implements Admission {}
  }

  // Answers a cancel command on the control stream's thread, which waits for neither ffprobe nor
  // the server. A timed-out ffprobe can wait for the disk long after the cancel, so the brake
  // applies when the cancel arrives rather than when the unit frees. The brake and the event run
  // outside this monitor because each takes the worker monitor.
  void cancel(UUID attemptId, ProbeCancelReason reason) {
    Runnable outsideThisMonitor =
        switch (cancelAttempt(attemptId, reason)) {
          case Cancellation.Stopped() -> () -> {};
          case Cancellation.StoppedAtProbeTimeout(var probe) ->
              () -> capacities.probeTimedOut(probe);
          case Cancellation.NothingRuns() -> () -> endedWithoutReport.accept(attemptId);
        };
    outsideThisMonitor.run();
  }

  // Decides in one section who sends the event that answers the cancel command: the probe's own
  // thread once the probe has ended, or the caller at once when no probe runs for the attempt. A
  // probe attempt that the server canceled has no probe report, so the sender discards a report
  // that the attempt already handed over.
  private synchronized Cancellation cancelAttempt(UUID attemptId, ProbeCancelReason reason) {
    var attempt = attempts.get(attemptId);
    if (attempt == null) {
      reports.discard(attemptId);
      return new Cancellation.NothingRuns();
    }

    if (attempt.ended) {
      attempt.cancelled = true;
      return new Cancellation.Stopped();
    }

    if (reason == ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT) {
      attempt.cancelAtProbeTimeout();
      return new Cancellation.StoppedAtProbeTimeout(attempt.counted);
    }

    attempt.cancel();
    return new Cancellation.Stopped();
  }

  private sealed interface Cancellation {
    /**
     * The probe still runs or has yet to hand its report over, and its own thread sends the event
     * once the probe has ended.
     */
    record Stopped() implements Cancellation {}

    /** As {@link Stopped}, for a probe that still ran when the server's probe timeout passed. */
    record StoppedAtProbeTimeout(ProbeCapacities.CountedProbe probe) implements Cancellation {}

    /** No probe runs for the attempt, so the caller sends the event at once. */
    record NothingRuns() implements Cancellation {}
  }

  // Never waits, so the worker ends a session under its monitor. A report of the ended session is
  // never sent: the sender cancels its open calls and discards its waiting reports, and a probe
  // that ends from now on hands over nothing. The server learns of these ends from the session's
  // end, so no event names them.
  synchronized void shutdown() {
    attempts.values().forEach(ProbeAttempt::cancel);
    executor.shutdownNow();
    reports.shutdown();
  }

  // Waits for the probe threads and then the report threads, which open their calls under the
  // worker monitor; the worker therefore closes a session's probes only after releasing it.
  @Override
  public void close() {
    shutdown();
    executor.close();
    reports.close();
  }

  @RequiredArgsConstructor
  private final class ProbeAttempt implements Runnable {

    private final ProbeRequest request;
    private final ProbeCapacities.CountedProbe counted;
    private final AtomicReference<Thread> thread = new AtomicReference<>();
    private volatile boolean cancelled;
    // Guarded by WorkerProbeSession.this.
    private boolean timedOut;
    // Guarded by WorkerProbeSession.this. The probe has ended and has yet to hand its report over.
    private boolean ended;

    private void cancelAtProbeTimeout() {
      timedOut = true;
      cancel();
    }

    private void cancel() {
      cancelled = true;
      var runningThread = thread.get();
      if (runningThread != null) {
        runningThread.interrupt();
      }
    }

    @Override
    public void run() {
      var runningThread = Thread.currentThread();
      thread.set(runningThread);
      if (cancelled) {
        runningThread.interrupt();
      }

      complete(execute());
    }

    private ProbeAttemptReport execute() {
      return limits.get().apply(request, probe());
    }

    private ProbeAttemptReport probe() {
      try {
        return ffprobe.probe(sources.resolve(request.getSource()), request);
      } catch (WorkerJobException exception) {
        log.warn(
            "Probe {} cannot resolve source namespace {} key {}",
            fromProto(request.getProbeAttemptId()),
            fromProto(request.getSource().getSourceNamespaceId()),
            request.getSource().getRelativeKey(),
            exception);
        return failed(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      } catch (RuntimeException exception) {
        log.error(
            "Probe {} failed unexpectedly for source namespace {} key {}",
            fromProto(request.getProbeAttemptId()),
            fromProto(request.getSource().getSourceNamespaceId()),
            request.getSource().getRelativeKey(),
            exception);
        return failed(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
      }
    }

    private ProbeAttemptReport failed(ProbeFailure failure) {
      return ProbeReports.failed(request, failure);
    }

    // The unit is free before the report is handed over or the event leaves, so the server's count
    // never falls below the worker's.
    private void complete(ProbeAttemptReport report) {
      var attemptId = fromProto(request.getProbeAttemptId());
      boolean endedAtProbeTimeout;
      synchronized (WorkerProbeSession.this) {
        ended = true;
        endedAtProbeTimeout = timedOut;
      }

      releaseUnit(endedAtProbeTimeout);
      summary.finished(attemptId, report.getResult());
      if (!handOver(report)) {
        endedWithoutReport.accept(attemptId);
      }
    }

    // Returns whether the report went to the sender. A stopped probe attempt has no probe report.
    // The attempt stays known until this section, so a cancel command either stops the attempt
    // before its report is handed over, or finds the report with the sender.
    private boolean handOver(ProbeAttemptReport report) {
      synchronized (WorkerProbeSession.this) {
        attempts.remove(fromProto(request.getProbeAttemptId()), this);
        if (cancelled) {
          return false;
        }

        reports.submit(report, waitingOrderOf(request));
        return true;
      }
    }

    private void releaseUnit(boolean endedAtProbeTimeout) {
      if (endedAtProbeTimeout) {
        capacities.releaseTimedOut(counted);
        return;
      }

      capacities.release(counted);
    }
  }
}
