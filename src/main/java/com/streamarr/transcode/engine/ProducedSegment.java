package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.List;
import lombok.NonNull;

/**
 * A segment the producer delivers: its name in the variant's HLS playlist and the bytes FFmpeg
 * wrote for it, which the producer never copies.
 */
public final class ProducedSegment {

  private static final String INITIALIZATION_SEGMENT_NAME = "init.mp4";

  private final String name;
  private final List<byte[]> parts;

  private ProducedSegment(@NonNull String name, @NonNull List<byte[]> parts) {
    this.name = name;
    this.parts = List.copyOf(parts);
  }

  static ProducedSegment of(@NonNull InitializationSegment segment) {
    return new ProducedSegment(INITIALIZATION_SEGMENT_NAME, List.of(segment.bytes()));
  }

  static ProducedSegment of(@NonNull MediaSegment segment) {
    return new ProducedSegment(
        "segment" + segment.sequenceNumber() + ".m4s",
        segment.fragments().stream().flatMap(fragment -> fragment.boxes().stream()).toList());
  }

  public String name() {
    return name;
  }

  public long byteLength() {
    return parts.stream().mapToLong(part -> part.length).sum();
  }

  /** The segment's bytes in order, as fresh read-only views on every call. */
  public List<ByteBuffer> content() {
    return parts.stream().map(part -> ByteBuffer.wrap(part).asReadOnlyBuffer()).toList();
  }

  @Override
  public String toString() {
    return "ProducedSegment[" + name + ", " + byteLength() + " bytes]";
  }
}
