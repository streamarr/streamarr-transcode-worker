package com.streamarr.transcode.fixtures;

import java.time.Duration;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Starts both sides of a race in a concurrency test together. */
public final class Races {

  private static final Duration START_LIMIT = Duration.ofSeconds(10);

  private Races() {}

  /** Returns once the race's other side is ready too, and fails the test when it never is. */
  public static void awaitStart(CyclicBarrier start) {
    try {
      start.await(START_LIMIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted at the race's start", e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new AssertionError("the race never started", e);
    }
  }
}
