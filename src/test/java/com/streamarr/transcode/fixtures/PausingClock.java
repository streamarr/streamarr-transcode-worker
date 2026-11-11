package com.streamarr.transcode.fixtures;

import com.streamarr.transcode.engine.MonotonicClock;
import java.util.concurrent.CountDownLatch;

/** A clock that holds one reading back until the test resumes it. */
public final class PausingClock implements MonotonicClock {

  private final CountDownLatch paused = new CountDownLatch(1);
  private final CountDownLatch resumed = new CountDownLatch(1);
  private volatile boolean pausing;

  public void pauseAtTheNextReading() {
    pausing = true;
  }

  public void awaitPaused() throws InterruptedException {
    paused.await();
  }

  public void resume() {
    resumed.countDown();
  }

  @Override
  public long nanoTime() {
    if (pausing) {
      pausing = false;
      paused.countDown();
      awaitResumed();
    }

    return System.nanoTime();
  }

  private void awaitResumed() {
    try {
      resumed.await();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while the clock held a reading back", interruption);
    }
  }
}
