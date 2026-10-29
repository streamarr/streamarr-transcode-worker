package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import lombok.NonNull;

/**
 * Copies the fragmented MP4 that FFmpeg writes of an HEVC source with {@code
 * delay_moov+default_base_moof+frag_keyframe}, moving each Dolby Vision RPU of a video sample to
 * the end of the sample of the picture it belongs to. An RPU ahead of its sample's first slice
 * belongs to the picture before, and moves to the end of that picture's sample; one that has no
 * previous picture, such as the first after a seek, is dropped. An RPU that follows a slice of its
 * sample but is not the sample's last NAL unit moves to the end of that sample.
 *
 * <p>FFmpeg's HEVC decoder reads an RPU only when it is the last NAL unit of the sample, so an RPU
 * ahead of the sample's first slice reshapes no picture. A sample whose RPU moves grows, shrinks or
 * changes; its fragment is rewritten with a {@code trun} that states every sample's size and a data
 * offset that places its samples in the rewritten {@code mdat}, and every other field stays as
 * read. A fragment whose samples keep their bytes passes unchanged. An RPU moved behind the
 * previous picture can cross fragments, so the mover holds the fragment of the last picture, and
 * every fragment after it, until the next picture arrives; when what it holds exceeds {@link
 * #MAXIMUM_HELD_BYTES}, as audio past the end of the video can, it passes them on, and an RPU that
 * then starts a sample has no previous picture. A sample the mover cannot delimit, or that holds no
 * slice, keeps its bytes; a sample without a slice decodes no picture, so its RPUs are copied
 * behind the previous picture instead of moved.
 *
 * <p>The muxer writes each video sample's duration as the gap to the next sample's decode time,
 * where the source's demuxer read a duration of its own; FFmpeg's decoder gives each picture its
 * packet's duration, and the encode's frame-rate conversion repeats and drops pictures by it. The
 * mover therefore gives each video sample the duration of its packet in the source, which the
 * copying FFmpeg writes beside the copy, and keeps every sample's decode time by giving each video
 * sample of a rewritten fragment its own {@code traf}, whose {@code tfdt} states that time. A
 * packet that is a keyframe where its sample is no sync sample, or the reverse, refuses the copy;
 * one whose duration is unknown leaves its sample's.
 */
final class FragmentedMp4RpuMover {

  /** The largest initialization segment or fragment the mover reads. */
  static final long MAXIMUM_FRAGMENT_BYTES = 32L * 1024 * 1024;

  /** What the mover holds back at most, beyond the fragment it reads, before passing it on. */
  static final long MAXIMUM_HELD_BYTES = 32L * 1024 * 1024;

  private static final Set<String> HEVC_SAMPLE_ENTRIES = Set.of("hvc1", "hev1", "dvh1", "dvhe");
  private static final Set<String> MOOF_CHILDREN = Set.of("mfhd", "traf");
  private static final Set<String> TRAF_CHILDREN = Set.of("tfhd", "tfdt", "trun");
  private static final int LENGTH_SIZE_FIELD =
      CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS - 1;
  private static final int BOX_HEADER_BYTES = 8;
  private static final long LARGEST_SAMPLE_DURATION = 0xFFFF_FFFFL;

  private final FragmentedMp4Reader reader;
  private final VideoPacketTimings timings;
  private final OutputStream output;
  private final Deque<HeldFragment> held = new ArrayDeque<>();
  private long heldBytes;
  private long readBytes;
  private int lengthSize;
  private Optional<VideoSample> previousPicture = Optional.empty();

  private FragmentedMp4RpuMover(
      InputStream input, VideoPacketTimings timings, OutputStream output) {
    this.reader = new FragmentedMp4Reader(input, MAXIMUM_FRAGMENT_BYTES, this::admit);
    this.timings = timings;
    this.output = output;
  }

