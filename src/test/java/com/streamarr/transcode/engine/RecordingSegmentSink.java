package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts each segment the producer delivers and keeps its name and bytes. A test can hold one
 * delivery until it releases it or the producer cancels it, or until it releases it whether or not
 * the producer cancelled it, refuse one, or throw an error from one.
 */
final class RecordingSegmentSink implements SegmentSink {

  private static final long HOLD_LIMIT_SECONDS = 10;

  private final List<Accepted> accepted = new CopyOnWriteArrayList<>();
  private final List<ProductionTiming> mediaTimings = new CopyOnWriteArrayList<>();
  private final Map<String, byte[]> acceptedContent = new ConcurrentHashMap<>();
  private final ByteArrayOutputStream acceptedBytes = new ByteArrayOutputStream();
  private final AtomicInteger deliveries = new AtomicInteger();
  private final List<String> startedNames = new CopyOnWriteArrayList<>();
  private final List<String> returnedNames = new CopyOnWriteArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch held = new CountDownLatch(1);
  private volatile OptionalInt heldDelivery = OptionalInt.empty();
  private volatile OptionalInt refusedDelivery = OptionalInt.empty();
  private volatile OptionalInt failedDelivery = OptionalInt.empty();
  private volatile Error failure;
  private volatile boolean cancelled;
  private volatile boolean holdsPastCancellation;

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

  /** Throws the error from the delivery at this zero-based position. */
  RecordingSegmentSink failing(int delivery, Error error) {
    failure = error;
    failedDelivery = OptionalInt.of(delivery);
    return this;
  }

  /**
   * Holds the delivery at this zero-based position until the test releases it, even after the
   * producer cancels it, as an upload slow to abandon would.
   */
  RecordingSegmentSink holdingPastCancellation(int delivery) {
    holdsPastCancellation = true;
    return holding(delivery);
  }

  boolean isHolding() {
    return held.getCount() == 0 && release.getCount() > 0;
  }

  void release() {
    release.countDown();
  }

  /** Whether the producer cancelled the held delivery. */
  boolean wasCancelled() {
    return cancelled;
  }

  /** The name of every segment the producer has handed to the sink, returned or not. */
  List<String> startedNames() {
    return List.copyOf(startedNames);
  }

  /**
   * The name of every delivery that has returned or thrown, noted just before it does, so never
   * later than the producer learns of it.
   */
  List<String> returnedNames() {
    return List.copyOf(returnedNames);
  }

  @Override
  public void deliver(ProducedSegment segment, DeliveryCancellation cancellation) {
    startedNames.add(segment.name());
    try {
      receive(segment, cancellation);
    } finally {
      returnedNames.add(segment.name());
    }
  }

  private void receive(ProducedSegment segment, DeliveryCancellation cancellation) {
    var delivery = OptionalInt.of(deliveries.getAndIncrement());
    if (delivery.equals(refusedDelivery)) {
      throw new IllegalStateException("the server refused " + segment.name());
    }

    if (delivery.equals(failedDelivery)) {
      throw failure;
    }

    if (delivery.equals(heldDelivery)) {
      cancellation.onCancel(
          holdsPastCancellation ? this::noteCancellation : this::cancelHeldDelivery);
      held.countDown();
      awaitRelease();
    }

    if (cancelled) {
      throw new CancellationException(segment.name() + " was cancelled");
    }

    var bytes = bytesOf(segment);
    synchronized (acceptedBytes) {
      acceptedBytes.writeBytes(bytes);
    }

    acceptedContent.put(segment.name(), bytes);
    accepted.add(new Accepted(segment.name(), segment.byteLength(), bytes.length));
    if (segment instanceof ProducedSegment.Media media) {
      mediaTimings.add(media.timing());
    }
  }

  private void cancelHeldDelivery() {
    noteCancellation();
    release.countDown();
  }

  private void noteCancellation() {
    cancelled = true;
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

  /** The timing of each accepted media segment, in delivery order. */
  List<ProductionTiming> mediaTimings() {
    return List.copyOf(mediaTimings);
  }

  List<String> acceptedNames() {
    return accepted.stream().map(Accepted::name).toList();
  }

  /** The bytes of the accepted segment with this name. */
  byte[] contentOf(String name) {
    return Optional.ofNullable(acceptedContent.get(name))
        .orElseThrow(() -> new AssertionError("the sink accepted no " + name));
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
