package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The EBML elements of a Matroska file. A reader that finds an element malformed throws {@link
 * ContainerIndexException}.
 */
final class MatroskaFile {

  private static final long EBML = 0x1A45DFA3L;
  private static final long SEGMENT = 0x18538067L;
  private static final long VOID = 0xECL;
  private static final long SEEK_HEAD = 0x114D9B74L;
  private static final long SEEK = 0x4DBBL;
  private static final long SEEK_ID = 0x53ABL;
  private static final long SEEK_POSITION = 0x53ACL;
  private static final long INFO = 0x1549A966L;
  private static final long TRACKS = 0x1654AE6BL;
  private static final long TRACK_ENTRY = 0xAEL;
  private static final long TRACK_TYPE = 0x83L;
  private static final long CUES = 0x1C53BB6BL;
  private static final long CLUSTER = 0x1F43B675L;
  private static final long VIDEO_TRACK_TYPE = 1;
  private static final long UNKNOWN_SIZE = -1;
  private static final int MAXIMUM_HEADER_BYTES = 12;
  private static final long MAXIMUM_ELEMENT_BYTES = 64L << 20;

  private MatroskaFile() {}

  /**
   * The TrackEntry of the video track at {@code videoTrackOrdinal}: zero for the first TrackEntry
   * whose type is video, one for the second, and so on.
   */
  static ByteBuffer videoTrack(ByteBuffer tracks, int videoTrackOrdinal) {
    var videoTracksBefore = new AtomicInteger(videoTrackOrdinal);
    var found = new AtomicReference<ByteBuffer>();
    eachChild(
        tracks,
        TRACK_ENTRY,
        entry -> {
          if (found.get() == null
              && unsigned(entry, TRACK_TYPE).orElse(0L) == VIDEO_TRACK_TYPE
              && videoTracksBefore.getAndDecrement() == 0) {
            found.set(entry);
          }
        });
    return Optional.ofNullable(found.get()).orElseThrow(() -> malformed("video TrackEntry"));
  }

  static Optional<Long> unsigned(ByteBuffer parent, long id) {
    return child(parent, id).map(MatroskaFile::unsigned);
  }

  static long unsigned(ByteBuffer value) {
    if (value.remaining() > Long.BYTES) {
      throw malformed("unsigned integer");
    }

    long result = 0;
    while (value.hasRemaining()) {
      result = (result << 8) | (value.get() & 0xFF);
    }

    return result;
  }

  static Optional<Double> floatValue(ByteBuffer parent, long id) {
    return child(parent, id)
        .map(
            value ->
                switch (value.remaining()) {
                  case Float.BYTES -> (double) value.getFloat();
                  case Double.BYTES -> value.getDouble();
                  default -> throw malformed("float");
                });
  }

  static Optional<String> string(ByteBuffer parent, long id) {
    return child(parent, id)
        .map(
            value -> {
              var bytes = new byte[value.remaining()];
              value.get(bytes);
              return new String(bytes, StandardCharsets.UTF_8);
            });
  }

  /** The first child with this ID; holds no other, as a hostile element can hold millions. */
  static Optional<ByteBuffer> child(ByteBuffer parent, long id) {
    var first = new AtomicReference<ByteBuffer>();
    eachChild(parent, id, match -> first.compareAndSet(null, match));
    return Optional.ofNullable(first.get());
  }

  /**
   * The first child with ID {@code id} of any child with ID {@code childId}, in order; holds no
   * other. FFmpeg's Matroska demuxer reads each repeat of a master element that may occur once,
   * such as a track's Video, into the same element.
   */
  static Optional<ByteBuffer> firstChildOfAny(ByteBuffer parent, long childId, long id) {
    var first = new AtomicReference<ByteBuffer>();
    eachChild(
        parent,
        childId,
        match -> {
          if (first.get() == null) {
            child(match, id).ifPresent(first::set);
          }
        });
    return Optional.ofNullable(first.get());
  }

  static List<ByteBuffer> children(ByteBuffer parent, long id) {
    var matches = new ArrayList<ByteBuffer>();
    eachChild(parent, id, matches::add);
    return matches;
  }

  /**
   * Gives each child of {@code parent} with this ID to {@code matches}, in order; a malformed child
   * anywhere in {@code parent} fails the walk.
   */
  private static void eachChild(ByteBuffer parent, long id, Consumer<ByteBuffer> matches) {
    var buffer = parent.duplicate();
    while (buffer.hasRemaining()) {
      var header = Header.read(buffer);
      if (header.size() == UNKNOWN_SIZE || header.size() > buffer.remaining()) {
        throw malformed("element size");
      }

      var end = buffer.position() + (int) header.size();
      if (header.id() == id) {
        matches.accept(buffer.duplicate().limit(end).slice());
      }

      buffer.position(end);
    }
  }

  static ContainerIndexException malformed(String detail) {
    return new ContainerIndexException("Unusable Matroska index: " + detail);
  }

  private record Header(long id, long size) {

    static Header read(ByteBuffer buffer) {
      var id = vint(buffer, false);
      var size = vint(buffer, true);
      return new Header(id, size);
    }

