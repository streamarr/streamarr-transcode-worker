package com.streamarr.transcode.engine;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The sample defaults a {@code trex} declares for one track's fragments. */
record TrackExtends(long trackId, long defaultSampleSize, int defaultSampleFlags) {

  /**
   * Reads every {@code trex} of a {@code moov}'s {@code mvex}, by track.
   *
   * @throws FragmentedMp4Exception when a {@code trex} ends before its fields do, or when two
   *     declare the same track
   */
  static Map<Long, TrackExtends> byTrackIdIn(BoxView moov) {
    return moov.children("mvex").stream()
        .flatMap(mvex -> mvex.children("trex").stream())
        .map(TrackExtends::of)
        .collect(
            Collectors.toUnmodifiableMap(
                TrackExtends::trackId, Function.identity(), TrackExtends::duplicate));
  }

  private static TrackExtends of(BoxView trex) {
    var fields = trex.fields().skip(4);
    var trackId = fields.u32();
    var defaultSampleSize = fields.skip(8).u32();
    return new TrackExtends(trackId, defaultSampleSize, fields.s32());
  }

  private static TrackExtends duplicate(TrackExtends first, TrackExtends second) {
    throw FragmentedMp4Exception.malformed("mvex declares track " + first.trackId() + " twice");
  }
}
