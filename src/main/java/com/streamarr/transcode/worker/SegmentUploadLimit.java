package com.streamarr.transcode.worker;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The UploadSegment calls that the server accepts from this worker at once (ADR 0037). A segment
 * beyond them waits for a call to end, in the order the segments arrived, and a limit of 0 lets
 * every segment open its call at once.
 */
final class SegmentUploadLimit {

  private final long limit;
  // Guarded by this monitor.
  private final Deque<Turn> queue = new ArrayDeque<>();
  private int openCalls;

  /** A limit of this many calls, read as the contract's unsigned 32-bit count. */
  SegmentUploadLimit(int unsignedLimit) {
    limit = Integer.toUnsignedLong(unsignedLimit);
  }

  /** A new turn to open one segment's call, which waits only once awaited. */
  Turn turn() {
    return new Turn();
  }

  /** The segments that wait for a call to end. */
  synchronized int queued() {
    return queue.size();
  }

  // Holds this monitor.
  private boolean admits(Turn turn) {
    return openCalls < limit && queue.peekFirst() == turn;
  }

  /**
   * One segment's turn to open its upload call. The producer awaits it before it opens the call and
   * closes it once the call has ended, while a stop of the attempt or the end of the worker session
   * cancels it from another thread.
   */
  final class Turn implements AutoCloseable {

    // Guarded by the limit's monitor.
    private boolean cancelled;
    private boolean holdsCall;

    private Turn() {}

    /**
     * Waits until the segment may open its call, and counts that call as open.
     *
     * @throws TimeoutException when no call ended within the timeout
     * @throws CancellationException when the turn was cancelled first
     */
    Turn await(Duration timeout) throws InterruptedException, TimeoutException {
      synchronized (SegmentUploadLimit.this) {
        if (limit == 0) {
          return this;
        }

        queue.addLast(this);
        try {
          awaitAdmission(timeout);
        } finally {
          queue.remove(this);
          SegmentUploadLimit.this.notifyAll();
        }

        openCalls++;
        holdsCall = true;
        return this;
      }
    }

    // Holds the limit's monitor.
    private void awaitAdmission(Duration timeout) throws InterruptedException, TimeoutException {
      var deadline = System.nanoTime() + timeout.toNanos();
      while (!cancelled && !admits(this)) {
        var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new TimeoutException("No segment upload ended within " + timeout);
        }

        TimeUnit.NANOSECONDS.timedWait(SegmentUploadLimit.this, remaining);
      }

      if (cancelled) {
        throw new CancellationException("The job attempt stopped");
      }
    }

    void cancel() {
      synchronized (SegmentUploadLimit.this) {
        cancelled = true;
        SegmentUploadLimit.this.notifyAll();
      }
    }

    /** Ends the turn's call, which lets the next segment open its own. */
    @Override
    public void close() {
      synchronized (SegmentUploadLimit.this) {
        if (!holdsCall) {
          return;
        }

        holdsCall = false;
        openCalls--;
        SegmentUploadLimit.this.notifyAll();
      }
    }
  }
}
