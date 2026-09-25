package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.BoxFields.isSet;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.TrackRun.FirstSample;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The video track an initialization segment declares, and the rules that read a fragment's video
 * start from its {@code moof}.
 */
record VideoTrack(long trackId, long timescale, int defaultSampleFlags) {

  private static final String VIDEO_HANDLER = "vide";
  private static final int SAMPLE_IS_NON_SYNC_SAMPLE = 0x0001_0000;

  /**
   * Finds the single video track of a {@code moov}, with its {@code trex} defaults; empty when it
   * declares none.
   */
  static Optional<VideoTrack> of(BoxView moov, Map<Long, TrackExtends> trackExtends) {
    var videoTraks =
        moov.children("trak").stream()
            .filter(trak -> handlerOf(trak).equals(VIDEO_HANDLER))
            .toList();
    if (videoTraks.size() > 1) {
      throw new FragmentedMp4Exception(
          Reason.MULTIPLE_VIDEO_TRACKS, "moov declares " + videoTraks.size() + " video tracks");
    }

    return videoTraks.stream().findFirst().map(trak -> fromTrak(trak, trackExtends));
  }

  /**
   * Reads the presentation time and sync status of this track's first sample in a fragment's {@code
   * traf}s; empty when the fragment carries no sample of this track.
   */
  Optional<VideoStart> startOf(List<TrackFragmentBox> trackFragments) {
    return trackFragments.stream()
        .filter(trackFragment -> trackFragment.header().trackId() == trackId)
        .map(this::startOfTrackFragment)
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static VideoTrack fromTrak(BoxView trak, Map<Long, TrackExtends> trackExtends) {
    var trackId = trackIdOf(trak.requiredChild("tkhd"));
    var timescale = timescaleOf(trak.requiredChild("mdia").requiredChild("mdhd"));
    var defaults =
        Optional.ofNullable(trackExtends.get(trackId))
            .orElseThrow(
                () -> FragmentedMp4Exception.malformed("mvex holds no trex for track " + trackId));
    return new VideoTrack(trackId, timescale, defaults.defaultSampleFlags());
  }

  private static String handlerOf(BoxView trak) {
    return trak.requiredChild("mdia").requiredChild("hdlr").fields().skip(8).fourcc();
  }

  private static long trackIdOf(BoxView tkhd) {
    var fields = tkhd.fields();
    var version = fields.u8();
    return fields.skip(3).skip(creationAndModificationTimeBytes(version)).u32();
  }

  private static long timescaleOf(BoxView mdhd) {
    var fields = mdhd.fields();
    var version = fields.u8();
    var timescale = fields.skip(3).skip(creationAndModificationTimeBytes(version)).u32();
    if (timescale == 0) {
      throw FragmentedMp4Exception.malformed("mdhd declares a timescale of zero");
    }

    return timescale;
  }

  private static int creationAndModificationTimeBytes(int version) {
    if (version == 1) {
      return 16;
    }

    return 8;
  }

  private Optional<VideoStart> startOfTrackFragment(TrackFragmentBox trackFragment) {
    return trackFragment.runs().stream()
        .map(TrackRun::firstSample)
        .flatMap(Optional::stream)
        .findFirst()
        .map(sample -> videoStart(trackFragment, sample));
  }

  private VideoStart videoStart(TrackFragmentBox trackFragment, FirstSample sample) {
    var flags =
        sample.flags().or(trackFragment.header()::defaultSampleFlags).orElse(defaultSampleFlags);
    var presentationTime =
        addExact(baseMediaDecodeTimeOf(trackFragment.traf()), sample.compositionOffset());
    return new VideoStart(presentationTime, timescale, !isSet(flags, SAMPLE_IS_NON_SYNC_SAMPLE));
  }

  private static long baseMediaDecodeTimeOf(BoxView traf) {
    var fields = traf.requiredChild("tfdt").fields();
    var version = fields.u8();
    fields.skip(3);
    if (version == 1) {
      return fields.s64();
    }

    return fields.u32();
  }

  private static long addExact(long decodeTime, long compositionOffset) {
    try {
      return Math.addExact(decodeTime, compositionOffset);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed("presentation time overflows a signed 64-bit value");
    }
  }
}
