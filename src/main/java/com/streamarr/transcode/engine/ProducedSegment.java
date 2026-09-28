package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.List;
import lombok.NonNull;

/**
 * A segment the producer delivers: the name the worker uploads it under and the bytes FFmpeg wrote
 * for it, which the producer never copies.
 */
public abstract sealed class ProducedSegment {

  // Read-only views that nothing moves, so each call hands out duplicates.
  private final List<ByteBuffer> parts;

  private ProducedSegment(@NonNull List<ByteBuffer> parts) {
    this.parts = parts.stream().map(ByteBuffer::asReadOnlyBuffer).toList();
  }

  static Initialization of(@NonNull InitializationSegment segment) {
    return new Initialization(List.of(ByteBuffer.wrap(segment.bytes())));
  }

  static Media of(@NonNull MediaSegment segment) {
    return new Media(
        segment.sequenceNumber(),
        segment.fragments().stream()
            .flatMap(fragment -> fragment.boxes().stream())
            .map(ByteBuffer::wrap)
            .toList());
  }

  /** The name the worker uploads the segment under. */
  public abstract String name();

  public long byteLength() {
    return parts.stream().mapToLong(ByteBuffer::remaining).sum();
  }

  /** The segment's bytes in order, as fresh read-only views on every call. */
  public List<ByteBuffer> content() {
    return parts.stream().map(ByteBuffer::duplicate).toList();
  }

  @Override
  public String toString() {
    return "ProducedSegment[" + name() + ", " + byteLength() + " bytes]";
  }

  /** The initialization segment. */
  public static final class Initialization extends ProducedSegment {

    private Initialization(List<ByteBuffer> parts) {
      super(parts);
    }

    @Override
    public String name() {
      return "init.mp4";
    }
  }

  /** A media segment, named after its sequence number. */
  public static final class Media extends ProducedSegment {

    private final int sequenceNumber;

    private Media(int sequenceNumber, List<ByteBuffer> parts) {
      super(parts);
      this.sequenceNumber = sequenceNumber;
    }

    @Override
    public String name() {
      return "segment" + sequenceNumber + ".m4s";
    }
  }
}
