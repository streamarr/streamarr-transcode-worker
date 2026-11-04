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

  /**
   * The initialization segment as the assembler rewrote it.
   *
   * @param readBytes the length of the segment that the reader read and holds; the rewrite can make
   *     the delivered segment shorter or longer
   */
  record Initialization(@NonNull InitializationSegment segment, long readBytes)
      implements ClosedSegment {

    @Override
    public long heldBytes() {
      return readBytes;
    }

    @Override
    public long additionalBytes() {
      return Math.max(0, segment.bytes().length - readBytes);
    }

    @Override
    public ProducedSegment write(ProductionTimer timer) {
      return ProducedSegment.of(segment);
    }
  }
}
