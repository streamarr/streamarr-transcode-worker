package com.streamarr.transcode.engine;

import java.util.List;
import java.util.Optional;

/** Fragments for tests that group them or compare them without reading their boxes. */
final class Fragments {

  private Fragments() {}

  /** A fragment of two one-byte boxes that carries no sample, for the caller to adjust. */
  static Fragment.FragmentBuilder fragmentBuilder() {
    return Fragment.builder()
        .boxes(List.of(new byte[] {1}, new byte[] {2}))
        .tracks(new MovieTracks(Optional.empty(), Optional.empty()))
        .videoSamples(List.of())
        .audioSamples(List.of());
  }
}
