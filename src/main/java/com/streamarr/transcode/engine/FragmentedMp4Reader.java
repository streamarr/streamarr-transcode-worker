package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.SampleRanges.MediaData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/** Reads the top-level ISOBMFF boxes of FFmpeg's fragmented MP4 output from a byte stream. */
final class FragmentedMp4Reader {

  private static final long MAXIMUM_ARRAY_BYTES = Integer.MAX_VALUE - 8L;
  private static final int SCRATCH_BYTES = 64 * 1024;

  private final InputStream stream;
  private final long maximumSegmentBytes;
  private final BoxAdmission admission;

  // Every read from the stream lands here first, box headers and body chunks alike, so a read that
  // blocks on the stream references no box.
  private final byte[] scratch = new byte[SCRATCH_BYTES];
  private final UnitBoxes unitBoxes = new UnitBoxes();
  private long position;
  private Optional<Movie> movie = Optional.empty();

  /**
   * @param maximumSegmentBytes the largest initialization segment or fragment the reader admits; it
   *     rejects any box that would exceed it before allocating memory for that box
   * @param admission admits each box's bytes after the reader has checked the box against the cap
   *     and before it allocates memory for the box; when it refuses a box, the reader ends reading
   *     with {@link ReadingCancelled}
   */
  FragmentedMp4Reader(
      @NonNull InputStream stream, long maximumSegmentBytes, @NonNull BoxAdmission admission) {
    if (maximumSegmentBytes <= 0 || maximumSegmentBytes > MAXIMUM_ARRAY_BYTES) {
      throw new IllegalArgumentException(
          "Segment cap must be between 1 and "
              + MAXIMUM_ARRAY_BYTES
              + ", got: "
              + maximumSegmentBytes);
    }

    this.stream = stream;
    this.maximumSegmentBytes = maximumSegmentBytes;
    this.admission = admission;
  }

  /**
   * Drops the boxes of the unit being read, from any thread, and ends reading with {@link
   * ReadingCancelled} instead of returning that unit. A read that blocks on the stream meanwhile
   * ends reading once it returns.
   */
  void cancel() {
    unitBoxes.cancel();
  }

  /**
   * Returns the initialization segment first, then each fragment, and empty at a clean end of file
   * on a box boundary.
   *
   * @throws FragmentedMp4Exception when the stream cannot be delivered, with the named reason
   * @throws ReadingCancelled when the admission refused a box or reading was cancelled
   */
  Optional<Mp4Unit> next() throws IOException {
    try {
      if (movie.isPresent()) {
        return readFragment(movie.orElseThrow());
      }

      return readInitializationSegment();
    } finally {
      unitBoxes.clear();
    }
  }

  private Optional<Mp4Unit> readInitializationSegment() throws IOException {
    var ftypHeader = readHeader();
    if (ftypHeader.isEmpty()) {
      return Optional.empty();
    }

    var missing = Reason.MISSING_INITIALIZATION_SEGMENT;
    var ftypBoxHeader = requireType(ftypHeader.orElseThrow(), "ftyp", missing);
    readBox(ftypBoxHeader, 0);
    var moovHeader =
        readHeader()
            .map(header -> requireType(header, "moov", missing))
            .orElseThrow(() -> unexpected(missing, "moov", "the end of the stream"));
    readBox(moovHeader, ftypBoxHeader.size());
    var boxes = unitBoxes.take();
    var ftyp = boxes.getFirst();
    var moov = boxes.getLast();
    var moovView = viewOf(moovHeader, moov);
    var trackExtends = TrackExtends.byTrackIdIn(moovView);
    movie =
        Optional.of(
            new Movie(VideoTrack.of(moovView, trackExtends), new SampleRanges(trackExtends)));
    var bytes = ByteBuffer.allocate(ftyp.length + moov.length).put(ftyp).put(moov).array();
    return Optional.of(new InitializationSegment(bytes));
  }

  private Optional<Mp4Unit> readFragment(Movie declared) throws IOException {
    var moofPosition = position;
    var nextHeader = readHeader();
    if (nextHeader.isEmpty()) {
      return Optional.empty();
    }

    var moofHeader = requireMoof(nextHeader.orElseThrow());
    readBox(moofHeader, 0);
    var mdatHeader =
        readHeader()
            .orElseThrow(
                () ->
                    new FragmentedMp4Exception(
                        Reason.END_OF_FILE_AFTER_MOVIE_FRAGMENT, "no mdat follows the moof"));
    readBox(requireType(mdatHeader, "mdat", Reason.UNEXPECTED_BOX), moofHeader.size());
    var boxes = unitBoxes.take();
    var moof = boxes.getFirst();
    var mdat = boxes.getLast();
    var trackFragments = TrackFragmentBox.allOf(viewOf(moofHeader, moof));
    var mediaData =
        new MediaData(
            moofPosition,
            (long) moof.length + mdatHeader.length(),
            (long) moof.length + mdat.length);
    declared.sampleRanges().requireInside(trackFragments, mediaData);
    var videoStart = declared.videoTrack().flatMap(track -> track.startOf(trackFragments));
    var shortestVideoSampleDuration =
        declared
            .videoTrack()
            .map(track -> track.shortestSampleDurationOf(trackFragments))
            .orElseGet(OptionalLong::empty);
    return Optional.of(new Fragment(List.of(moof, mdat), videoStart, shortestVideoSampleDuration));
  }

