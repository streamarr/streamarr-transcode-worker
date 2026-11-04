package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the movie box (moov) of an MP4 or MOV file and the boxes it holds. A box whose size runs
 * past its parent, a movie box larger than 256 MiB and a file without a movie box throw {@link
 * ContainerIndexException}; a box shorter than its fields throws {@link
 * java.nio.BufferUnderflowException} or {@link ContainerIndexException}.
 */
final class Mp4MovieBox {

  private static final long MAXIMUM_MOVIE_BOX_BYTES = 256L << 20;
  private static final int BOX_HEADER_BYTES = 8;
  private static final int LARGE_BOX_HEADER_BYTES = 16;
  private static final String MOVIE_BOX = "moov";

  private Mp4MovieBox() {}

  /** The payload of the file's movie box. */
  static ByteBuffer read(FileChannel channel) throws IOException {
    var movie = scanThroughMovieBox(channel).movie().orElseThrow(() -> malformed(MOVIE_BOX));
    return payload(channel, movie.position(), movie.header());
  }

  // Walks the file's top-level boxes up to the first movie box and keeps only the boxes that the
  // readers need, so a file of many small boxes takes no more memory than a file of few.
  private static TopLevelScan scanThroughMovieBox(FileChannel channel) throws IOException {
    var size = channel.size();
    long position = 0;
    while (position + BOX_HEADER_BYTES <= size) {
      var header =
          ContainerFileBytes.read(
              channel, position, (int) Math.min(LARGE_BOX_HEADER_BYTES, size - position));
      var box = new TopLevelBox(position, BoxHeader.read(header, size - position));
      if (MOVIE_BOX.equals(box.header().type())) {
        return new TopLevelScan(Optional.of(box));
      }

      position += box.header().size();
    }

    return new TopLevelScan(Optional.empty());
  }

  /** The payload of the first child box of the given type. */
  static Optional<ByteBuffer> child(ByteBuffer parent, String type) {
    return boxes(parent).stream()
        .filter(box -> type.equals(box.type()))
        .map(Box::payload)
        .findFirst();
  }

  /** The payloads of every child box of the given type, in file order. */
  static List<ByteBuffer> children(ByteBuffer parent, String type) {
    return boxes(parent).stream().filter(box -> type.equals(box.type())).map(Box::payload).toList();
  }

  /** The track_ID of the track header (tkhd) of the given track box's payload. */
  static long trackId(ByteBuffer track) {
    var header = child(track, "tkhd").orElseThrow(() -> malformed("tkhd"));
    var version = header.get() & 0xFF;
    skipFlags(header);
    skip(header, version == 1 ? 16 : 8);
    return Integer.toUnsignedLong(header.getInt());
  }

  static String fourCharacterCode(ByteBuffer buffer) {
    var bytes = new byte[4];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  static void skipFlags(ByteBuffer box) {
    skip(box, 3);
  }

  static void skip(ByteBuffer buffer, int bytes) {
    if (bytes > buffer.remaining()) {
      throw malformed("box shorter than its fields");
    }

    buffer.position(buffer.position() + bytes);
  }

  private static ByteBuffer payload(FileChannel channel, long position, BoxHeader box)
      throws IOException {
    if (box.payloadSize() > MAXIMUM_MOVIE_BOX_BYTES) {
      throw malformed("oversized moov");
    }

    return ContainerFileBytes.read(channel, position + box.headerSize(), (int) box.payloadSize());
  }

  /** Every child box of the parent, in file order. */
  static List<Box> boxes(ByteBuffer parent) {
    var boxes = new ArrayList<Box>();
    var position = parent.position();
    while (parent.limit() - position >= BOX_HEADER_BYTES) {
      var view = parent.duplicate().position(position);
      var header = BoxHeader.read(view, view.remaining());
      var payload =
          parent
              .duplicate()
              .position(position + header.headerSize())
              .limit(Math.toIntExact(position + header.size()))
              .slice();
      boxes.add(new Box(header.type(), payload));
      position = Math.toIntExact(position + header.size());
    }

    return boxes;
  }

  private static ContainerIndexException malformed(String detail) {
    return new ContainerIndexException("Unusable MP4 movie box: " + detail);
  }

  record Box(String type, ByteBuffer payload) {}

  private record TopLevelBox(long position, BoxHeader header) {}

  private record TopLevelScan(Optional<TopLevelBox> movie) {}

  private record BoxHeader(String type, long size, int headerSize) {

    static BoxHeader read(ByteBuffer buffer, long available) {
      var size = Integer.toUnsignedLong(buffer.getInt());
      var type = fourCharacterCode(buffer);
      var headerSize = BOX_HEADER_BYTES;
      if (size == 1) {
        size = buffer.getLong();
        headerSize = LARGE_BOX_HEADER_BYTES;
      }

      if (size == 0) {
        size = available;
      }

      if (size < headerSize || size > available) {
        throw malformed("box size");
      }

      return new BoxHeader(type, size, headerSize);
    }

    long payloadSize() {
      return size - headerSize;
    }
  }
}
