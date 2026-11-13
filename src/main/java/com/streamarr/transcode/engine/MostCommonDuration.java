package com.streamarr.transcode.engine;

import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/** Finds the duration that the most samples of a track share. */
final class MostCommonDuration {

  private MostCommonDuration() {}

  /**
   * The duration that the most samples share, the shorter of two as common; empty when no sample is
   * counted.
   *
   * @param sampleCounts how many samples last each duration
   */
  static Optional<Long> of(Map<Long, Long> sampleCounts) {
    return sampleCounts.entrySet().stream()
        .max(
            Entry.<Long, Long>comparingByValue()
                .thenComparing(Entry.comparingByKey(Comparator.reverseOrder())))
        .map(Entry::getKey);
  }
}
