package com.streamarr.transcode.worker;

/** How the worker sets its background probe capacity. */
public sealed interface BackgroundProbeCapacity {

  /** The worker adapts the capacity between 1 and the maximum, its processor count. */
  record Adaptive(int maximum) implements BackgroundProbeCapacity {

    public Adaptive {
      if (maximum < 1) {
        throw new IllegalArgumentException(
            "The maximum background probe capacity must be positive");
      }
    }
  }

  /** A worker setting fixes the capacity. */
  record Fixed(int capacity) implements BackgroundProbeCapacity {

    public Fixed {
      if (capacity < 1) {
        throw new IllegalArgumentException("A fixed background probe capacity must be positive");
      }
    }
  }
}
