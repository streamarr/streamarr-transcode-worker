package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeCancelReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeKind;
import com.streamarr.transcode.probe.ProbeReports;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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

  private static ProbeReportQueue.Order waitingOrderOf(ProbeRequest request) {
    if (request.getPriority() == ProbePriority.PROBE_PRIORITY_PLAYBACK) {
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

  // A timed-out ffprobe can wait for the disk long after the cancel, so the brake applies when the
  // cancel arrives rather than when the unit frees. The brake runs outside this monitor because its
  // capacity change takes the worker monitor.
  void cancel(UUID attemptId, ProbeCancelReason reason) {
    cancelAttempt(attemptId, reason).ifPresent(capacities::probeTimedOut);
  }

  // Returns the probe when the cancel ends it at the probe timeout. A cancel for a probe that has
  // ended changes only where its report waits: the server ends the session when the report of a
  // probe that it canceled is late.
  private synchronized Optional<ProbeCapacities.CountedProbe> cancelAttempt(
      UUID attemptId, ProbeCancelReason reason) {
    var attempt = attempts.get(attemptId);
    if (attempt == null) {
      reports.sendFirst(attemptId);
      return Optional.empty();
    }

    if (attempt.ended) {
      attempt.cancelled = true;
      return Optional.empty();
    }

    if (reason == ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT) {
      attempt.cancelAtProbeTimeout();
      return Optional.of(attempt.counted);
    }

    attempt.cancel();
    return Optional.empty();
  }

  // Never waits, so the worker ends a session under its monitor. A report of the ended session is
  // never sent: the sender cancels its open calls and discards its waiting reports, and a probe
  // that ends from now on hands over nothing.
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

    // The unit is free before the report is handed over, so the server's count never falls below
    // the worker's. The attempt stays known until its report waits with the sender, so that a
    // cancel in between still moves the report to the canceled probes' reports.
    private void complete(ProbeAttemptReport report) {
      var attemptId = fromProto(request.getProbeAttemptId());
      boolean endedAtProbeTimeout;
      synchronized (WorkerProbeSession.this) {
        ended = true;
        endedAtProbeTimeout = timedOut;
      }

      releaseUnit(endedAtProbeTimeout);
      summary.finished(attemptId, report.getResult());
      synchronized (WorkerProbeSession.this) {
        attempts.remove(attemptId, this);
        reports.submit(
            report, cancelled ? ProbeReportQueue.Order.CANCELED : waitingOrderOf(request));
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
