package com.streamarr.transcode.engine;

import java.util.function.BooleanSupplier;

/**
 * The segment memory of one worker: its slot count times each producer's own budget of two segment
 * caps. Every producer reserves each box of FFmpeg's output here as well as within its own budget,
 * and releases the bytes once it holds them no longer, so a stopping producer keeps its share until
 * it has released its segments.
 */
public final class SegmentMemoryBudget {

  private final long capacityBytes;

  // Guarded by this monitor.
  private long reservedBytes;

  private SegmentMemoryBudget(long capacityBytes) {
    this.capacityBytes = capacityBytes;
  }

  /** The budget of a worker that runs this many job attempts at once. */
  public static SegmentMemoryBudget forSlots(int slots) {
    if (slots <= 0) {
      throw new IllegalArgumentException("Slot count must be positive, got: " + slots);
    }

    return new SegmentMemoryBudget(slots * Producer.BUDGET_BYTES);
  }

  // Reserves the bytes when they fit now.
  synchronized boolean tryReserveAtOnce(long bytes) {
    if (reservedBytes + bytes > capacityBytes) {
      return false;
    }

    reservedBytes += bytes;
    return true;
  }

  // Waits until the bytes fit and reserves them; false once the waiter abandons them first. A
  // producer that abandons its reservation wakes the budget through release. Nothing interrupts a
  // producer's reader, and its caller learns of an interrupt once the wait ends.
  synchronized boolean tryReserve(long bytes, BooleanSupplier abandoned) {
    var interrupted = false;
    var reserved = tryReserveAtOnce(bytes);
    while (!reserved && !abandoned.getAsBoolean()) {
      interrupted |= !tryWait();
      reserved = tryReserveAtOnce(bytes);
    }

    if (interrupted) {
      Thread.currentThread().interrupt();
    }

    return reserved;
  }

  // Holds this monitor. False when an interrupt ended the wait.
  private boolean tryWait() {
    try {
      wait();
      return true;
    } catch (InterruptedException _) {
      return false;
    }
  }

  // Releases the bytes, which may be none, and wakes every waiting reader to look again.
  synchronized void release(long bytes) {
    reservedBytes -= bytes;
    notifyAll();
  }
}
