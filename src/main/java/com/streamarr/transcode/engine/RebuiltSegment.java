package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * The samples of one media segment, which the producer writes as one {@code moof} and one {@code
 * mdat}: video first, of which a keyframe that opens the segment guarantees one, then audio, each
 * in decode order. The rebuilt segment references the bytes of the fragments that carried its
 * samples instead of copying them.
 */
final class RebuiltSegment implements ClosedSegment {

  private final int sequenceNumber;
  private final List<CarriedSample> videoSamples;
  private final List<CarriedSample> audioSamples;
  private final List<MovieFragmentWriter.Track> tracks;
  private final long heldBytes;
  private final long sharedBytes;

  /**
   * @param stillHeld the fragments the assembler still holds for later segments once this one
   *     closes, by identity; those that carried samples of this segment count for its delivery too
   */
  @Builder
  private RebuiltSegment(
      int sequenceNumber,
      @NonNull List<CarriedSample> videoSamples,
      @NonNull List<CarriedSample> audioSamples,
      @NonNull Set<Fragment> stillHeld) {
    this.sequenceNumber = sequenceNumber;
    this.videoSamples = List.copyOf(videoSamples);
    this.audioSamples = List.copyOf(audioSamples);
    this.tracks = tracksOf(this.videoSamples, this.audioSamples);
    var fragments = fragmentsOf(Stream.concat(videoSamples.stream(), audioSamples.stream()));
    this.sharedBytes = heldBytesOf(fragments.stream().filter(stillHeld::contains).toList());
    this.heldBytes = heldBytesOf(fragments) - sharedBytes;
  }

  /** The segment's bytes once written. */
  long byteLength() {
    return MovieFragmentWriter.headerBytes(tracks)
        + tracks.stream().mapToLong(MovieFragmentWriter.Track::sampleBytes).sum();
  }

  @Override
  public long heldBytes() {
    return heldBytes;
  }

  // The new moof and mdat header, and the fragments a later segment shares, which the delivery
  // and the assembler each count until the delivery returns.
  @Override
  public long additionalBytes() {
    return MovieFragmentWriter.headerBytes(tracks) + sharedBytes;
  }

  @Override
  public ProducedSegment write() {
    var parts = new ArrayList<ByteBuffer>();
    parts.add(ByteBuffer.wrap(MovieFragmentWriter.header(sequenceNumber + 1L, tracks)));
    addSampleBytes(parts, videoSamples);
    addSampleBytes(parts, audioSamples);
    return ProducedSegment.Media.of(sequenceNumber, parts);
  }

  private static List<MovieFragmentWriter.Track> tracksOf(
      List<CarriedSample> videoSamples, List<CarriedSample> audioSamples) {
    var tracks = new ArrayList<MovieFragmentWriter.Track>();
    tracks.add(trackOf(videoSamples, videoSamples.getFirst().videoTrackId()));
    if (!audioSamples.isEmpty()) {
      tracks.add(trackOf(audioSamples, audioSamples.getFirst().audioTrackId()));
    }

    return List.copyOf(tracks);
  }

  private static MovieFragmentWriter.Track trackOf(List<CarriedSample> samples, long trackId) {
    return new MovieFragmentWriter.Track(
        trackId, samples.stream().map(CarriedSample::sample).toList());
  }

  // One view per run of samples that lie back to back in one fragment's mdat.
  private static void addSampleBytes(List<ByteBuffer> parts, List<CarriedSample> samples) {
    var index = 0;
    while (index < samples.size()) {
      var first = samples.get(index);
      var end = first.sample().mediaDataPosition() + Math.toIntExact(first.sample().size());
      index++;
      while (index < samples.size() && samples.get(index).follows(first.fragment(), end)) {
        end += Math.toIntExact(samples.get(index).sample().size());
        index++;
      }

      var start = first.sample().mediaDataPosition();
      parts.add(ByteBuffer.wrap(first.fragment().mediaDataBox(), start, end - start).slice());
    }
  }

  private static Collection<Fragment> fragmentsOf(Stream<CarriedSample> samples) {
    var fragments = new IdentityHashMap<Fragment, Fragment>();
    samples.forEach(sample -> fragments.put(sample.fragment(), sample.fragment()));
    return fragments.values();
  }

  private static long heldBytesOf(Collection<Fragment> fragments) {
    return fragments.stream().mapToLong(Fragment::heldBytes).sum();
  }
}
