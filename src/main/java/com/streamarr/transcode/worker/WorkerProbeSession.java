package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeCancelReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeAttemptReport;
import com.streamarr.transcode.probe.ProbeKind;
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

  // worker_session.proto: the server ends the session on a larger message.
  private static final int MAXIMUM_SESSION_MESSAGE_BYTES = 131_072;

  @NonNull private final FfprobeExecutor ffprobe;
  @NonNull private final WorkerMediaSourceResolver sources;
  @NonNull private final Consumer<ProbeAttemptReport> reports;
  @NonNull private final ExecutorService executor;
  @NonNull private final ProbeSummary summary;
  @NonNull private final ProbeCapacities capacities;
  private final Map<UUID, ProbeAttempt> attempts = new HashMap<>();

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
      return new Admission.Refused(failed(request, ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY));
    }

    var attempt = new ProbeAttempt(request, counted.get());
    attempts.put(attemptId, attempt);
    summary.started(attemptId);
    executor.execute(attempt);
    return new Admission.Started();
  }

  private void complete(ProbeAttemptReport report) {
    summary.finished(fromProto(report.result().getProbeAttemptId()), report.result());
    reports.accept(report);
  }

  private static ProbeAttemptReport failed(ProbeRequest request, ProbeFailure failure) {
    return ProbeAttemptReport.resultOnly(
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion())
            .setFailure(failure)
            .build());
  }

  private sealed interface Admission {
    record Started() implements Admission {}

    record Ignored() implements Admission {}

    record Refused(ProbeAttemptReport report) implements Admission {}
  }

  synchronized void cancel(UUID attemptId, ProbeCancelReason reason) {
    var attempt = attempts.get(attemptId);
    if (attempt == null) {
      return;
    }

    if (reason == ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT) {
      attempt.cancelAtProbeTimeout();
      return;
    }

    attempt.cancel();
  }

  synchronized void shutdown() {
    attempts.values().forEach(ProbeAttempt::cancel);
    executor.shutdownNow();
  }

  @Override
  public void close() {
    shutdown();
    executor.close();
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
      var report = probe();
      var sessionMessageBytes =
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeResult(report.result())
              .build()
              .getSerializedSize();
      if (sessionMessageBytes <= MAXIMUM_SESSION_MESSAGE_BYTES) {
        return report;
      }

      log.warn(
          "Probe {} result takes {} bytes in its session message, more than the {} bytes the"
              + " server accepts",
          fromProto(request.getProbeAttemptId()),
          sessionMessageBytes,
          MAXIMUM_SESSION_MESSAGE_BYTES);
      return failed(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
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
      return WorkerProbeSession.failed(request, failure);
    }

    // The unit is free before the result goes out, so the server's count never falls below the
    // worker's.
    private void complete(ProbeAttemptReport report) {
      boolean endedAtProbeTimeout;
      synchronized (WorkerProbeSession.this) {
        attempts.remove(fromProto(report.result().getProbeAttemptId()), this);
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
