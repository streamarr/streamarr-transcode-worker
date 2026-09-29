package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.Builder;

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
  private final long maximumSampleCount;

  /**
   * @param maximumSegmentBytes the segment cap, which bounds how many samples a media segment can
   *     describe
   */
  SampleRanges(Map<Long, TrackExtends> trackExtends, long maximumSegmentBytes) {
    this.trackExtends = Map.copyOf(trackExtends);
    this.maximumSampleCount = maximumSegmentBytes / MovieFragmentWriter.SAMPLE_ENTRY_BYTES;
  }

  /**
   * @throws FragmentedMp4Exception when a run that holds bytes lies outside the {@code mdat}'s
   *     body, or when a run's samples need a default size that no box declares
   */
  void requireInside(List<TrackFragmentBox> trackFragments, MediaData mediaData) {
    forEachRun(
        trackFragments,
        mediaData,
        placed ->
            mediaData.requireInside(
                placed.trackFragment().header().trackId(), placed.start(), placed.bytes()));
  }

  /**
   * Every sample of each {@code traf} that holds samples, in {@code traf} order: timed from its
   * {@code tfdt} and placed in the {@code mdat}. It trusts every run to lie inside the {@code
   * mdat}, which {@link #requireInside} checks first.
   *
   * @throws FragmentedMp4Exception when the fragment holds more samples than a media segment under
   *     the segment cap can describe, when a {@code traf} with samples holds no {@code tfdt}, or
   *     when a sample needs a default that no box declares
   */
  List<TrackSamples> samplesOf(List<TrackFragmentBox> trackFragments, MediaData mediaData) {
    describableSampleCountOf(trackFragments);
    var timing = new SampleTiming(mediaData);
    forEachRun(trackFragments, mediaData, timing::add);
    return timing.trackSamples();
  }

  /**
   * The samples of every run of the fragment.
   *
   * @throws FragmentedMp4Exception when the fragment holds more samples than a media segment under
   *     the segment cap can describe
   */
  long describableSampleCountOf(List<TrackFragmentBox> trackFragments) {
    var sampleCount =
        trackFragments.stream()
            .flatMap(trackFragment -> trackFragment.runs().stream())
            .mapToLong(TrackRun::sampleCount)
            .sum();
    if (sampleCount > maximumSampleCount) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "a fragment of "
              + sampleCount
              + " samples needs more than the segment cap to describe them");
    }

    return sampleCount;
  }

  // Places every run in order, handing each to the visitor before placing the next.
  private void forEachRun(
      List<TrackFragmentBox> trackFragments, MediaData mediaData, Consumer<PlacedRun> visitor) {
    var placement = new Placement(mediaData, visitor);
    trackFragments.forEach(placement::place);
  }

  /** Where a run's samples start, measured from the {@code moof}'s first byte, and their bytes. */
  @Builder
  private record PlacedRun(TrackFragmentBox trackFragment, TrackRun run, long start, long bytes) {}

  /** Times each sample from its {@code traf}'s decode time and places it in the {@code mdat}. */
  private final class SampleTiming {

    private final MediaData mediaData;
    private final List<TrackSamples> trackSamples = new ArrayList<>();
    // The traf whose samples the last run added, by identity: two trafs can hold the same bytes.
    private Optional<TrackFragmentBox> trackFragment = Optional.empty();
    private OptionalLong decodeTime = OptionalLong.empty();

    private SampleTiming(MediaData mediaData) {
      this.mediaData = mediaData;
    }

    private void add(PlacedRun placed) {
      if (trackFragment.filter(added -> added == placed.trackFragment()).isEmpty()) {
        trackFragment = Optional.of(placed.trackFragment());
        decodeTime = OptionalLong.empty();
        trackSamples.add(
            new TrackSamples(placed.trackFragment().header().trackId(), new ArrayList<>()));
      }

      var entries = placed.run().entries(defaultsOf(placed.trackFragment().header()));
      if (entries.isEmpty()) {
        return;
      }

      var time = decodeTime.orElseGet(() -> placed.trackFragment().baseMediaDecodeTime());
      var position = positionOf(placed);
      var samples = trackSamples.getLast().samples();
      for (var entry : entries) {
        samples.add(
            Sample.builder()
                .decodeTime(time)
                .compositionOffset(entry.compositionOffset())
                .duration(entry.duration())
                .size(entry.size())
                .flags(entry.flags())
                .mediaDataPosition(position)
                .build());
        time = decodeTimeAfter(time, entry.duration());
        position += Math.toIntExact(entry.size());
      }

      decodeTime = OptionalLong.of(time);
    }

    // A run that holds no bytes may point anywhere, and none of its samples is ever read.
    private int positionOf(PlacedRun placed) {
      if (placed.bytes() == 0) {
        return 0;
      }

      return mediaData.positionInBox(placed.start());
    }

    private List<TrackSamples> trackSamples() {
      return trackSamples.stream().filter(track -> !track.samples().isEmpty()).toList();
    }
  }

  private TrackRun.Defaults defaultsOf(TrackFragmentHeader header) {
    return new TrackRun.Defaults(
        () ->
            header
                .defaultSampleDuration()
                .orElseGet(() -> trackDefaultsOf(header).defaultSampleDuration()),
        () -> defaultSampleSizeOf(header),
        () ->
            header
                .defaultSampleFlags()
                .orElseGet(() -> trackDefaultsOf(header).defaultSampleFlags()));
  }

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
        visitor.accept(
            PlacedRun.builder()
                .trackFragment(trackFragment)
                .run(run)
                .start(start)
                .bytes(bytes)
                .build());
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

  private static long decodeTimeAfter(long decodeTime, long duration) {
    try {
      return Math.addExact(decodeTime, duration);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed("a decode time overflows a signed 64-bit value");
    }
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

  private TrackExtends trackDefaultsOf(TrackFragmentHeader header) {
    return Optional.ofNullable(trackExtends.get(header.trackId()))
        .orElseThrow(
            () ->
                FragmentedMp4Exception.malformed(
                    "a traf of track "
                        + header.trackId()
                        + " needs a default sample field, and no trex declares one"));
  }

  /**
   * Where a fragment's {@code mdat} body lies, measured from its {@code moof}'s first byte, and
   * where that {@code moof} starts in the stream.
   */
  @Builder
  record MediaData(long moofPosition, long boxStart, long bodyStart, long end) {

    /** Where a position measured from the {@code moof} lies in the {@code mdat} box's bytes. */
    int positionInBox(long fromMoof) {
      return Math.toIntExact(fromMoof - boxStart);
    }

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
