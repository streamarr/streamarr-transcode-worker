package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.SegmentUploadLimitReached;
import com.google.rpc.Code;
import com.streamarr.transcode.engine.MonotonicClock;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.util.Comparator;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The UploadSegment calls that the server accepts from this worker at once (ADR 0037). A segment
 * beyond them waits for a call to end, in the order the segments arrived, and a limit of 0 lets
 * every segment open its call at once. A segment whose call the server refused at this limit waits
 * again in its first place in that order, and opens its call again after a delay.
 */
final class SegmentUploadLimit {

  // The server frees the place of a call that the worker cancelled once it has stored that call's
  // segment, and nothing tells the worker when.
  private static final long FIRST_REOPEN_DELAY_NANOS = Duration.ofMillis(10).toNanos();
  private static final long LONGEST_REOPEN_DELAY_NANOS = Duration.ofSeconds(1).toNanos();

  private final long limit;
  private final MonotonicClock clock;
  // Guarded by this monitor. In the order the segments first asked for a call.
  private final Queue<Turn> queue = new PriorityQueue<>(Comparator.comparingLong(Turn::arrival));
  private long arrivals;
  private int openCalls;

  /**
   * A limit of this many calls, read as the contract's unsigned 32-bit count, whose waits and
   * delays the clock times.
   */
  SegmentUploadLimit(int unsignedLimit, MonotonicClock clock) {
    limit = Integer.toUnsignedLong(unsignedLimit);
    this.clock = clock;
  }

  /**
   * Whether the server refused a call because the worker already holds this limit of calls, as the
   * marker in the refusal's status details states. Any other refusal comes from the server's
   * fleet-wide limits.
   */
  static boolean isRefusalAtTheLimit(Throwable failure) {
    var status = StatusProto.fromThrowable(failure);
    if (status == null || status.getCode() != Code.RESOURCE_EXHAUSTED_VALUE) {
      return false;
    }

    return status.getDetailsList().stream()
        .anyMatch(detail -> detail.is(SegmentUploadLimitReached.class));
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
  private boolean admits(Turn turn, long now) {
    return openCalls < limit && queue.peek() == turn && turn.isPastItsReopenTime(now);
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
    private long reopenTime = clock.nanoTime();
    private long reopenDelayNanos = FIRST_REOPEN_DELAY_NANOS;
    private OptionalLong refusalDeadline = OptionalLong.empty();
    private long arrival;

    private Turn() {}

    // Holds the limit's monitor.
    private long arrival() {
      return arrival;
    }

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

        arrival = arrivals++;
        queue.add(this);
        awaitCallInQueue(clock.nanoTime() + timeout.toNanos());
        return this;
      }
    }

    /**
     * Ends the turn's call, which the server refused at the limit, and waits again in the queue,
     * ahead of every segment that asked for a call after it. The server frees the place of a call
     * that the worker cancelled only once it has stored that call's segment, so it can still hold a
     * call that the worker no longer counts. The segment therefore opens its call again after a
     * delay, which doubles with each refusal up to 1 s, and keeps the segments behind it waiting.
     * Its waits after refusals end within one timeout of its first refusal.
     *
     * @return false, having waited for nothing, when there is no limit to wait for
     * @throws TimeoutException when the segment opened no call within the timeout
     * @throws CancellationException when the turn was cancelled first
     */
    boolean tryAwaitAgainInItsPlace(Duration timeout)
        throws InterruptedException, TimeoutException {
      synchronized (SegmentUploadLimit.this) {
        if (limit == 0) {
          return false;
        }

        holdsCall = false;
        openCalls--;
        var now = clock.nanoTime();
        if (refusalDeadline.isEmpty()) {
          refusalDeadline = OptionalLong.of(now + timeout.toNanos());
        }

        reopenTime = now + reopenDelayNanos;
        reopenDelayNanos = Math.min(2 * reopenDelayNanos, LONGEST_REOPEN_DELAY_NANOS);
        queue.add(this);
        SegmentUploadLimit.this.notifyAll();
        awaitCallInQueue(refusalDeadline.getAsLong());
        return true;
      }
    }

    // Holds the limit's monitor, with this turn in the queue.
    private void awaitCallInQueue(long deadline) throws InterruptedException, TimeoutException {
      try {
        awaitAdmission(deadline);
      } finally {
        queue.remove(this);
        SegmentUploadLimit.this.notifyAll();
      }

      openCalls++;
      holdsCall = true;
    }

    // Holds the limit's monitor.
    private boolean isPastItsReopenTime(long now) {
      return now - reopenTime >= 0;
    }

    // Holds the limit's monitor. A turn that a call's end admits only after its deadline still
    // times out.
    private void awaitAdmission(long deadline) throws InterruptedException, TimeoutException {
      var now = clock.nanoTime();
      while (!cancelled && deadline - now > 0 && !admits(this, now)) {
        TimeUnit.NANOSECONDS.timedWait(
            SegmentUploadLimit.this, Math.min(deadline - now, nanosUntilReopenTime(now)));
        now = clock.nanoTime();
      }

      if (cancelled) {
        throw new CancellationException("The job attempt stopped");
      }

      if (deadline - now <= 0) {
        throw new TimeoutException("The segment found no upload call that the server accepts");
      }
    }

    // Holds the limit's monitor. Past that time only a notification can admit the turn.
    private long nanosUntilReopenTime(long now) {
      var untilReopen = reopenTime - now;
      if (untilReopen <= 0) {
        return Long.MAX_VALUE;
      }

      return untilReopen;
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
        reopenTheEarliestDelayedTurnNow();
        SegmentUploadLimit.this.notifyAll();
      }
    }
  }

  // Holds this monitor. The end of one of the worker's calls can free a place on the server, so the
  // earliest refused segment that still waits out its delay opens its call again without waiting
  // it out. Each end reopens a different segment, even when several end before any reopened
  // segment resumes. The delay stays for a place that frees without such an end.
  private void reopenTheEarliestDelayedTurnNow() {
    var now = clock.nanoTime();
    queue.stream()
        .filter(turn -> !turn.isPastItsReopenTime(now))
        .min(Comparator.comparingLong(Turn::arrival))
        .ifPresent(turn -> turn.reopenTime = now);
  }
}
