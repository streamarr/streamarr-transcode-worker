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
 * the end of the sample of the picture it belongs to, as the {@link RpuMove} says.
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

  private final FragmentedMp4Reader reader;
  private final OutputStream output;
  private final RpuMove move;
  private final Deque<HeldFragment> held = new ArrayDeque<>();
  private long heldBytes;
  private int lengthSize;
  private Optional<VideoSample> previousPicture = Optional.empty();

  private FragmentedMp4RpuMover(InputStream input, OutputStream output, RpuMove move) {
    this.reader = new FragmentedMp4Reader(input, MAXIMUM_FRAGMENT_BYTES, _ -> true);
    this.output = output;
    this.move = move;
  }

  /**
   * Copies the stream to the end of the input, writing each unit once no later sample can change
   * it.
   *
   * @throws FragmentedMp4Exception when the input is not the fragmented MP4 of an HEVC video track
   *     that FFmpeg writes with those flags, for the named reason
   */
  static void copy(@NonNull InputStream input, @NonNull OutputStream output, @NonNull RpuMove move)
      throws IOException {
    new FragmentedMp4RpuMover(input, output, move).copyToEnd();
  }

  private void copyToEnd() throws IOException {
    for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
      switch (unit.orElseThrow()) {
        case InitializationSegment segment -> pass(segment);
        case Fragment fragment -> accept(fragment);
      }
    }

    releaseAll();
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
      repair(sample);
    }

    held.addLast(heldFragment);
    heldBytes += fragment.byteLength();
    if (heldBytes > MAXIMUM_HELD_BYTES) {
      previousPicture = Optional.empty();
    }

    releaseBeforePreviousPicture();
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

  private void repair(VideoSample sample) {
    var delimited = HevcNalUnit.delimit(sample.original(), lengthSize);
    if (delimited.isEmpty()) {
      previousPicture = Optional.empty();
      return;
    }

    var units = delimited.orElseThrow();
    var firstSlice = firstSliceOf(units);
    if (firstSlice.isEmpty()) {
      copyRpusBehindPreviousPicture(units);
      return;
    }

    var leading = new ArrayList<HevcNalUnit>();
    var kept = new ArrayList<HevcNalUnit>();
    var behind = new ArrayList<HevcNalUnit>();
    for (var index = 0; index < units.size(); index++) {
      var unit = units.get(index);
      if (!unit.rpu() || index == units.size() - 1 && index > firstSlice.getAsInt()) {
        kept.add(unit);
      } else if (index < firstSlice.getAsInt()) {
        leading.add(unit);
      } else {
        behind.add(unit);
      }
    }

    if (move == RpuMove.BEHIND_OWN_PICTURE) {
      behind.addAll(0, leading);
    } else {
      appendBehindPreviousPicture(leading);
    }

    if (!leading.isEmpty() || !behind.isEmpty()) {
      kept.addAll(behind);
      sample.replace(kept);
    }

    if (move == RpuMove.BEHIND_PREVIOUS_PICTURE) {
      previousPicture = Optional.of(sample);
    }
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
    if (move == RpuMove.BEHIND_PREVIOUS_PICTURE) {
      appendBehindPreviousPicture(units.stream().filter(HevcNalUnit::rpu).toList());
    }
  }

  private void appendBehindPreviousPicture(List<HevcNalUnit> rpus) {
    previousPicture.ifPresent(picture -> rpus.forEach(picture::append));
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
    heldBytes -= fragment.fragment.byteLength();
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

  private static void putBox(ByteBuffer target, BoxView box) {
    target.putInt(BOX_HEADER_BYTES + box.body().remaining());
    putType(target, box.type());
    target.put(box.body().duplicate());
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
      var video = videoSamples.stream().map(VideoSample::content).iterator();
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
        if (child.type().equals("traf")) {
          var trackId = TrackFragmentHeader.of(child.requiredChild("tfhd")).trackId();
          layout.addTraf(child, videoTrackId.equals(Optional.of(trackId)) ? video : audio);
        } else {
          layout.addBox(child);
        }
      }

      return layout;
    }
  }

  /** The children of a rewritten moof, and the samples of its mdat in the order its runs place. */
  private record Layout(List<byte[]> moofChildren, List<ByteBuffer> samples, int dataStart) {

    private int moofBodyBytes() {
      return moofChildren.stream().mapToInt(child -> child.length).sum();
    }

    private void addBox(BoxView box) {
      var bytes = ByteBuffer.allocate(BOX_HEADER_BYTES + box.body().remaining());
      putBox(bytes, box);
      moofChildren.add(bytes.array());
    }

    private void addTraf(BoxView traf, Iterator<ByteBuffer> trackSamples) {
      var children = new ArrayList<byte[]>();
      for (var child : traf.children()) {
        if (!child.type().equals("trun")) {
          var bytes = ByteBuffer.allocate(BOX_HEADER_BYTES + child.body().remaining());
          putBox(bytes, child);
          children.add(bytes.array());
          continue;
        }

        var run = TrackRun.of(child);
        var sizes = new long[Math.toIntExact(run.sampleCount())];
        var dataOffset = dataStart + samples.stream().mapToInt(ByteBuffer::remaining).sum();
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

  /** One video sample's bytes: as read, or the NAL units the mover gave it. */
  private static final class VideoSample {

    private final HeldFragment fragment;
    private final ByteBuffer original;
    private Optional<List<ByteBuffer>> units = Optional.empty();

    private VideoSample(HeldFragment fragment, ByteBuffer original) {
      this.fragment = fragment;
      this.original = original;
    }

    private HeldFragment fragment() {
      return fragment;
    }

    private ByteBuffer original() {
      return original.duplicate();
    }

    private boolean changed() {
      return units.isPresent();
    }

    private void replace(List<HevcNalUnit> nalUnits) {
      units = Optional.of(new ArrayList<>(nalUnits.stream().map(HevcNalUnit::bytes).toList()));
    }

    private void append(HevcNalUnit rpu) {
      if (units.isEmpty()) {
        units = Optional.of(new ArrayList<>(List.of(original())));
      }

      units.orElseThrow().add(rpu.bytes());
    }

    private ByteBuffer content() {
      if (units.isEmpty()) {
        return original();
      }

      var parts = units.orElseThrow();
      var content = ByteBuffer.allocate(parts.stream().mapToInt(ByteBuffer::remaining).sum());
      parts.forEach(part -> content.put(part.duplicate()));
      return content.flip();
    }
  }
}
