package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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
 * memory than one rewritten sample beside the fragment that the producer already admitted, except
 * when a rewritten sample is longer than the source's: the samples then move into a longer copy of
 * the array, which the producer admits as the fragment's growth.
 */
final class VideoSampleRewrite {

  // A copy of the fragment's array is at least this share longer than the array it replaces, so
  // the copies over one fragment stay a bounded multiple of its final length however many samples
  // grow.
  private static final int GROWTH_DIVISOR = 4;

  private final SampleRewrite sampleRewrite;
  private final int maximumMediaDataBytes;

  private VideoSampleRewrite(SampleRewrite sampleRewrite, long maximumSegmentBytes) {
    this.sampleRewrite = sampleRewrite;
    this.maximumMediaDataBytes = (int) Math.min(maximumSegmentBytes, Integer.MAX_VALUE);
  }

  /**
   * The rewrite of the copy's samples; empty when the decision lists no bitstream rewrite of
   * samples.
   *
   * @param maximumSegmentBytes the segment cap, which the media data of a rewritten fragment may
   *     not exceed either
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the copy lists a
   *     rewrite for video that is neither HEVC nor AV1, other than an HDR10+ strip or an assumed
   *     source matrix rewrite of H.264; with {@link Reason#MALFORMED_BOX} when the HEVC or AVC
   *     record declares no usable NAL unit length
   */
  static Optional<VideoSampleRewrite> of(
      VideoDecision decision, InitializationSegment segment, long maximumSegmentBytes) {
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
                  NalSyntax.HEVC, CodecConfigurationReader.nalUnitLengthSizeOf(sampleEntry), copy);
          case "av01" -> new Av1SampleRewrite(sampleRewrites);
          case "avc1", "avc3" -> avcRewrite(sampleEntry, copy);
          default -> throw noSuchContent(sampleEntry, sampleRewrites);
        };
    return Optional.of(new VideoSampleRewrite(rewrite, maximumSegmentBytes));
  }

  // H.264 carries HDR10+ in SEI messages; its Dolby Vision is profile 9, which no copy strips.
  private static SampleRewrite avcRewrite(BoxView sampleEntry, VideoDecision.Copy copy) {
    if (!Set.of(BitstreamRewrite.STRIP_HDR10_PLUS, BitstreamRewrite.NAME_ASSUMED_SOURCE_MATRIX)
        .containsAll(copy.bitstreamRewrites())) {
      throw noSuchContent(sampleEntry, copy.bitstreamRewrites());
    }

    return new NalUnitSampleRewrite(
        NalSyntax.AVC, CodecConfigurationReader.avcNalUnitLengthSizeOf(sampleEntry), copy);
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
   *     read, or two samples share bytes of the {@code mdat}; with {@link
   *     Reason#DOLBY_VISION_CONVERSION_FAILED} when a copy that converts profile 7 meets a sample
   *     without exactly one RPU that converts; with {@link Reason#EXCEEDS_SEGMENT_CAP} when the
   *     rewritten samples would take more media data than the segment cap
   */
  Fragment rewrite(Fragment fragment) {
    var mdat = fragment.mediaDataBox();
    var placed = inMediaDataOrder(fragment);
    var video = new ArrayList<>(fragment.videoSamples());
    var audio = new ArrayList<>(fragment.audioSamples());
    var target = mdat;
    var write = -1;
    for (var index = 0; index < placed.size(); index++) {
      var each = placed.get(index);
      var original = bytesOf(mdat, each.sample());
      var bytes = each.video() ? sampleRewrite.rewrite(original) : original;
      if (write < 0 && bytes == original) {
        continue;
      }

      if (write < 0) {
        write = each.sample().mediaDataPosition();
      }

      target = withRoomAt(target, mdat, write, bytes.remaining(), each.sample());
      copy(bytes, target, write);
      var moved = withPlacement(each.sample(), write, bytes.remaining());
      (each.video() ? video : audio).set(each.index(), moved);
      write += bytes.remaining();
    }

    if (write < 0) {
      return fragment;
    }

    return Fragment.builder()
        .boxes(withMediaDataBox(fragment.boxes(), trimmed(target, mdat, write)))
        .tracks(fragment.tracks())
        .videoSamples(video)
        .audioSamples(audio)
        .build();
  }

  // Every sample in the order of its bytes in the mdat; FFmpeg writes no two over the same bytes.
  private static List<Placed> inMediaDataOrder(Fragment fragment) {
    var placed = new ArrayList<Placed>();
    for (var index = 0; index < fragment.videoSamples().size(); index++) {
      placed.add(new Placed(true, index, fragment.videoSamples().get(index)));
    }

    for (var index = 0; index < fragment.audioSamples().size(); index++) {
      placed.add(new Placed(false, index, fragment.audioSamples().get(index)));
    }

    placed.sort(Comparator.comparingInt(each -> each.sample().mediaDataPosition()));
    for (var index = 1; index < placed.size(); index++) {
      requireAfter(placed.get(index - 1).sample(), placed.get(index).sample());
    }

    return placed;
  }

  private static void requireAfter(Sample before, Sample sample) {
    if (sample.mediaDataPosition() < before.mediaDataPosition() + before.size()) {
      throw FragmentedMp4Exception.malformed(
          "a sample at mdat position "
              + sample.mediaDataPosition()
              + " shares bytes with the sample before it");
    }
  }

  // A sample that shrinks moves down inside the fragment's own array, onto bytes at or before its
  // own, and ends before the next sample's bytes, which are still to move. A sample that grows past
  // its own end would overwrite them, so from the first such sample on, the samples move into a
  // copy of the array: a quarter longer than the array it replaces, or as long as the sample needs,
  // and never longer than the segment cap; the copy is trimmed to its samples at the end. A copy
  // sized by one sample's growth times the remaining samples, audio included, ran to hundreds of
  // megabytes for a fragment of three.
  private byte[] withRoomAt(byte[] target, byte[] mdat, int write, int length, Sample sample) {
    var end = (long) write + length;
    if (target == mdat && end <= sample.mediaDataPosition() + sample.size()) {
      return target;
    }

    if (target != mdat && end <= target.length) {
      return target;
    }

    if (end > maximumMediaDataBytes) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "the rewritten samples take "
              + end
              + " bytes of media data, over the segment cap of "
              + maximumMediaDataBytes);
    }

    var room = Math.max(end, target.length + (long) target.length / GROWTH_DIVISOR);
    return Arrays.copyOf(target, (int) Math.min(room, maximumMediaDataBytes));
  }

  // A copy's room beyond its last sample would count as media data against the segment cap, so the
  // copy ends where its samples do; the fragment's own array keeps the length the reader admitted.
  private static byte[] trimmed(byte[] target, byte[] mdat, int end) {
    if (target == mdat || target.length == end) {
      return target;
    }

    return Arrays.copyOf(target, end);
  }

  private static List<byte[]> withMediaDataBox(List<byte[]> boxes, byte[] mediaDataBox) {
    if (boxes.getLast() == mediaDataBox) {
      return boxes;
    }

    var replaced = new ArrayList<>(boxes);
    replaced.set(replaced.size() - 1, mediaDataBox);
    return replaced;
  }

  // System.arraycopy copies as if through a temporary array, so a sample may move onto bytes that
  // it overlaps.
  private static void copy(ByteBuffer bytes, byte[] target, int position) {
    System.arraycopy(
        bytes.array(), bytes.arrayOffset() + bytes.position(), target, position, bytes.remaining());
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
