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

  /**
   * The decode time of the {@code traf}'s first sample, which its {@code tfdt} declares: unsigned
   * in version 0 and signed in version 1, where FFmpeg writes the AAC priming frame before zero.
   *
   * @throws FragmentedMp4Exception when the {@code traf} holds no {@code tfdt}
   */
  long baseMediaDecodeTime() {
    var fields = traf.requiredChild("tfdt").fields();
    var version = fields.u8();
    fields.skip(3);
    if (version == 1) {
      return fields.s64();
    }

    return fields.u32();
  }

  private static TrackFragmentBox of(BoxView traf) {
    var header = TrackFragmentHeader.of(traf.requiredChild("tfhd"));
    return new TrackFragmentBox(
        traf, header, traf.children("trun").stream().map(TrackRun::of).toList());
  }
}
