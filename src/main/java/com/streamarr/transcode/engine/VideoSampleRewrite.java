package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Applies the sample rewrites that a copy lists to the video samples of each fragment. Each rewrite
 * reads every NAL unit or OBU of every video sample and refuses a sample that it cannot read, so no
 * stripped content passes inside one. A fragment whose video samples keep their bytes passes
 * unchanged. Any other fragment gets a new {@code mdat} that holds every sample of its tracks back
 * to back, in their order in the old one, and samples that point into it; its other boxes stay as
 * read, because a media segment writes its own {@code moof}.
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
   *     read
   */
  Fragment rewrite(Fragment fragment) {
    var original =
        fragment.videoSamples().stream().map(sample -> bytesOf(fragment, sample)).toList();
    var rewritten = original.stream().map(sampleRewrite::rewrite).toList();
    var changed =
        IntStream.range(0, rewritten.size())
            .anyMatch(index -> rewritten.get(index) != original.get(index));
    if (!changed) {
      return fragment;
    }

    return rebuilt(fragment, rewritten);
  }

  private static Fragment rebuilt(Fragment fragment, List<ByteBuffer> rewrittenVideo) {
    var placed = new ArrayList<Placed>();
    for (var index = 0; index < fragment.videoSamples().size(); index++) {
      placed.add(new Placed(true, index, fragment.videoSamples().get(index)));
    }

    for (var index = 0; index < fragment.audioSamples().size(); index++) {
      placed.add(new Placed(false, index, fragment.audioSamples().get(index)));
    }

    placed.sort(Comparator.comparingInt(each -> each.sample().mediaDataPosition()));
    var mdat = fragment.mediaDataBox();
    var headerLength = BoxHeader.read(new BoxFields("mdat", ByteBuffer.wrap(mdat))).length();
    var body = new ByteArrayOutputStream(mdat.length);
    var video = new ArrayList<>(fragment.videoSamples());
    var audio = new ArrayList<>(fragment.audioSamples());
    for (var each : placed) {
      var bytes =
          each.video()
              ? rewrittenVideo.get(each.index())
              : bytesOf(mdat, each.sample()).duplicate();
      var moved = withPlacement(each.sample(), headerLength + body.size(), bytes.remaining());
      body.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
      (each.video() ? video : audio).set(each.index(), moved);
    }

    var boxes = new ArrayList<>(fragment.boxes());
    boxes.set(boxes.size() - 1, mdatOf(mdat, headerLength, body.toByteArray()));
    return Fragment.builder()
        .boxes(boxes)
        .tracks(fragment.tracks())
        .videoSamples(video)
        .audioSamples(audio)
        .build();
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

  // The old mdat's header, with its 32-bit or 64-bit size changed to fit the new body.
  private static byte[] mdatOf(byte[] old, int headerLength, byte[] body) {
    var box = ByteBuffer.allocate(headerLength + body.length);
    box.put(old, 0, headerLength).put(body);
    if (headerLength == BoxHeader.LARGE_LENGTH) {
      return box.putLong(BoxHeader.COMPACT_LENGTH, box.capacity()).array();
    }

    return box.putInt(0, box.capacity()).array();
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
