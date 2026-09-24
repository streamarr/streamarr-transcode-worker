package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.List;
import lombok.NonNull;

/**
 * A segment the producer delivers: the name the worker uploads it under and the bytes FFmpeg wrote
 * for it, which the producer never copies.
 */
public abstract sealed class ProducedSegment
    permits ProducedSegment.Initialization, ProducedSegment.Media {

  private final List<byte[]> boxes;

  private ProducedSegment(@NonNull List<byte[]> boxes) {
    this.boxes = List.copyOf(boxes);
  }

  static Initialization of(@NonNull InitializationSegment segment) {
    return new Initialization(List.of(segment.bytes()));
  }

  static Media of(@NonNull MediaSegment segment) {
    return new Media(
        segment.sequenceNumber(),
        segment.fragments().stream().flatMap(fragment -> fragment.boxes().stream()).toList());
  }

  /** The name the worker uploads the segment under. */
  public abstract String name();

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

  /** The initialization segment. */
  public static final class Initialization extends ProducedSegment {

    private Initialization(List<byte[]> boxes) {
      super(boxes);
    }

    @Override
    public String name() {
      return "init.mp4";
    }
  }

  /** A media segment, named after its sequence number. */
  public static final class Media extends ProducedSegment {

    private final int sequenceNumber;

    private Media(int sequenceNumber, List<byte[]> boxes) {
      super(boxes);
      this.sequenceNumber = sequenceNumber;
    }

    @Override
    public String name() {
      return "segment" + sequenceNumber + ".m4s";
    }
  }
}
