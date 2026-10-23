package com.streamarr.transcode.engine;

import lombok.NonNull;

/**
 * A segment the assembler closed. The producer reserves the memory that writing and delivering it
 * holds beyond what the reader already holds for it, then writes it and hands it to the sink.
 */
sealed interface ClosedSegment permits ClosedSegment.Initialization, RebuiltSegment {

  /** The bytes the reader holds for the segment, which its delivery takes over. */
  long heldBytes();

  /** The bytes that writing and delivering the segment holds besides those. */
  long additionalBytes();

  /** Writes the segment; a media segment's production time ends the timer's lap. */
  ProducedSegment write(ProductionTimer timer);

  /** The initialization segment, delivered exactly as read. */
  record Initialization(@NonNull InitializationSegment segment) implements ClosedSegment {

    @Override
    public long heldBytes() {
      return segment.bytes().length;
    }

    @Override
    public long additionalBytes() {
      return 0;
    }

    @Override
    public ProducedSegment write(ProductionTimer timer) {
      return ProducedSegment.of(segment);
    }
  }
}
