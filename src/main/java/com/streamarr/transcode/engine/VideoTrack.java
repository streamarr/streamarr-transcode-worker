package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.BoxFields.isSet;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The video track an initialization segment declares, and the rule that reads a fragment's video
 * start from its first video sample.
 */
record VideoTrack(long trackId, long timescale) {

  private static final String VIDEO_HANDLER = "vide";
  private static final int SAMPLE_IS_NON_SYNC_SAMPLE = 0x0001_0000;

  /**
   * Finds the single video track of a {@code moov}, which must declare its {@code trex} defaults;
   * empty when it declares none.
   */
  static Optional<VideoTrack> of(BoxView moov, Map<Long, TrackExtends> trackExtends) {
    var videoTraks =
        moov.children("trak").stream()
            .map(TrackBox::new)
            .filter(trak -> trak.handler().equals(VIDEO_HANDLER))
            .toList();
    if (videoTraks.size() > 1) {
      throw new FragmentedMp4Exception(
          Reason.MULTIPLE_VIDEO_TRACKS, "moov declares " + videoTraks.size() + " video tracks");
    }

    return videoTraks.stream().findFirst().map(trak -> fromTrak(trak, trackExtends));
  }

  /**
   * The presentation time and sync status of the first of a fragment's samples of this track; empty
   * when the fragment carries none.
   */
  Optional<VideoStart> startOf(List<Sample> samples) {
    return samples.stream().findFirst().map(this::videoStart);
  }

  private static VideoTrack fromTrak(TrackBox trak, Map<Long, TrackExtends> trackExtends) {
    var trackId = trak.trackId();
    if (!trackExtends.containsKey(trackId)) {
      throw FragmentedMp4Exception.malformed("mvex holds no trex for track " + trackId);
    }

    return new VideoTrack(trackId, trak.timescale());
  }

  private VideoStart videoStart(Sample sample) {
    var presentationTime = addExact(sample.decodeTime(), sample.compositionOffset());
    return new VideoStart(
        presentationTime, timescale, !isSet(sample.flags(), SAMPLE_IS_NON_SYNC_SAMPLE));
  }

  private static long addExact(long decodeTime, long compositionOffset) {
    try {
      return Math.addExact(decodeTime, compositionOffset);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed("presentation time overflows a signed 64-bit value");
    }
  }
}
