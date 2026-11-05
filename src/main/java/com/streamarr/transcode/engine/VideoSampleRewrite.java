package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies the sample rewrites that a copy lists to the video samples of each fragment. Each rewrite
 * reads every NAL unit or OBU of every video sample and refuses a sample that it cannot read, so no
 * stripped content passes inside one. A fragment whose video samples keep their bytes passes
 * unchanged. In any other fragment, the samples from the first rewritten one on move down inside
 * the fragment's own {@code mdat} array, back to back in their old order, and get new positions and
 * sizes. The array keeps its length and its header, which nothing reads again: a media segment
 * writes its own {@code moof} and {@code mdat} from the samples. A rewrite therefore holds no more
 * memory than one rewritten sample beside the fragment that the producer already admitted.
 */
final class VideoSampleRewrite {

  private final SampleRewrite sampleRewrite;

  private VideoSampleRewrite(SampleRewrite sampleRewrite) {
    this.sampleRewrite = sampleRewrite;
  }

  /**
   * The rewrite of the copy's samples; empty when the decision lists no bitstream rewrite of
   * samples.
   *
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the copy lists a
   *     rewrite for video that is neither HEVC nor AV1, other than an HDR10+ strip of H.264; with
   *     {@link Reason#MALFORMED_BOX} when the HEVC or AVC record declares no usable NAL unit length
   */
  static Optional<VideoSampleRewrite> of(VideoDecision decision, InitializationSegment segment) {
    if (!(decision instanceof VideoDecision.Copy copy)) {
      return Optional.empty();
    }

    var sampleRewrites =
        copy.bitstreamRewrites().stream()
            .filter(BitstreamRewrite::rewritesSamples)
            .collect(Collectors.toUnmodifiableSet());
    if (sampleRewrites.isEmpty()) {
      return Optional.empty();
    }

    // The output check has already found the video sample entry.
    var sampleEntry = CodecConfigurationReader.firstVideoSampleEntryOf(segment).orElseThrow();
    SampleRewrite rewrite =
        switch (sampleEntry.type()) {
          case "hvc1", "hev1", "dvh1" ->
              new NalUnitSampleRewrite(
                  NalSyntax.HEVC,
                  CodecConfigurationReader.nalUnitLengthSizeOf(sampleEntry),
                  sampleRewrites);
          case "av01" -> new Av1SampleRewrite(sampleRewrites);
          case "avc1", "avc3" -> avcRewrite(sampleEntry, sampleRewrites);
          default -> throw noSuchContent(sampleEntry, sampleRewrites);
        };
    return Optional.of(new VideoSampleRewrite(rewrite));
  }

  // H.264 carries HDR10+ in SEI messages; its Dolby Vision is profile 9, which no copy strips.
  private static SampleRewrite avcRewrite(
      BoxView sampleEntry, Set<BitstreamRewrite> sampleRewrites) {
    if (!sampleRewrites.equals(Set.of(BitstreamRewrite.STRIP_HDR10_PLUS))) {
      throw noSuchContent(sampleEntry, sampleRewrites);
    }

    return new NalUnitSampleRewrite(
        NalSyntax.AVC,
        CodecConfigurationReader.avcNalUnitLengthSizeOf(sampleEntry),
        sampleRewrites);
  }

  private static FragmentedMp4Exception noSuchContent(
      BoxView sampleEntry, Set<BitstreamRewrite> sampleRewrites) {
    return mismatch(
        "the copy lists "
            + sampleRewrites
            + " for a "
            + sampleEntry.type()
            + " sample entry, which carries no such content");
  }

  /**
   * The fragment with its video samples rewritten.
   *
   * @throws FragmentedMp4Exception with {@link Reason#MALFORMED_BOX} when a video sample cannot be
   *     read, or two samples share bytes of the {@code mdat}
   */
  Fragment rewrite(Fragment fragment) {
    var mdat = fragment.mediaDataBox();
    var video = new ArrayList<>(fragment.videoSamples());
    var audio = new ArrayList<>(fragment.audioSamples());
    var write = -1;
    for (var each : inMediaDataOrder(fragment)) {
      var original = bytesOf(mdat, each.sample());
      var bytes = each.video() ? sampleRewrite.rewrite(original) : original;
      if (write < 0 && bytes == original) {
        continue;
      }

      if (write < 0) {
        write = each.sample().mediaDataPosition();
      }

      requireRoomAt(write, each.sample(), bytes);
      copy(bytes, mdat, write);
      var moved = withPlacement(each.sample(), write, bytes.remaining());
      (each.video() ? video : audio).set(each.index(), moved);
      write += bytes.remaining();
    }

    if (write < 0) {
      return fragment;
    }

    return Fragment.builder()
        .boxes(fragment.boxes())
        .tracks(fragment.tracks())
        .videoSamples(video)
        .audioSamples(audio)
        .build();
  }

  private static List<Placed> inMediaDataOrder(Fragment fragment) {
    var placed = new ArrayList<Placed>();
    for (var index = 0; index < fragment.videoSamples().size(); index++) {
      placed.add(new Placed(true, index, fragment.videoSamples().get(index)));
    }

    for (var index = 0; index < fragment.audioSamples().size(); index++) {
      placed.add(new Placed(false, index, fragment.audioSamples().get(index)));
    }

    placed.sort(Comparator.comparingInt(each -> each.sample().mediaDataPosition()));
    return placed;
  }

  // Each sample moves down to where the samples before it end, which is never past where it starts:
  // every rewrite only removes bytes, and FFmpeg writes no two samples over the same bytes.
  private static void requireRoomAt(int write, Sample sample, ByteBuffer bytes) {
    if (write > sample.mediaDataPosition() || bytes.remaining() > sample.size()) {
      throw FragmentedMp4Exception.malformed(
          "a rewritten sample at mdat position "
              + sample.mediaDataPosition()
              + " cannot move to position "
              + write);
    }
  }

  // System.arraycopy copies as if through a temporary array, so a sample may move onto bytes that
  // it overlaps.
  private static void copy(ByteBuffer bytes, byte[] mdat, int position) {
    System.arraycopy(
        bytes.array(), bytes.arrayOffset() + bytes.position(), mdat, position, bytes.remaining());
  }

  private static Sample withPlacement(Sample sample, int mediaDataPosition, int size) {
    return Sample.builder()
        .decodeTime(sample.decodeTime())
        .compositionOffset(sample.compositionOffset())
        .duration(sample.duration())
        .size(size)
        .flags(sample.flags())
        .mediaDataPosition(mediaDataPosition)
        .build();
  }

  private static ByteBuffer bytesOf(byte[] mdat, Sample sample) {
    return ByteBuffer.wrap(mdat, sample.mediaDataPosition(), Math.toIntExact(sample.size()))
        .slice();
  }

  private static FragmentedMp4Exception mismatch(String detail) {
    return new FragmentedMp4Exception(Reason.OUTPUT_MISMATCH, detail);
  }

  private record Placed(boolean video, int index, Sample sample) {}
}