  private static BoxHeader requireMoof(BoxHeader header) {
    return switch (header.type()) {
      case "ftyp", "moov" ->
          throw new FragmentedMp4Exception(
              Reason.MISPLACED_INITIALIZATION_SEGMENT, header.type() + " follows a fragment");
      default -> requireType(header, "moof", Reason.UNEXPECTED_BOX);
    };
  }

  private static BoxHeader requireType(BoxHeader header, String type, Reason reason) {
    if (!header.type().equals(type)) {
      throw unexpected(reason, type, header.type());
    }

    return header;
  }

  private static FragmentedMp4Exception unexpected(Reason reason, String expected, String found) {
    return new FragmentedMp4Exception(reason, "expected " + expected + ", found " + found);
  }

  /** Reads the next header into {@link #scratch}, where {@link #readBox} copies it from. */
  private Optional<BoxHeader> readHeader() throws IOException {
    var read = stream.readNBytes(scratch, 0, BoxHeader.COMPACT_LENGTH);
    position += read;
    if (read == 0) {
      return Optional.empty();
    }

    requireHeaderBytes(read, BoxHeader.COMPACT_LENGTH);
    var length = BoxHeader.COMPACT_LENGTH;
    if (BoxHeader.declaresLargeSize(scratch)) {
      length = BoxHeader.LARGE_LENGTH;
      var largeSizeBytesRead = stream.readNBytes(scratch, read, length - read);
      position += largeSizeBytesRead;
      read += largeSizeBytesRead;
      requireHeaderBytes(read, length);
    }

    var header = BoxHeader.read(new BoxFields("box header", ByteBuffer.wrap(scratch, 0, length)));
    if (header.isUnsized()) {
      throw new FragmentedMp4Exception(
          Reason.UNSIZED_BOX, header.type() + " extends to the end of the stream");
    }

    return Optional.of(header);
  }

  private static void requireHeaderBytes(int read, int headerLength) {
    if (read < headerLength) {
      throw new FragmentedMp4Exception(
          Reason.END_OF_FILE_IN_BOX_HEADER,
          "read " + read + " of " + headerLength + " header bytes");
    }
  }

  // Adds the box whose header the scratch buffer holds to the unit being read.
  private void readBox(BoxHeader header, long admittedBytes) throws IOException {
    if (Long.compareUnsigned(header.size(), maximumSegmentBytes - admittedBytes) > 0) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          header.type()
              + " of "
              + Long.toUnsignedString(header.size())
              + " bytes after "
              + admittedBytes
              + " exceeds the cap of "
              + maximumSegmentBytes);
    }

    if (header.size() < header.length()) {
      throw FragmentedMp4Exception.malformed(
          header.type() + " declares " + header.size() + " bytes");
    }

    if (!admission.tryAdmit(header.size())) {
      throw new ReadingCancelled();
    }

    unitBoxes.begin(header, scratch);
    var remaining = header.size() - header.length();
    while (remaining > 0) {
      var read = stream.read(scratch, 0, (int) Math.min(remaining, scratch.length));
      if (read < 0) {
        throw new FragmentedMp4Exception(
            Reason.END_OF_FILE_IN_BOX_BODY,
            header.type()
                + " ended after "
                + (header.size() - remaining)
                + " of "
                + header.size()
                + " bytes");
      }

      position += read;
      remaining -= read;
      unitBoxes.append(scratch, read);
    }
  }

  private static BoxView viewOf(BoxHeader header, byte[] box) {
    var headerLength = header.length();
    return new BoxView(
        header.type(), ByteBuffer.wrap(box, headerLength, box.length - headerLength));
  }

  /** What the initialization segment's {@code moov} declares for reading every fragment. */
  private record Movie(Optional<VideoTrack> videoTrack, SampleRanges sampleRanges) {}

  /**
   * Ends reading when the admission refuses a box or reading is cancelled; the reader then holds
   * none of its unit.
   */
  static final class ReadingCancelled extends RuntimeException {
    ReadingCancelled() {
      super(null, null, false, false);
    }
  }

  // The boxes of the unit being read. The reader holds them only here until the unit is complete,
  // never in a local variable while it reads from the stream, so cancelling reading from another
  // thread drops them even while a read blocks.
  private static final class UnitBoxes {

    // Guarded by this monitor.
    private final List<byte[]> boxes = new ArrayList<>();
    private int filledBytes;
    private boolean cancelled;

    // Starts a box with the header bytes at the start of the scratch buffer.
    synchronized void begin(BoxHeader header, byte[] scratch) {
      requireNotCancelled();
      var box = new byte[Math.toIntExact(header.size())];
      System.arraycopy(scratch, 0, box, 0, header.length());
      boxes.add(box);
      filledBytes = header.length();
    }

    // Appends the first bytes of the scratch buffer to the box begun last.
    synchronized void append(byte[] scratch, int length) {
      requireNotCancelled();
      System.arraycopy(scratch, 0, boxes.getLast(), filledBytes, length);
      filledBytes += length;
    }

    // Hands over the unit's boxes in the order they were read.
    synchronized List<byte[]> take() {
      requireNotCancelled();
      var taken = List.copyOf(boxes);
      boxes.clear();
      return taken;
    }

    synchronized void clear() {
      boxes.clear();
    }

    synchronized void cancel() {
      cancelled = true;
      boxes.clear();
    }

    // Holds this monitor.
    private void requireNotCancelled() {
      if (cancelled) {
        throw new ReadingCancelled();
      }
    }
  }
}
