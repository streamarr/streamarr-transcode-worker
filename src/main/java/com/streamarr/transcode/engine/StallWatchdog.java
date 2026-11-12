package com.streamarr.transcode.engine;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Detects an encoder stall: FFmpeg writing nothing to its standard output for the stall timeout
 * while the producer reads it. The producer pauses the watchdog while its reader waits for the
 * producer rather than for FFmpeg and while the worker pauses FFmpeg, and ends it once the attempt
 * no longer needs watching. Pauses may overlap, and watching resumes once every pause has ended.
 *
 * <p>A watchdog that also watches FFmpeg's reading counts the bytes that FFmpeg reads as progress:
 * such an FFmpeg has stalled once it has neither written nor read for the stall timeout. The
 * watchdog samples the bytes read only while it watches, several times within the stall timeout,
 * and dates bytes that a sample finds to the sample before it, the earliest moment they can have
 * been read at. An FFmpeg that stops reading therefore stalls within the stall timeout of its last
 * read, as one that stops writing does of its last byte. A sample that the system delays dates them
 * no earlier than one sample interval before itself, so an FFmpeg that a sample finds reading never
 * stalls sooner than the stall timeout less that interval after it.
 */
final class StallWatchdog {

  private static final int READ_SAMPLES_PER_STALL_TIMEOUT = 10;
  // The worker reports an attempt's progress to the server once a second.
  private static final Duration LONGEST_READ_SAMPLE_INTERVAL = Duration.ofSeconds(1);

  private final Duration stallTimeout;
  private final boolean watchesReads;
  private final Supplier<OptionalLong> bytesRead;
  private final long readSampleIntervalNanos;

  // When FFmpeg last wrote output, or the reader last resumed reading.
  private volatile long lastOutputNanos = System.nanoTime();
  // The bytes read that the latest sample found; 0 before any sample found a count.
  private volatile long sampledBytesRead;

  // Guarded by this monitor.
  private int pauses;
  private boolean ended;
  private long sampledAtNanos = lastOutputNanos;
  private long lastReadNanos = lastOutputNanos;

  StallWatchdog(Duration stallTimeout) {
    this(stallTimeout, false, OptionalLong::empty);
  }

  /**
   * A watchdog for an FFmpeg whose reading counts as progress too.
   *
   * @param bytesRead the bytes that FFmpeg has read so far, which grow only while it reads; empty
   *     while no count is known
   */
  static StallWatchdog watchingReads(Duration stallTimeout, Supplier<OptionalLong> bytesRead) {
    return new StallWatchdog(stallTimeout, true, bytesRead);
  }

  private StallWatchdog(
      Duration stallTimeout, boolean watchesReads, Supplier<OptionalLong> bytesRead) {
    this.stallTimeout = stallTimeout;
    this.watchesReads = watchesReads;
    this.bytesRead = bytesRead;
    this.readSampleIntervalNanos =
        Math.min(
            stallTimeout.dividedBy(READ_SAMPLES_PER_STALL_TIMEOUT).toNanos(),
            LONGEST_READ_SAMPLE_INTERVAL.toNanos());
  }

  /** Whether the bytes that FFmpeg reads count as its progress too. */
  boolean watchesReads() {
    return watchesReads;
  }

  /** The bytes read that the latest sample found; 0 for a watchdog that watches no reading. */
  long sampledBytesRead() {
    return sampledBytesRead;
  }

  /** FFmpeg's standard output, noting when each byte arrives. */
  InputStream watch(InputStream output) {
    return new WatchedOutput(output);
  }

  synchronized void pause() {
    pauses++;
  }

  /** Ends one pause, and resumes watching once none lasts, counting the stall timeout afresh. */
  synchronized void resume() {
    pauses--;
    if (pauses == 0) {
      lastOutputNanos = System.nanoTime();
      notifyAll();
    }
  }

  synchronized void end() {
    ended = true;
    notifyAll();
  }

  /**
   * Returns true once FFmpeg has written nothing, and read nothing that the watchdog counts, for
   * the stall timeout while the watchdog was neither paused nor ended, and false once it ends.
   * Nothing interrupts the producer's watchdog thread; an interrupt ends the watch.
   */
  synchronized boolean awaitStall() {
    while (!ended) {
      var silence = silence();
      if (pauses == 0 && silence >= stallTimeout.toNanos()) {
        return true;
      }

      if (!tryAwaitWake(silence)) {
        return false;
      }
    }

    return false;
  }

  // Holds this monitor. How long FFmpeg has neither written nor, as far as the samples show, read.
  private long silence() {
    if (watchesReads && pauses == 0) {
      sampleBytesRead();
    }

    var now = System.nanoTime();
    return Math.min(now - lastOutputNanos, now - lastReadNanos);
  }

  // Holds this monitor. Bytes read since the sample before were read at or after that sample. When
  // the system ran the watchdog late, the time it did not look is its own delay, not FFmpeg's
  // silence, so the bytes are dated no earlier than one interval before this sample.
  private void sampleBytesRead() {
    var sampled = bytesRead.get().orElse(sampledBytesRead);
    var now = System.nanoTime();
    if (sampled > sampledBytesRead) {
      sampledBytesRead = sampled;
      lastReadNanos = now - Math.min(now - sampledAtNanos, readSampleIntervalNanos);
    }

    sampledAtNanos = now;
  }

  // Holds this monitor. False when interrupted.
  @SuppressWarnings({"java:S2273", "java:S2274"}) // Called in awaitStall's loop.
  private boolean tryAwaitWake(long silence) {
    try {
      if (pauses > 0) {
        wait();
        return true;
      }

      TimeUnit.NANOSECONDS.timedWait(this, untilTheNextLook(stallTimeout.toNanos() - silence));
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private long untilTheNextLook(long untilStalled) {
    if (!watchesReads) {
      return untilStalled;
    }

    return Math.min(untilStalled, readSampleIntervalNanos);
  }

  private final class WatchedOutput extends FilterInputStream {

    private WatchedOutput(InputStream output) {
      super(output);
    }

    @Override
    public int read() throws IOException {
      var value = super.read();
      if (value >= 0) {
        lastOutputNanos = System.nanoTime();
      }

      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      var read = super.read(buffer, offset, length);
      if (read > 0) {
        lastOutputNanos = System.nanoTime();
      }

      return read;
    }
  }
}
