package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/**
 * One {@code traf} of a {@code moof} with its {@code tfhd} and {@code trun}s, read once for every
 * rule that reads the fragment.
 */
record TrackFragmentBox(
    @NonNull BoxView traf, @NonNull TrackFragmentHeader header, @NonNull List<TrackRun> runs) {

  /** Reads every {@code traf} of a {@code moof}, in order. */
  static List<TrackFragmentBox> allOf(BoxView moof) {
    return moof.children("traf").stream().map(TrackFragmentBox::of).toList();
  }

  private static TrackFragmentBox of(BoxView traf) {
    var header = TrackFragmentHeader.of(traf.requiredChild("tfhd"));
    return new TrackFragmentBox(
        traf, header, traf.children("trun").stream().map(TrackRun::of).toList());
  }
}
