package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;
import lombok.NonNull;

/**
 * A segment the producer delivers: the name the worker uploads it under and the bytes FFmpeg wrote
 * for it, which the producer never copies.
 */
public final class ProducedSegment {

  private static final String INITIALIZATION_SEGMENT_NAME = "init.mp4";

  private final OptionalInt sequenceNumber;
  private final List<byte[]> boxes;

  private ProducedSegment(@NonNull OptionalInt sequenceNumber, @NonNull List<byte[]> boxes) {
    this.sequenceNumber = sequenceNumber;
    this.boxes = List.copyOf(boxes);
  }

  static ProducedSegment of(@NonNull InitializationSegment segment) {
    return new ProducedSegment(OptionalInt.empty(), List.of(segment.bytes()));
  }

  static ProducedSegment of(@NonNull MediaSegment segment) {
    return new ProducedSegment(
        OptionalInt.of(segment.sequenceNumber()),
        segment.fragments().stream().flatMap(fragment -> fragment.boxes().stream()).toList());
  }

  /** The media segment's sequence number; empty for the initialization segment. */
  public OptionalInt sequenceNumber() {
    return sequenceNumber;
  }

  /** The name the worker uploads the segment under. */
  public String name() {
    if (sequenceNumber.isEmpty()) {
      return INITIALIZATION_SEGMENT_NAME;
    }

    return "segment" + sequenceNumber.getAsInt() + ".m4s";
  }

  public long byteLength() {
    return boxes.stream().mapToLong(box -> box.length).sum();
  }

  /** The segment's bytes in order, as fresh read-only views on every call. */
  public List<ByteBuffer> content() {
    return boxes.stream().map(box -> ByteBuffer.wrap(box).asReadOnlyBuffer()).toList();
  }

  @Override
  public String toString() {
    return "ProducedSegment[" + name() + ", " + byteLength() + " bytes]";
  }
}
