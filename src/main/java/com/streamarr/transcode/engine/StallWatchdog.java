package com.streamarr.transcode.engine;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Detects an encoder stall: FFmpeg writing nothing to its standard output for the stall timeout
 * while the producer reads it. The producer pauses the watchdog while its reader waits for the
 * producer rather than for FFmpeg, and ends it once the attempt no longer needs watching.
 */
final class StallWatchdog {

  private final Duration stallTimeout;

  // When FFmpeg last wrote output, or the reader last resumed reading.
  private volatile long lastOutputNanos = System.nanoTime();

  // Guarded by this monitor.
  private boolean paused;
  private boolean ended;

  StallWatchdog(Duration stallTimeout) {
    this.stallTimeout = stallTimeout;
  }

  /** FFmpeg's standard output, noting when each byte arrives. */
  InputStream watch(InputStream output) {
    return new WatchedOutput(output);
  }

  synchronized void pause() {
    paused = true;
  }

  /** Resumes watching, counting the stall timeout afresh. */
  synchronized void resume() {
    paused = false;
    lastOutputNanos = System.nanoTime();
    notifyAll();
  }

  synchronized void end() {
    ended = true;
    notifyAll();
  }

  /**
   * Returns true once FFmpeg has written nothing for the stall timeout while the watchdog was
   * neither paused nor ended, and false once it ends. Nothing interrupts the producer's watchdog
   * thread; an interrupt ends the watch.
   */
  synchronized boolean awaitStall() {
    while (!ended) {
      var silence = System.nanoTime() - lastOutputNanos;
      if (!paused && silence >= stallTimeout.toNanos()) {
        return true;
      }

      if (!tryAwaitWake(silence)) {
        return false;
      }
    }

    return false;
  }

  // Holds this monitor. False when interrupted.
  private boolean tryAwaitWake(long silence) {
    try {
      if (paused) {
        wait();
        return true;
      }

      TimeUnit.NANOSECONDS.timedWait(this, stallTimeout.toNanos() - silence);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
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
