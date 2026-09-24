package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** Reads the top-level ISOBMFF boxes of FFmpeg's fragmented MP4 output from a byte stream. */
final class FragmentedMp4Reader {

  private static final int COMPACT_HEADER_BYTES = 8;
  private static final int LARGE_HEADER_BYTES = 16;
  private static final int LARGE_SIZE = 1;
  private static final int UNSIZED = 0;
  private static final long MAXIMUM_ARRAY_BYTES = Integer.MAX_VALUE - 8L;

  private final InputStream stream;
  private final long maximumSegmentBytes;
  private boolean initialized;
  private Optional<VideoTrack> videoTrack = Optional.empty();

  /**
   * @param maximumSegmentBytes the largest initialization segment or fragment the reader admits; it
   *     rejects any box that would exceed it before allocating memory for that box
   */
  FragmentedMp4Reader(@NonNull InputStream stream, long maximumSegmentBytes) {
    if (maximumSegmentBytes <= 0 || maximumSegmentBytes > MAXIMUM_ARRAY_BYTES) {
      throw new IllegalArgumentException(
          "Segment cap must be between 1 and "
              + MAXIMUM_ARRAY_BYTES
              + ", got: "
              + maximumSegmentBytes);
    }

    this.stream = stream;
    this.maximumSegmentBytes = maximumSegmentBytes;
  }

  /**
   * Returns the initialization segment first, then each fragment, and empty at a clean end of file
   * on a box boundary.
   *
   * @throws FragmentedMp4Exception when the stream cannot be delivered, with the named reason
   */
  Optional<Mp4Unit> next() throws IOException {
    if (initialized) {
      return readFragment();
    }

    return readInitializationSegment();
  }

  private Optional<Mp4Unit> readInitializationSegment() throws IOException {
    var ftypHeader = readHeader();
    if (ftypHeader.isEmpty()) {
      return Optional.empty();
    }

    var ftyp = readBox(requireInitializationBox(ftypHeader.orElseThrow(), "ftyp"), 0);
    var moovHeader =
        requireInitializationBox(
            readHeader().orElseThrow(() -> missingInitialization("moov", "the end of the stream")),
            "moov");
    var moov = readBox(moovHeader, ftyp.length);
    videoTrack = VideoTrack.of(nested(moovHeader, moov));
    initialized = true;
    var bytes = ByteBuffer.allocate(ftyp.length + moov.length).put(ftyp).put(moov).array();
    return Optional.of(new InitializationSegment(bytes));
  }

  private static BoxHeader requireInitializationBox(BoxHeader header, String type) {
    if (!header.type().equals(type)) {
      throw missingInitialization(type, header.type());
    }

    return header;
  }

  private static FragmentedMp4Exception missingInitialization(String expected, String found) {
    return new FragmentedMp4Exception(
        Reason.MISSING_INITIALIZATION_SEGMENT, "expected " + expected + ", found " + found);
  }

  private Optional<Mp4Unit> readFragment() throws IOException {
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
    var mdat = readBox(requireType(mdatHeader, "mdat"), moof.length);
    var videoStart = videoTrack.flatMap(track -> track.startOf(nested(moofHeader, moof)));
    return Optional.of(new Fragment(List.of(moof, mdat), videoStart));
  }

  private static BoxHeader requireMoof(BoxHeader header) {
    return switch (header.type()) {
      case "ftyp", "moov" ->
          throw new FragmentedMp4Exception(
              Reason.MISPLACED_INITIALIZATION_SEGMENT, header.type() + " follows a fragment");
      default -> requireType(header, "moof");
    };
  }

  private static BoxHeader requireType(BoxHeader header, String type) {
    if (!header.type().equals(type)) {
      throw new FragmentedMp4Exception(
          Reason.UNEXPECTED_BOX, "expected " + type + ", found " + header.type());
    }

    return header;
  }

  private Optional<BoxHeader> readHeader() throws IOException {
    var compact = stream.readNBytes(COMPACT_HEADER_BYTES);
    if (compact.length == 0) {
      return Optional.empty();
    }

    requireHeaderBytes(compact.length, COMPACT_HEADER_BYTES);
    var fields = new BoxFields("box header", ByteBuffer.wrap(compact));
    var size = fields.u32();
    var type = fields.fourcc();
    if (size == UNSIZED) {
      throw new FragmentedMp4Exception(
          Reason.UNSIZED_BOX, type + " extends to the end of the stream");
    }

    if (size != LARGE_SIZE) {
      return Optional.of(new BoxHeader(compact, type, size));
    }

    var large = new byte[LARGE_HEADER_BYTES];
    System.arraycopy(compact, 0, large, 0, COMPACT_HEADER_BYTES);
    var read = stream.readNBytes(large, COMPACT_HEADER_BYTES, COMPACT_HEADER_BYTES);
    requireHeaderBytes(COMPACT_HEADER_BYTES + read, LARGE_HEADER_BYTES);
    return Optional.of(new BoxHeader(large, type, ByteBuffer.wrap(large).getLong(8)));
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

    if (header.size() < header.bytes().length) {
      throw FragmentedMp4Exception.malformed(
          header.type() + " declares " + header.size() + " bytes");
    }

    var box = new byte[(int) header.size()];
    var headerLength = header.bytes().length;
    System.arraycopy(header.bytes(), 0, box, 0, headerLength);
    var read = stream.readNBytes(box, headerLength, box.length - headerLength);
    if (read < box.length - headerLength) {
      throw new FragmentedMp4Exception(
          Reason.END_OF_FILE_IN_BOX_BODY,
          header.type() + " ended after " + (headerLength + read) + " of " + box.length + " bytes");
    }

    return box;
  }

  private static NestedBox nested(BoxHeader header, byte[] box) {
    var headerLength = header.bytes().length;
    return new NestedBox(
        header.type(), ByteBuffer.wrap(box, headerLength, box.length - headerLength));
  }

  private record BoxHeader(byte[] bytes, String type, long size) {}
}
