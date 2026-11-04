package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

  // The av1C fields before its configOBUs (AV1 ISOBMFF binding 2.3.3).
  private static final int AV1C_FIELDS = 4;

  private final SampleRewrite sampleRewrite;

  private VideoSampleRewrite(SampleRewrite sampleRewrite) {
    this.sampleRewrite = sampleRewrite;
  }

  /**
   * The rewrite of the copy's samples; empty when the decision lists no bitstream rewrite.
   *
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the copy lists a
   *     rewrite for video that is neither HEVC nor AV1, or when the av1C record holds a metadata
   *     OBU that the copy strips; with {@link Reason#MALFORMED_BOX} when the HEVC record declares
   *     no usable NAL unit length, or the av1C record's OBUs cannot be read
   */
  static Optional<VideoSampleRewrite> of(VideoDecision decision, InitializationSegment segment) {
    if (!(decision instanceof VideoDecision.Copy copy) || copy.bitstreamRewrites().isEmpty()) {
      return Optional.empty();
    }

    // The output check has already found the video sample entry.
    var sampleEntry = CodecConfigurationReader.firstVideoSampleEntryOf(segment).orElseThrow();
    SampleRewrite rewrite =
        switch (sampleEntry.type()) {
          case "hvc1", "hev1" ->
              new HevcSampleRewrite(
                  CodecConfigurationReader.nalUnitLengthSizeOf(sampleEntry),
                  copy.bitstreamRewrites());
          case "av01" -> av1Rewrite(sampleEntry, copy.bitstreamRewrites());
          default ->
              throw mismatch(
                  "the copy lists "
                      + copy.bitstreamRewrites()
                      + " for a "
                      + sampleEntry.type()
                      + " sample entry, which carries no such content");
        };
    return Optional.of(new VideoSampleRewrite(rewrite));
  }

  // FFmpeg copies the source's metadata OBUs into av1C's configOBUs. The worker rewrites only
  // samples, so a record that holds a stripped OBU fails the attempt instead of reaching a client.
  private static SampleRewrite av1Rewrite(BoxView sampleEntry, Set<BitstreamRewrite> rewrites) {
    var rewrite = new Av1SampleRewrite(rewrites);
    var av1C =
        sampleEntry.after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES).requiredChild("av1C");
    var record = av1C.body();
    var configurationObus = record.slice(AV1C_FIELDS, record.remaining() - AV1C_FIELDS);
    if (rewrite.rewrite(configurationObus) != configurationObus) {
      throw mismatch("the av1C record holds a metadata OBU that the copy strips from its samples");
    }

    return rewrite;
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

  private static ByteBuffer bytesOf(Fragment fragment, Sample sample) {
    return bytesOf(fragment.mediaDataBox(), sample);
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
