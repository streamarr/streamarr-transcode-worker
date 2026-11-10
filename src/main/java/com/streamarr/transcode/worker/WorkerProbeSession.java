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

  /** Takes the limits on a probe report that the server stated when it accepted the session. */
  void accepted(WorkerSessionAccepted accepted) {
    limits.set(ProbeReportLimits.of(accepted));
  }

  void start(ProbeRequest request) {
    if (tryAdmit(request) instanceof Admission.Refused(var report)) {
      complete(report);
    }
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

  // Hands the report over without waiting: a refused probe ends on the control stream's thread.
  private void complete(ProbeAttemptReport report) {
    summary.finished(fromProto(report.getResult().getProbeAttemptId()), report.getResult());
    reports.submit(report);
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

  // Returns the probe when the cancel ends it at the probe timeout.
  private synchronized Optional<ProbeCapacities.CountedProbe> cancelAttempt(
      UUID attemptId, ProbeCancelReason reason) {
    var attempt = attempts.get(attemptId);
    if (attempt == null) {
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
  // never sent: its open call is cancelled, and a probe that ends from now on hands over nothing.
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
    // the worker's.
    private void complete(ProbeAttemptReport report) {
      boolean endedAtProbeTimeout;
      synchronized (WorkerProbeSession.this) {
        attempts.remove(fromProto(report.getResult().getProbeAttemptId()), this);
        endedAtProbeTimeout = timedOut;
      }

      releaseUnit(endedAtProbeTimeout);
      WorkerProbeSession.this.complete(report);
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
