package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The probe reports that wait for a report call, in the order in which the worker sends them: the
 * reports of canceled probes first, then the reports of probes with playback priority, then every
 * other report, and first in, first out within each of the three (ADR 0062). The owner's monitor
 * guards a queue.
 */
final class ProbeReportQueue {

  /** Where a report waits; an earlier constant goes first. */
  enum Order {
    CANCELED,
    PLAYBACK,
    OTHER
  }

  private final Map<Order, Deque<ProbeAttemptReport>> waiting = new EnumMap<>(Order.class);

  ProbeReportQueue() {
    for (var order : Order.values()) {
      waiting.put(order, new ArrayDeque<>());
    }
  }

  void add(ProbeAttemptReport report, Order order) {
    waiting.get(order).add(report);
  }

  /** Removes and returns the report that the worker sends next. */
  Optional<ProbeAttemptReport> poll() {
    return waiting.values().stream()
        .filter(reports -> !reports.isEmpty())
        .findFirst()
        .map(Deque::remove);
  }

  /**
   * Moves this probe attempt's report, when it waits behind the canceled probes' reports, to the
   * end of them.
   */
  void moveToCanceled(UUID probeAttemptId) {
    remove(waiting.get(Order.PLAYBACK), probeAttemptId)
        .or(() -> remove(waiting.get(Order.OTHER), probeAttemptId))
        .ifPresent(report -> add(report, Order.CANCELED));
  }

  private static Optional<ProbeAttemptReport> remove(
      Deque<ProbeAttemptReport> reports, UUID probeAttemptId) {
    var remaining = reports.iterator();
    while (remaining.hasNext()) {
      var report = remaining.next();
      if (fromProto(report.getResult().getProbeAttemptId()).equals(probeAttemptId)) {
        remaining.remove();
        return Optional.of(report);
      }
    }

    return Optional.empty();
  }

  void clear() {
    waiting.values().forEach(Deque::clear);
  }
}
