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
 * The probe reports that wait for a report call, in the two orders in which the worker sends them:
 * the reports of playback probes, and every other report, first in, first out within each (ADR
 * 0062). The owner's monitor guards a queue.
 */
final class ProbeReportQueue {

  /**
   * Where a report waits: with the reports of playback probes, which go first, or with the others.
   */
  enum Order {
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

  /** Removes and returns the report that has waited longest in this order. */
  Optional<ProbeAttemptReport> poll(Order order) {
    return Optional.ofNullable(waiting.get(order).poll());
  }

  /** Discards this probe attempt's report when it waits. */
  void discard(UUID probeAttemptId) {
    waiting
        .values()
        .forEach(
            reports ->
                reports.removeIf(
                    report ->
                        fromProto(report.getResult().getProbeAttemptId()).equals(probeAttemptId)));
  }

  void clear() {
    waiting.values().forEach(Deque::clear);
  }
}
