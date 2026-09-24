package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The default sample sizes an initialization segment declares per track, and the rule that every
 * sample a {@code moof} describes lies inside the body of the {@code mdat} that follows it.
 *
 * <p>Samples are placed as ISO/IEC 14496-12 (8.8.7, 8.8.8) defines: a {@code traf}'s base is its
 * {@code tfhd} base-data-offset, else the {@code moof} under default-base-is-moof, else the {@code
 * moof} for the first {@code traf} and the end of the previous {@code traf}'s data after it; a run
 * starts at that base plus its data offset, else where the previous run of the {@code traf} ended,
 * else at the base.
 */
final class SampleRanges {

  private final Map<Long, Long> defaultSampleSizes;

  private SampleRanges(Map<Long, Long> defaultSampleSizes) {
    this.defaultSampleSizes = defaultSampleSizes;
  }

  static SampleRanges of(BoxView moov) {
    var sizes = new HashMap<Long, Long>();
    for (var mvex : moov.children("mvex")) {
      for (var trex : mvex.children("trex")) {
        var fields = trex.fields().skip(4);
        var trackId = fields.u32();
        sizes.put(trackId, fields.skip(8).u32());
      }
    }

    return new SampleRanges(Map.copyOf(sizes));
  }

  /**
   * @throws FragmentedMp4Exception when a run that holds bytes lies outside the {@code mdat}'s
   *     body, or when a run's samples need a default size that no box declares
   */
  void requireInside(BoxView moof, MediaData mediaData) {
    var previousTrafEnd = 0L;
    for (var traf : moof.children("traf")) {
      previousTrafEnd = requireTrackFragmentInside(traf, previousTrafEnd, mediaData);
    }
  }

  /** Returns where the {@code traf}'s data ends, measured from the {@code moof}'s first byte. */
  private long requireTrackFragmentInside(BoxView traf, long previousTrafEnd, MediaData mediaData) {
    var header = TrackFragmentHeader.of(traf.requiredChild("tfhd"));
    var base = baseOf(header, previousTrafEnd, mediaData);
    var runEnd = base;
    for (var trun : traf.children("trun")) {
      var run = TrackRun.of(trun);
      var start = runEnd;
      var dataOffset = run.dataOffset();
      if (dataOffset.isPresent()) {
        start = offsetBy(base, dataOffset.orElseThrow());
      }

      var bytes = sampleBytesOf(run, () -> defaultSampleSizeOf(header));
      mediaData.requireInside(header.trackId(), start, bytes);
      runEnd = start + bytes;
    }

    return runEnd;
  }

  private static long baseOf(TrackFragmentHeader header, long previousTrafEnd, MediaData data) {
    if (header.baseDataOffset().isPresent()) {
      return offsetBy(header.baseDataOffset().getAsLong(), -data.moofPosition());
    }

    if (header.defaultBaseIsMoof()) {
      return 0;
    }

    return previousTrafEnd;
  }

  private static long offsetBy(long base, long offset) {
    try {
      return Math.addExact(base, offset);
    } catch (ArithmeticException _) {
      throw outside("a sample position overflows a signed 64-bit offset");
    }
  }

  private static long sampleBytesOf(TrackRun run, LongSupplier defaultSampleSize) {
    try {
      return run.sampleBytes(defaultSampleSize);
    } catch (ArithmeticException _) {
      throw outside("a run's samples add up to more bytes than a signed 64-bit count");
    }
  }

  private static FragmentedMp4Exception outside(String detail) {
    return new FragmentedMp4Exception(Reason.SAMPLE_DATA_OUTSIDE_MDAT, detail);
  }

  private long defaultSampleSizeOf(TrackFragmentHeader header) {
    return header.defaultSampleSize().orElseGet(() -> trackDefaultSampleSizeOf(header.trackId()));
  }

  private long trackDefaultSampleSizeOf(long trackId) {
    return Optional.ofNullable(defaultSampleSizes.get(trackId))
        .orElseThrow(
            () ->
                FragmentedMp4Exception.malformed(
                    "a traf of track "
                        + trackId
                        + " needs a default sample size, and no trex declares one"));
  }

  /**
   * Where a fragment's {@code mdat} body lies, measured from its {@code moof}'s first byte, and
   * where that {@code moof} starts in the stream.
   */
  record MediaData(long moofPosition, long bodyStart, long end) {

    void requireInside(long trackId, long start, long bytes) {
      if (bytes == 0 || holds(start, bytes)) {
        return;
      }

      throw outside(
          "track "
              + trackId
              + " places "
              + bytes
              + " bytes at "
              + start
              + ", outside the mdat body at "
              + bodyStart
              + ".."
              + end
              + " from its moof");
    }

    private boolean holds(long start, long bytes) {
      return start >= bodyStart && bytes <= end - start;
    }
  }
}
