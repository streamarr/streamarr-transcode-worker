package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts each segment the producer delivers and keeps its name and bytes. A test can hold one
 * delivery until it releases it, or refuse one.
 */
final class RecordingSegmentSink implements SegmentSink {

  private static final long HOLD_LIMIT_SECONDS = 10;

  private final List<Accepted> accepted = new CopyOnWriteArrayList<>();
  private final ByteArrayOutputStream acceptedBytes = new ByteArrayOutputStream();
  private final AtomicInteger deliveries = new AtomicInteger();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch held = new CountDownLatch(1);
  private volatile OptionalInt heldDelivery = OptionalInt.empty();
  private volatile OptionalInt refusedDelivery = OptionalInt.empty();

  /** Holds the delivery at this zero-based position, counting the initialization segment. */
  RecordingSegmentSink holding(int delivery) {
    heldDelivery = OptionalInt.of(delivery);
    return this;
  }

  /** Refuses the delivery at this zero-based position, counting the initialization segment. */
  RecordingSegmentSink refusing(int delivery) {
    refusedDelivery = OptionalInt.of(delivery);
    return this;
  }

  boolean isHolding() {
    return held.getCount() == 0 && release.getCount() > 0;
  }

  void release() {
    release.countDown();
  }

  @Override
  public void deliver(ProducedSegment segment) {
    var delivery = OptionalInt.of(deliveries.getAndIncrement());
    if (delivery.equals(refusedDelivery)) {
      throw new IllegalStateException("the server refused " + segment.name());
    }

    if (delivery.equals(heldDelivery)) {
      held.countDown();
      awaitRelease();
    }

    var bytes = bytesOf(segment);
    synchronized (acceptedBytes) {
      acceptedBytes.writeBytes(bytes);
    }

    accepted.add(new Accepted(segment.name(), segment.byteLength(), bytes.length));
  }

  private void awaitRelease() {
    try {
      if (!release.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("the test never released the held delivery");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while holding a delivery", e);
    }
  }

  List<Accepted> accepted() {
    return List.copyOf(accepted);
  }

  List<String> acceptedNames() {
    return accepted.stream().map(Accepted::name).toList();
  }

  /** Every accepted segment's bytes, concatenated in delivery order. */
  byte[] acceptedBytes() {
    synchronized (acceptedBytes) {
      return acceptedBytes.toByteArray();
    }
  }

  private static byte[] bytesOf(ProducedSegment segment) {
    var bytes = new ByteArrayOutputStream();
    segment
        .content()
        .forEach(
            view -> {
              var copy = new byte[view.remaining()];
              view.get(copy);
              bytes.writeBytes(copy);
            });
    return bytes.toByteArray();
  }

  /** A segment the sink accepted, with the length the producer declared and the length it held. */
  record Accepted(String name, long declaredLength, long contentLength) {}
}
