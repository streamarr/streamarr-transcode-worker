package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.Optional;

/**
 * The video track an initialization segment declares, and the rules that read a fragment's video
 * start from its {@code moof}.
 */
record VideoTrack(long trackId, long timescale, int defaultSampleFlags) {

  private static final String VIDEO_HANDLER = "vide";
  private static final int SAMPLE_IS_NON_SYNC_SAMPLE = 0x0001_0000;

  private static final int TFHD_BASE_DATA_OFFSET = 0x000001;
  private static final int TFHD_SAMPLE_DESCRIPTION_INDEX = 0x000002;
  private static final int TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
  private static final int TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
  private static final int TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;

  private static final int TRUN_DATA_OFFSET = 0x000001;
  private static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
  private static final int TRUN_SAMPLE_DURATION = 0x000100;
  private static final int TRUN_SAMPLE_SIZE = 0x000200;
  private static final int TRUN_SAMPLE_FLAGS = 0x000400;
  private static final int TRUN_SAMPLE_COMPOSITION_TIME_OFFSET = 0x000800;

  /** Finds the single video track of a {@code moov}; empty when it declares none. */
  static Optional<VideoTrack> of(NestedBox moov) {
    var videoTraks =
        moov.children("trak").stream()
            .filter(trak -> handlerOf(trak).equals(VIDEO_HANDLER))
            .toList();
    if (videoTraks.size() > 1) {
      throw new FragmentedMp4Exception(
          Reason.MULTIPLE_VIDEO_TRACKS, "moov declares " + videoTraks.size() + " video tracks");
    }

    return videoTraks.stream().findFirst().map(trak -> fromTrak(moov, trak));
  }

  /**
   * Reads the presentation time and sync status of this track's first sample in a {@code moof};
   * empty when the fragment carries no sample of this track.
   */
  Optional<VideoStart> startOf(NestedBox moof) {
    return moof.children("traf").stream()
        .map(this::startOfTrackFragment)
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static VideoTrack fromTrak(NestedBox moov, NestedBox trak) {
    var trackId = trackIdOf(trak.requiredChild("tkhd"));
    var timescale = timescaleOf(trak.requiredChild("mdia").requiredChild("mdhd"));
    var trackExtends =
        moov.requiredChild("mvex").children("trex").stream()
            .map(TrackExtends::of)
            .filter(trex -> trex.trackId() == trackId)
            .findFirst()
            .orElseThrow(
                () -> FragmentedMp4Exception.malformed("mvex holds no trex for track " + trackId));
    return new VideoTrack(trackId, timescale, trackExtends.defaultSampleFlags());
  }

  private static String handlerOf(NestedBox trak) {
    return trak.requiredChild("mdia").requiredChild("hdlr").fields().skip(8).fourcc();
  }

  private static long trackIdOf(NestedBox tkhd) {
    var fields = tkhd.fields();
    var version = fields.u8();
    return fields.skip(3).skip(creationAndModificationTimeBytes(version)).u32();
  }

  private static long timescaleOf(NestedBox mdhd) {
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

  private Optional<VideoStart> startOfTrackFragment(NestedBox traf) {
    var header = TrackFragmentHeader.of(traf.requiredChild("tfhd"));
    if (header.trackId() != trackId) {
      return Optional.empty();
    }

    return traf.children("trun").stream()
        .map(VideoTrack::firstSampleOf)
        .flatMap(Optional::stream)
        .findFirst()
        .map(sample -> videoStart(traf, header, sample));
  }

  private VideoStart videoStart(NestedBox traf, TrackFragmentHeader header, FirstSample sample) {
    var flags = sample.flags().or(header::defaultSampleFlags).orElse(defaultSampleFlags);
    var presentationTime = addExact(baseMediaDecodeTimeOf(traf), sample.compositionOffset());
    return new VideoStart(presentationTime, timescale, !isSet(flags, SAMPLE_IS_NON_SYNC_SAMPLE));
  }

  private static Optional<FirstSample> firstSampleOf(NestedBox trun) {
    var fields = trun.fields();
    var version = fields.u8();
    var flags = fields.u24();
    var sampleCount = fields.u32();
    var firstSampleFlags =
        fields
            .skipIf(isSet(flags, TRUN_DATA_OFFSET), 4)
            .s32If(isSet(flags, TRUN_FIRST_SAMPLE_FLAGS));
    if (sampleCount == 0) {
      return Optional.empty();
    }

    var sampleFlags =
        fields
            .skipIf(isSet(flags, TRUN_SAMPLE_DURATION), 4)
            .skipIf(isSet(flags, TRUN_SAMPLE_SIZE), 4)
            .s32If(isSet(flags, TRUN_SAMPLE_FLAGS));
    var compositionOffset = compositionOffsetOf(fields, flags, version);
    return Optional.of(new FirstSample(firstSampleFlags.or(() -> sampleFlags), compositionOffset));
  }

  private static long compositionOffsetOf(BoxFields fields, int flags, int version) {
    if (!isSet(flags, TRUN_SAMPLE_COMPOSITION_TIME_OFFSET)) {
      return 0;
    }

    if (version == 0) {
      return fields.u32();
    }

    return fields.s32();
  }

  private static long baseMediaDecodeTimeOf(NestedBox traf) {
    var fields = traf.requiredChild("tfdt").fields();
    var version = fields.u8();
    fields.skip(3);
    if (version == 1) {
      return fields.s64();
    }

    return fields.u32();
  }

  private static boolean isSet(int flags, int flag) {
    return (flags & flag) != 0;
  }

  private static long addExact(long decodeTime, long compositionOffset) {
    try {
      return Math.addExact(decodeTime, compositionOffset);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed("presentation time overflows a signed 64-bit value");
    }
  }

  private record TrackExtends(long trackId, int defaultSampleFlags) {

    static TrackExtends of(NestedBox trex) {
      var fields = trex.fields().skip(4);
      var trackId = fields.u32();
      return new TrackExtends(trackId, fields.skip(12).s32());
    }
  }

  private record TrackFragmentHeader(long trackId, Optional<Integer> defaultSampleFlags) {

    static TrackFragmentHeader of(NestedBox tfhd) {
      var fields = tfhd.fields().skip(1);
      var flags = fields.u24();
      var trackId = fields.u32();
      var defaultSampleFlags =
          fields
              .skipIf(isSet(flags, TFHD_BASE_DATA_OFFSET), 8)
              .skipIf(isSet(flags, TFHD_SAMPLE_DESCRIPTION_INDEX), 4)
              .skipIf(isSet(flags, TFHD_DEFAULT_SAMPLE_DURATION), 4)
              .skipIf(isSet(flags, TFHD_DEFAULT_SAMPLE_SIZE), 4)
              .s32If(isSet(flags, TFHD_DEFAULT_SAMPLE_FLAGS));
      return new TrackFragmentHeader(trackId, defaultSampleFlags);
    }
  }

  private record FirstSample(Optional<Integer> flags, long compositionOffset) {}
}