  /**
   * Copies the stream to the end of the input, writing each unit once no later sample can change
   * it.
   *
   * @param timings the source's timing of each of the input's video packets
   * @throws FragmentedMp4Exception when the input is not the fragmented MP4 of an HEVC video track
   *     that FFmpeg writes with those flags, or the timings do not match it, for the named reason
   */
  static void copy(
      @NonNull InputStream input, @NonNull VideoPacketTimings timings, @NonNull OutputStream output)
      throws IOException {
    new FragmentedMp4RpuMover(input, timings, output).copyToEnd();
  }

  private void copyToEnd() throws IOException {
    for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
      readBytes = 0;
      switch (unit.orElseThrow()) {
        case InitializationSegment segment -> pass(segment);
        case Fragment fragment -> accept(fragment);
      }
    }

    releaseAll();
  }

  // The unit being read, its boxes and the heap of its samples, fits one fragment's cap.
  private boolean admit(long bytes) {
    readBytes = Math.addExact(readBytes, bytes);
    if (readBytes > MAXIMUM_FRAGMENT_BYTES) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "a unit of the copy of the source holds more than "
              + MAXIMUM_FRAGMENT_BYTES
              + " bytes with the heap of its samples");
    }

    return true;
  }

  private void pass(InitializationSegment segment) throws IOException {
    lengthSize = nalUnitLengthSizeOf(segment);
    output.write(segment.bytes());
    output.flush();
  }

  private static int nalUnitLengthSizeOf(InitializationSegment segment) {
    var entry =
        CodecConfigurationReader.firstVideoSampleEntryOf(segment)
            .filter(sampleEntry -> HEVC_SAMPLE_ENTRIES.contains(sampleEntry.type()))
            .orElseThrow(
                () ->
                    new FragmentedMp4Exception(
                        Reason.OUTPUT_MISMATCH, "the copy of the source declares no HEVC video"));
    var hvcC =
        entry
            .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
            .requiredChild("hvcC")
            .fields();
    var lengthSize = (hvcC.skip(LENGTH_SIZE_FIELD).u8() & 0x03) + 1;
    if (lengthSize == 3) {
      throw FragmentedMp4Exception.malformed("hvcC declares NAL unit lengths of 3 bytes");
    }

    return lengthSize;
  }

  private void accept(Fragment fragment) throws IOException {
    var moof = boxView(fragment.boxes().getFirst());
    requireFfmpegLayout(moof);
    requireSamplesWithinTheirMdat(fragment);
    var heldFragment = new HeldFragment(fragment, moof);
    for (var sample : heldFragment.videoSamples) {
      restoreSourceDuration(sample, fragment.tracks());
      repair(sample);
    }

    held.addLast(heldFragment);
    heldBytes += fragment.heldBytes();
    releaseBeforePreviousPicture();
    if (heldBytes > MAXIMUM_HELD_BYTES) {
      releaseAll();
    }
  }

  private static void requireFfmpegLayout(BoxView moof) {
    requireChildren(moof, MOOF_CHILDREN);
    for (var traf : moof.children("traf")) {
      requireChildren(traf, TRAF_CHILDREN);
    }

    for (var trackFragment : TrackFragmentBox.allOf(moof)) {
      var header = trackFragment.header();
      if (header.baseDataOffset().isPresent() || !header.defaultBaseIsMoof()) {
        throw new FragmentedMp4Exception(
            Reason.UNEXPECTED_BOX,
            "a tfhd of track "
                + header.trackId()
                + " places its data other than from the start of its moof");
      }
    }
  }

  // Each run lies inside the mdat, but runs may still overlap; a rewrite writes every sample
  // apart, so samples that hold more bytes than their mdat would grow the copy past its input.
  private static void requireSamplesWithinTheirMdat(Fragment fragment) {
    var sampleBytes =
        Stream.concat(fragment.videoSamples().stream(), fragment.audioSamples().stream())
            .mapToLong(Sample::size)
            .sum();
    if (sampleBytes > fragment.mediaDataBox().length - BOX_HEADER_BYTES) {
      throw FragmentedMp4Exception.malformed(
          "the runs of a fragment place "
              + sampleBytes
              + " sample bytes in an mdat of "
              + fragment.mediaDataBox().length
              + " bytes");
    }
  }

  private static void requireChildren(BoxView box, Set<String> allowed) {
    for (var child : box.children()) {
      if (!allowed.contains(child.type())) {
        throw new FragmentedMp4Exception(
            Reason.UNEXPECTED_BOX, box.type() + " holds " + child.type());
      }
    }
  }

  // FFmpeg passes both outputs the same packets in the same order, but repairs an invalid or
  // non-monotonic decode time in each output's own time base, so only a packet's keyframe flag
  // reliably names its sample.
  private void restoreSourceDuration(VideoSample sample, MovieTracks tracks) throws IOException {
    var packet = timings.next();
    var video = tracks.video().orElseThrow();
    var copied = sample.copied();
    if (packet.keyframe() != video.startOf(copied).syncSample()) {
      throw unusable(
          "the video sample at decode time "
              + copied.decodeTime()
              + (packet.keyframe() ? " is no sync sample" : " is a sync sample")
              + " where the source's packet "
              + (packet.keyframe() ? "is" : "is not")
              + " a keyframe");
    }

    if (packet.duration() > 0) {
      sample.restoreDuration(
          requireSampleDuration(ticksOf(packet, video.timescale(), packet.duration())));
    }
  }

  private static long ticksOf(VideoPacketTiming packet, long timescale, long value) {
    return packet
        .timeBase()
        .ticksIn(timescale, value)
        .orElseThrow(
            () ->
                unusable(
                    value
                        + " in "
                        + packet.timeBase()
                        + " does not convert exactly into the video track's timescale of "
                        + timescale));
  }

  private static long requireSampleDuration(long duration) {
    if (duration > LARGEST_SAMPLE_DURATION) {
      throw unusable("a source packet lasts " + duration + " ticks, more than a trun can state");
    }

    return duration;
  }

  private static FragmentedMp4Exception unusable(String detail) {
    return new FragmentedMp4Exception(Reason.UNUSABLE_VIDEO_TIMING, detail);
  }

  private void repair(VideoSample sample) {
    var delimited = HevcNalUnit.delimit(sample.original(), lengthSize);
    if (delimited.isEmpty()) {
      previousPicture = Optional.empty();
      return;
    }

    var units = delimited.orElseThrow();
    sample.keepDroppedTailLast(units);
    var firstSlice = firstSliceOf(units);
    if (firstSlice.isEmpty()) {
      copyRpusBehindPreviousPicture(units);
      return;
    }

    // An RPU that already ends what the decoder sees of the sample is the one FFmpeg's decoder
    // reads, so the RPUs that move behind the sample's slices go ahead of it.
    var lastIndex = lastReachingDecoder(units);
    var endsWithRpu = units.get(lastIndex).endsWithRpu() && lastIndex > firstSlice.getAsInt();
    var leading = new ArrayList<HevcNalUnit>();
    var kept = new ArrayList<HevcNalUnit>();
    var behind = new ArrayList<HevcNalUnit>();
    var insertBehindAt = OptionalInt.empty();
    for (var index = 0; index < units.size(); index++) {
      var unit = units.get(index);
      if (index == lastIndex && endsWithRpu) {
        insertBehindAt = OptionalInt.of(kept.size());
        kept.add(unit);
      } else if (!unit.holdsOnlyRpus()) {
        // A length whose RPU follows another NAL unit that the decoder reads, such as a slice or a
        // parameter set, keeps its place: moving it would move that NAL unit too.
        kept.add(unit);
      } else if (index < firstSlice.getAsInt()) {
        leading.add(unit);
      } else {
        behind.add(unit);
      }
    }

    appendBehindPreviousPicture(leading);
    if (!leading.isEmpty() || !behind.isEmpty()) {
      kept.addAll(insertBehindAt.orElse(kept.size()), behind);
      sample.replace(kept);
    }

    previousPicture = Optional.of(sample);
  }

  // A sample with a slice holds a NAL unit that reaches the decoder.
  private static int lastReachingDecoder(List<HevcNalUnit> units) {
    var index = units.size() - 1;
    while (!units.get(index).reachesDecoder()) {
      index--;
    }

    return index;
  }

  private static OptionalInt firstSliceOf(List<HevcNalUnit> units) {
    for (var index = 0; index < units.size(); index++) {
      if (units.get(index).slice()) {
        return OptionalInt.of(index);
      }
    }

    return OptionalInt.empty();
  }

  private void copyRpusBehindPreviousPicture(List<HevcNalUnit> units) {
    appendBehindPreviousPicture(units.stream().filter(HevcNalUnit::holdsOnlyRpus).toList());
  }

  // FFmpeg's HEVC decoder reads only a sample's last NAL unit as its RPU, so the previous picture
  // keeps only the last RPU moved behind it, however many samples carry RPUs for it.
  private void appendBehindPreviousPicture(List<HevcNalUnit> rpus) {
    if (rpus.isEmpty()) {
      return;
    }

    previousPicture.ifPresent(picture -> picture.endWithMovedRpu(rpus.getLast()));
  }

  // Every fragment ahead of the previous picture's is final: only that picture can still grow.
  private void releaseBeforePreviousPicture() throws IOException {
    var holding = previousPicture.map(VideoSample::fragment);
    while (!held.isEmpty() && holding.filter(fragment -> fragment == held.peekFirst()).isEmpty()) {
      write(held.removeFirst());
    }

    output.flush();
  }

  private void releaseAll() throws IOException {
    previousPicture = Optional.empty();
    releaseBeforePreviousPicture();
  }

  private void write(HeldFragment fragment) throws IOException {
    heldBytes -= fragment.fragment.heldBytes();
    if (!fragment.changed()) {
      for (var box : fragment.fragment.boxes()) {
        output.write(box);
      }

      return;
    }

    output.write(fragment.rewritten());
  }

  private static BoxView boxView(byte[] box) {
    var header = BoxHeader.read(new BoxFields("box header", ByteBuffer.wrap(box)));
    return new BoxView(
        header.type(), ByteBuffer.wrap(box, header.length(), box.length - header.length()));
  }

  private static byte[] boxBytes(BoxView box) {
    var bytes = ByteBuffer.allocate(BOX_HEADER_BYTES + box.body().remaining());
    bytes.putInt(bytes.capacity());
    putType(bytes, box.type());
    bytes.put(box.body().duplicate());
    return bytes.array();
  }

  private static void putType(ByteBuffer target, String type) {
    for (var index = 0; index < type.length(); index++) {
      target.put((byte) type.charAt(index));
    }
  }

  /** A fragment the mover holds, with the video samples whose bytes it may change. */
  private static final class HeldFragment {

    private final Fragment fragment;
    private final BoxView moof;
    private final List<VideoSample> videoSamples;

    private HeldFragment(Fragment fragment, BoxView moof) {
      this.fragment = fragment;
      this.moof = moof;
      var mediaData = fragment.mediaDataBox();
      this.videoSamples =
          fragment.videoSamples().stream()
              .map(
                  sample ->
                      new VideoSample(
                          this,
                          sample,
                          ByteBuffer.wrap(
                                  mediaData,
                                  sample.mediaDataPosition(),
                                  Math.toIntExact(sample.size()))
                              .slice()))
              .toList();
    }

    private boolean changed() {
      return videoSamples.stream().anyMatch(VideoSample::changed);
    }

    // The moof's size depends on no data offset, so it is measured from runs placed at zero.
    private byte[] rewritten() {
      var moofBytes = BOX_HEADER_BYTES + layOut(0).moofBodyBytes();
      var layout = layOut(moofBytes + BOX_HEADER_BYTES);
      var dataBytes = layout.samples().stream().mapToLong(ByteBuffer::remaining).sum();
      var bytes = ByteBuffer.allocate(Math.toIntExact(moofBytes + BOX_HEADER_BYTES + dataBytes));
      bytes.putInt(moofBytes);
      putType(bytes, "moof");
      layout.moofChildren().forEach(bytes::put);
      bytes.putInt(Math.toIntExact(BOX_HEADER_BYTES + dataBytes));
      putType(bytes, "mdat");
      layout.samples().forEach(sample -> bytes.put(sample.duplicate()));
      return bytes.array();
    }

    private Layout layOut(int dataStart) {
      var tracks = fragment.tracks();
      var videoTrackId = tracks.video().map(VideoTrack::trackId);
      var video = videoSamples.iterator();
      var mediaData = fragment.mediaDataBox();
      var audio =
          fragment.audioSamples().stream()
              .map(
                  sample ->
                      ByteBuffer.wrap(
                              mediaData, sample.mediaDataPosition(), Math.toIntExact(sample.size()))
                          .slice())
              .iterator();
      var layout = new Layout(new ArrayList<>(), new ArrayList<>(), dataStart);
      for (var child : moof.children()) {
        if (!child.type().equals("traf")) {
          layout.addBox(child);
          continue;
        }

        var trackId = TrackFragmentHeader.of(child.requiredChild("tfhd")).trackId();
        if (videoTrackId.equals(Optional.of(trackId))) {
          layout.addVideoTrafs(child, video);
          continue;
        }

        layout.addTraf(child, audio);
      }

      return layout;
    }
  }

  /** The children of a rewritten moof, and the samples of its mdat in the order its runs place. */
  private record Layout(List<byte[]> moofChildren, List<ByteBuffer> samples, int dataStart) {

    private int moofBodyBytes() {
      return moofChildren.stream().mapToInt(child -> child.length).sum();
    }

    private int nextDataOffset() {
      return dataStart + samples.stream().mapToInt(ByteBuffer::remaining).sum();
    }

    // A traf of each of the video traf's samples, which keeps the video traf's tfhd and states the
    // sample's decode time, so that no sample's duration moves the next one's decode time.
    private void addVideoTrafs(BoxView traf, Iterator<VideoSample> videoSamples) {
      var tfhd = boxBytes(traf.requiredChild("tfhd"));
      var sampleCount =
          traf.children("trun").stream().mapToLong(run -> TrackRun.of(run).sampleCount()).sum();
      for (var index = 0L; index < sampleCount; index++) {
        var sample = videoSamples.next();
        var content = sample.content();
        var run = TrackRun.ofOneSample(nextDataOffset(), sample.entry(content.remaining()));
        samples.add(content);
        var tfdt = ByteBuffer.allocate(BOX_HEADER_BYTES + 12);
        tfdt.putInt(tfdt.capacity());
        putType(tfdt, "tfdt");
        tfdt.putInt(1 << 24).putLong(sample.copied().decodeTime());
        var trafBytes = BOX_HEADER_BYTES + tfhd.length + tfdt.capacity() + run.length;
        var bytes = ByteBuffer.allocate(trafBytes);
        bytes.putInt(trafBytes);
        putType(bytes, "traf");
        bytes.put(tfhd).put(tfdt.array()).put(run);
        moofChildren.add(bytes.array());
      }
    }

    private void addBox(BoxView box) {
      moofChildren.add(boxBytes(box));
    }

    private void addTraf(BoxView traf, Iterator<ByteBuffer> trackSamples) {
      var children = new ArrayList<byte[]>();
      for (var child : traf.children()) {
        if (!child.type().equals("trun")) {
          children.add(boxBytes(child));
          continue;
        }

        var run = TrackRun.of(child);
        var sizes = new long[Math.toIntExact(run.sampleCount())];
        var dataOffset = nextDataOffset();
        for (var index = 0; index < sizes.length; index++) {
          var sample = trackSamples.next();
          sizes[index] = sample.remaining();
          samples.add(sample);
        }

        children.add(run.rewritten(dataOffset, sizes));
      }

      var trafBytes = BOX_HEADER_BYTES + children.stream().mapToInt(child -> child.length).sum();
      var bytes = ByteBuffer.allocate(trafBytes);
      bytes.putInt(trafBytes);
      putType(bytes, "traf");
      children.forEach(bytes::put);
      moofChildren.add(bytes.array());
    }
  }

  /**
   * One video sample's bytes: as read, or as the mover rearranged them, then the RPU moved behind
   * it; and its duration: as read, or its packet's in the source. A rearranged sample holds its own
   * copy of its bytes rather than a view of each NAL unit, so what a held sample retains is bounded
   * by its size.
   */
  private static final class VideoSample {

    private final HeldFragment fragment;
    private final Sample copied;
    private final ByteBuffer original;
    private long duration;
    private Optional<ByteBuffer> rearranged = Optional.empty();
    private Optional<ByteBuffer> movedRpu = Optional.empty();
    private int droppedTailBytes;

    private VideoSample(HeldFragment fragment, Sample copied, ByteBuffer original) {
      this.fragment = fragment;
      this.copied = copied;
      this.original = original;
      this.duration = copied.duration();
    }

    private HeldFragment fragment() {
      return fragment;
    }

    /** The sample as the copy holds it. */
    private Sample copied() {
      return copied;
    }

    private ByteBuffer original() {
      return original.duplicate();
    }

    private boolean changed() {
      return rearranged.isPresent() || movedRpu.isPresent() || duration != copied.duration();
    }

    private void restoreDuration(long sourceDuration) {
      duration = sourceDuration;
    }

    /** The sample's run entry with its current duration and the size of its content. */
    private TrackRun.Entry entry(long size) {
      return TrackRun.Entry.builder()
          .duration(duration)
          .size(size)
          .flags(copied.flags())
          .compositionOffset(copied.compositionOffset())
          .build();
    }

    /**
     * Keeps the bytes after these NAL units, which FFmpeg's splitter drops, at the end of the
     * sample, behind any RPU that moves into it: ahead of an RPU, they would be read as its length.
     */
    private void keepDroppedTailLast(List<HevcNalUnit> nalUnits) {
      droppedTailBytes =
          original.remaining() - nalUnits.stream().mapToInt(HevcNalUnit::length).sum();
    }

    private void replace(List<HevcNalUnit> nalUnits) {
      var bytes = ByteBuffer.allocate(original.remaining());
      nalUnits.forEach(unit -> bytes.put(unit.bytes().duplicate()));
      bytes.put(original.slice(original.limit() - droppedTailBytes, droppedTailBytes));
      rearranged = Optional.of(bytes.flip());
    }

    private void endWithMovedRpu(HevcNalUnit rpu) {
      movedRpu = Optional.of(copyOf(rpu.bytes()));
    }

    private ByteBuffer content() {
      var base = rearranged.orElse(original).duplicate();
      if (movedRpu.isEmpty()) {
        return base;
      }

      var rpu = movedRpu.orElseThrow().duplicate();
      var content = ByteBuffer.allocate(base.remaining() + rpu.remaining());
      var tailStart = base.limit() - droppedTailBytes;
      return content
          .put(base.duplicate().limit(tailStart))
          .put(rpu)
          .put(base.position(tailStart))
          .flip();
    }

    private static ByteBuffer copyOf(ByteBuffer bytes) {
      var copy = ByteBuffer.allocate(bytes.remaining());
      return copy.put(bytes.duplicate()).flip();
    }
  }
}
