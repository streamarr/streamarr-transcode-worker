package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.SampleRanges.MediaData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** Reads the top-level ISOBMFF boxes of FFmpeg's fragmented MP4 output from a byte stream. */
final class FragmentedMp4Reader {

  private static final long MAXIMUM_ARRAY_BYTES = Integer.MAX_VALUE - 8L;

  private final InputStream stream;
  private final long maximumSegmentBytes;
  private final BoxAdmission admission;
  private final byte[] headerBytes = new byte[BoxHeader.LARGE_LENGTH];
  private long position;
  private Optional<Movie> movie = Optional.empty();

  /**
   * @param maximumSegmentBytes the largest initialization segment or fragment the reader admits; it
   *     rejects any box that would exceed it before allocating memory for that box
   */
  FragmentedMp4Reader(@NonNull InputStream stream, long maximumSegmentBytes) {
    this(stream, maximumSegmentBytes, BoxAdmission.UNBOUNDED);
  }

  /**
   * @param admission admits each box's bytes after the reader has checked the box against the cap
   *     and before it allocates memory for the box
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
   * Returns the initialization segment first, then each fragment, and empty at a clean end of file
   * on a box boundary.
   *
   * @throws FragmentedMp4Exception when the stream cannot be delivered, with the named reason
   */
  Optional<Mp4Unit> next() throws IOException {
    if (movie.isPresent()) {
      return readFragment(movie.orElseThrow());
    }

    return readInitializationSegment();
  }

  private Optional<Mp4Unit> readInitializationSegment() throws IOException {
    var ftypHeader = readHeader();
    if (ftypHeader.isEmpty()) {
      return Optional.empty();
    }

    var missing = Reason.MISSING_INITIALIZATION_SEGMENT;
    var ftyp = readBox(requireType(ftypHeader.orElseThrow(), "ftyp", missing), 0);
    var moovHeader =
        readHeader()
            .map(header -> requireType(header, "moov", missing))
            .orElseThrow(() -> unexpected(missing, "moov", "the end of the stream"));
    var moov = readBox(moovHeader, ftyp.length);
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
    var moof = readBox(moofHeader, 0);
    var mdatHeader =
        readHeader()
            .orElseThrow(
                () ->
                    new FragmentedMp4Exception(
                        Reason.END_OF_FILE_AFTER_MOVIE_FRAGMENT, "no mdat follows the moof"));
    var mdat = readBox(requireType(mdatHeader, "mdat", Reason.UNEXPECTED_BOX), moof.length);
    var trackFragments = TrackFragmentBox.allOf(viewOf(moofHeader, moof));
    var mediaData =
        new MediaData(
            moofPosition,
            (long) moof.length + mdatHeader.length(),
            (long) moof.length + mdat.length);
    declared.sampleRanges().requireInside(trackFragments, mediaData);
    var videoStart = declared.videoTrack().flatMap(track -> track.startOf(trackFragments));
    return Optional.of(new Fragment(List.of(moof, mdat), videoStart));
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

  /** Reads the next header into {@link #headerBytes}, where {@link #readBox} copies it from. */
  private Optional<BoxHeader> readHeader() throws IOException {
    var read = stream.readNBytes(headerBytes, 0, BoxHeader.COMPACT_LENGTH);
    position += read;
    if (read == 0) {
      return Optional.empty();
    }

    requireHeaderBytes(read, BoxHeader.COMPACT_LENGTH);
    var length = BoxHeader.COMPACT_LENGTH;
    if (BoxHeader.declaresLargeSize(headerBytes)) {
      length = BoxHeader.LARGE_LENGTH;
      var largeSizeBytesRead = stream.readNBytes(headerBytes, read, length - read);
      position += largeSizeBytesRead;
      read += largeSizeBytesRead;
      requireHeaderBytes(read, length);
    }

    var header =
        BoxHeader.read(new BoxFields("box header", ByteBuffer.wrap(headerBytes, 0, length)));
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

  private byte[] readBox(BoxHeader header, long admittedBytes) throws IOException {
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

    admission.admit(header.size());
    var box = new byte[(int) header.size()];
    var headerLength = header.length();
    System.arraycopy(headerBytes, 0, box, 0, headerLength);
    var read = stream.readNBytes(box, headerLength, box.length - headerLength);
    position += read;
    if (read < box.length - headerLength) {
      throw new FragmentedMp4Exception(
          Reason.END_OF_FILE_IN_BOX_BODY,
          header.type() + " ended after " + (headerLength + read) + " of " + box.length + " bytes");
    }

    return box;
  }

  private static BoxView viewOf(BoxHeader header, byte[] box) {
    var headerLength = header.length();
    return new BoxView(
        header.type(), ByteBuffer.wrap(box, headerLength, box.length - headerLength));
  }

  /** What the initialization segment's {@code moov} declares for reading every fragment. */
  private record Movie(Optional<VideoTrack> videoTrack, SampleRanges sampleRanges) {}
}
