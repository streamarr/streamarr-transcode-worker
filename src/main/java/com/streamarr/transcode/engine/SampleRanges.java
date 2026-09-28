package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The rule that every sample a {@code moof} describes lies inside the body of the {@code mdat} that
 * follows it, with the default sample sizes the initialization segment declares per track.
 *
 * <p>Samples are placed as ISO/IEC 14496-12 (8.8.7, 8.8.8) defines: a {@code traf}'s base is its
 * {@code tfhd} base-data-offset, else the {@code moof} under default-base-is-moof, else the {@code
 * moof} for the first {@code traf} and the end of the previous {@code traf}'s data after it; a run
 * starts at that base plus its data offset, else where the previous run of the {@code traf} ended,
 * else at the base.
 */
final class SampleRanges {

  private final Map<Long, TrackExtends> trackExtends;

  SampleRanges(Map<Long, TrackExtends> trackExtends) {
    this.trackExtends = Map.copyOf(trackExtends);
  }

  /**
   * @throws FragmentedMp4Exception when a run that holds bytes lies outside the {@code mdat}'s
   *     body, or when a run's samples need a default size that no box declares
   */
  void requireInside(List<TrackFragmentBox> trackFragments, MediaData mediaData) {
    forEachRun(
        trackFragments,
        mediaData,
        placed -> mediaData.requireInside(placed.trackId(), placed.start(), placed.bytes()));
  }

  // Places every run in order, handing each to the visitor before placing the next.
  private void forEachRun(
      List<TrackFragmentBox> trackFragments, MediaData mediaData, Consumer<PlacedRun> visitor) {
    var placement = new Placement(mediaData, visitor);
    trackFragments.forEach(placement::place);
  }

  /** Where a run's samples start, measured from the {@code moof}'s first byte, and their bytes. */
  private record PlacedRun(long trackId, long start, long bytes) {}

  /** Places each {@code traf}'s runs after the data of the {@code traf} placed before it. */
  private final class Placement {

    private final MediaData mediaData;
    private final Consumer<PlacedRun> visitor;
    private long previousTrafEnd;

    private Placement(MediaData mediaData, Consumer<PlacedRun> visitor) {
      this.mediaData = mediaData;
      this.visitor = visitor;
    }

    private void place(TrackFragmentBox trackFragment) {
      var header = trackFragment.header();
      var base = baseOf(header, previousTrafEnd, mediaData);
      var runEnd = base;
      for (var run : trackFragment.runs()) {
        var start = runEnd;
        var dataOffset = run.dataOffset();
        if (dataOffset.isPresent()) {
          start = offsetBy(base, dataOffset.orElseThrow());
        }

        var bytes = sampleBytesOf(run, () -> defaultSampleSizeOf(header));
        visitor.accept(new PlacedRun(header.trackId(), start, bytes));
        runEnd = start + bytes;
      }

      previousTrafEnd = runEnd;
    }
  }

  private static long baseOf(TrackFragmentHeader header, long previousTrafEnd, MediaData data) {
    if (header.baseDataOffset().isPresent()) {
      return data.fromMoof(header.baseDataOffset().getAsLong());
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
    return Optional.ofNullable(trackExtends.get(trackId))
        .map(TrackExtends::defaultSampleSize)
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

    /**
     * Measures an unsigned 64-bit stream position from the {@code moof}.
     *
     * @throws FragmentedMp4Exception when the position lies beyond any stream position
     */
    long fromMoof(long unsignedStreamPosition) {
      if (Long.compareUnsigned(unsignedStreamPosition, Long.MAX_VALUE) > 0) {
        throw outside(
            "a tfhd base of "
                + Long.toUnsignedString(unsignedStreamPosition)
                + " lies beyond any stream position");
      }

      return unsignedStreamPosition - moofPosition;
    }

    private boolean holds(long start, long bytes) {
      return start >= bodyStart && bytes <= end - start;
    }
  }
}
