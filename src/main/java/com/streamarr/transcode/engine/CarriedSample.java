package com.streamarr.transcode.engine;

import java.util.stream.Stream;
import lombok.NonNull;

/** A sample and the fragment whose mdat holds its bytes. */
record CarriedSample(@NonNull Fragment fragment, @NonNull Sample sample) {

  static Stream<CarriedSample> videoOf(Fragment fragment) {
    return fragment.videoSamples().stream().map(sample -> new CarriedSample(fragment, sample));
  }

  static Stream<CarriedSample> audioOf(Fragment fragment) {
    return fragment.audioSamples().stream().map(sample -> new CarriedSample(fragment, sample));
  }

  boolean follows(Fragment previous, int end) {
    return fragment == previous && sample.mediaDataPosition() == end;
  }

  long videoTrackId() {
    return fragment.tracks().video().orElseThrow().trackId();
  }

  long audioTrackId() {
    return fragment.tracks().audio().orElseThrow().trackId();
  }
}