    /** Reads an EBML variable-size integer; an ID keeps its length marker, a size drops it. */
    private static long vint(ByteBuffer buffer, boolean isSize) {
      var first = buffer.get() & 0xFF;
      var length = Integer.numberOfLeadingZeros(first) - 23;
      if (first == 0 || length > (isSize ? 8 : 4)) {
        throw malformed("variable-size integer");
      }

      long value = isSize ? first & (0xFF >> length) : first;
      var allOnes = value == (0xFF >> length);
      for (var i = 1; i < length; i++) {
        var next = buffer.get() & 0xFF;
        allOnes &= next == 0xFF;
        value = (value << 8) | next;
      }

      return isSize && allOnes ? UNKNOWN_SIZE : value;
    }
  }

  /** The Info, Tracks and Cues elements of the first Segment, as FFmpeg's demuxer finds them. */
  record TopLevel(
      Optional<ByteBuffer> info, Optional<ByteBuffer> tracks, Optional<ByteBuffer> cues) {

    static TopLevel read(FileChannel channel) throws IOException {
      var found = elements(channel, Set.of(INFO, TRACKS, CUES));
      return new TopLevel(
          Optional.ofNullable(found.get(INFO)),
          Optional.ofNullable(found.get(TRACKS)),
          Optional.ofNullable(found.get(CUES)));
    }

    /** The Tracks element alone, so that a Cues element the file cannot hold loses no track. */
    static Optional<ByteBuffer> tracks(FileChannel channel) throws IOException {
      return Optional.ofNullable(elements(channel, Set.of(TRACKS)).get(TRACKS));
    }

    private static Map<Long, ByteBuffer> elements(FileChannel channel, Set<Long> ids)
        throws IOException {
      var segment = firstSegment(channel);
      var beforeFirstCluster = elementsBeforeFirstCluster(channel, segment, ids);
      var found = beforeFirstCluster.found();
      var seekPositions = beforeFirstCluster.seekPositions();
      for (var id : ids) {
        if (!found.containsKey(id) && seekPositions.containsKey(id)) {
          seek(channel, segment.dataStart() + seekPositions.get(id), id)
              .ifPresent(payload -> found.put(id, payload));
        }
      }

      return found;
    }

    private static FileElement firstSegment(FileChannel channel) throws IOException {
      var ebml = element(channel, 0);
      if (ebml.id() != EBML || ebml.size() == UNKNOWN_SIZE) {
        throw malformed("EBML header");
      }

      var segment = element(channel, ebml.end());
      // A Void element may sit at the root, ahead of the Segment.
      while (segment.id() == VOID && segment.size() != UNKNOWN_SIZE) {
        segment = element(channel, segment.end());
      }

      if (segment.id() != SEGMENT) {
        throw malformed("Segment");
      }

      return segment;
    }

    private record BeforeFirstCluster(Map<Long, ByteBuffer> found, Map<Long, Long> seekPositions) {}

    private static BeforeFirstCluster elementsBeforeFirstCluster(
        FileChannel channel, FileElement segment, Set<Long> ids) throws IOException {
      var fileSize = channel.size();
      var segmentEnd =
          segment.size() == UNKNOWN_SIZE ? fileSize : Math.min(segment.end(), fileSize);
      var found = new HashMap<Long, ByteBuffer>();
      var seekPositions = new HashMap<Long, Long>();
      var position = segment.dataStart();
      // The walk stops once it holds every element sought, so that a malformed element after them
      // loses none.
      while (position < segmentEnd && !found.keySet().containsAll(ids)) {
        var element = element(channel, position);
        if (element.id() == CLUSTER || element.size() == UNKNOWN_SIZE) {
          break;
        }

        if (element.id() == SEEK_HEAD) {
          seekPositions(payload(channel, element), seekPositions);
        }

        if (ids.contains(element.id())) {
          found.putIfAbsent(element.id(), payload(channel, element));
        }

        position = element.end();
      }

      return new BeforeFirstCluster(found, seekPositions);
    }

    private static Optional<ByteBuffer> seek(FileChannel channel, long position, long id)
        throws IOException {
      if (position >= channel.size()) {
        return Optional.empty();
      }

      var element = element(channel, position);
      if (element.id() != id || element.size() == UNKNOWN_SIZE) {
        return Optional.empty();
      }

      return Optional.of(payload(channel, element));
    }

    private static void seekPositions(ByteBuffer seekHead, Map<Long, Long> positions) {
      for (var seek : children(seekHead, SEEK)) {
        var id = child(seek, SEEK_ID).map(MatroskaFile::unsigned);
        var position = unsigned(seek, SEEK_POSITION);
        if (id.isPresent() && position.isPresent()) {
          positions.putIfAbsent(id.get(), position.get());
        }
      }
    }

    private static FileElement element(FileChannel channel, long position) throws IOException {
      var length = (int) Math.min(MAXIMUM_HEADER_BYTES, channel.size() - position);
      if (length <= 0) {
        throw malformed("element past the end of the file");
      }

      var buffer = ContainerFileBytes.read(channel, position, length);
      var header = Header.read(buffer);
      return new FileElement(header.id(), position + buffer.position(), header.size());
    }

    private static ByteBuffer payload(FileChannel channel, FileElement element) throws IOException {
      if (element.size() > MAXIMUM_ELEMENT_BYTES) {
        throw malformed("oversized element");
      }

      return ContainerFileBytes.read(channel, element.dataStart(), (int) element.size());
    }
  }

  private record FileElement(long id, long dataStart, long size) {

    long end() {
      return dataStart + size;
    }
  }
}
