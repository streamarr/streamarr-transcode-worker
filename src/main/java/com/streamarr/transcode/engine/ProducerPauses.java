package com.streamarr.transcode.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The tally that every producer of one engine keeps of its seeks' pauses and its holds: how many
 * began and ended, and how many producers have FFmpeg stopped now. A producer counts each under its
 * own lock, and continues a stopped FFmpeg before it exits, so a producer that ends leaves no
 * stopped one behind.
 */
public final class ProducerPauses {

  private final AtomicLong seekPauses = new AtomicLong();
  private final AtomicLong seekResumes = new AtomicLong();
  private final AtomicLong holds = new AtomicLong();
  private final AtomicLong holdReleases = new AtomicLong();
  private final AtomicLong stoppedProducers = new AtomicLong();

  /** The pauses for a seek that stopped, or kept stopped, a producer's FFmpeg. */
  public long seekPauses() {
    return seekPauses.get();
  }

  /** The pauses for a seek that ended. */
  public long seekResumes() {
    return seekResumes.get();
  }

  /** The holds that stopped, or kept stopped, a producer's FFmpeg. */
  public long holds() {
    return holds.get();
  }

  /** The holds that ended. */
  public long holdReleases() {
    return holdReleases.get();
  }

  /** The producers whose FFmpeg a seek's pause or a hold stops now. */
  public long stoppedProducers() {
    return stoppedProducers.get();
  }

  void seekPaused() {
    seekPauses.incrementAndGet();
  }

  void seekResumed() {
    seekResumes.incrementAndGet();
  }

  void held() {
    holds.incrementAndGet();
  }

  void holdReleased() {
    holdReleases.incrementAndGet();
  }

  void stopped() {
    stoppedProducers.incrementAndGet();
  }

  void continued() {
    stoppedProducers.decrementAndGet();
  }
}
