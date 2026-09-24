package com.streamarr.transcode.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * The segment memory of one worker: its slot count times each producer's own budget of two segment
 * caps. Every producer reserves each box of FFmpeg's output here as well as within its own budget,
 * and releases the bytes once it holds them no longer, so a stopping producer keeps its share until
 * it has released its segments.
 *
 * <p>Readers that wait for memory reserve it in the order they began to wait, and no reader
 * reserves while another waits, so a waiting reader never waits for a reader that asks after it.
 */
public final class SegmentMemoryBudget {

  private final long capacityBytes;

  // Guarded by this monitor. Each waiting reader's place, first in the order they began to wait.
  private final Deque<Object> waiting = new ArrayDeque<>();
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

  // Reserves the bytes when they fit now and no reader waits for memory.
  synchronized boolean tryReserveAtOnce(long bytes) {
    return waiting.isEmpty() && tryReserveIfFits(bytes);
  }

  // Waits until every reader that began to wait earlier has reserved or withdrawn and the bytes
  // fit, then reserves them; false once the reservation is withdrawn first. A producer that
  // withdraws its reservation wakes the budget through release. Nothing interrupts a producer's
  // reader, and its caller learns of an interrupt once the wait ends.
  synchronized boolean tryReserve(long bytes, BooleanSupplier withdrawn) {
    var place = new Object();
    waiting.addLast(place);
    var interrupted = false;
    try {
      var reserved = tryReserveInTurn(place, bytes);
      while (!reserved && !withdrawn.getAsBoolean()) {
        interrupted |= !tryWait();
        reserved = tryReserveInTurn(place, bytes);
      }

      return reserved;
    } finally {
      waiting.remove(place);
      notifyAll();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // Holds this monitor.
  private boolean tryReserveInTurn(Object place, long bytes) {
    return waiting.peekFirst() == place && tryReserveIfFits(bytes);
  }

  // Holds this monitor.
  private boolean tryReserveIfFits(long bytes) {
    if (reservedBytes + bytes > capacityBytes) {
      return false;
    }

    reservedBytes += bytes;
    return true;
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
