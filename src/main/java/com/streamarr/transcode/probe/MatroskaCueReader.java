package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reads the keyframes of one Matroska video track from the Cues element. A keyframe's decode time
 * equals its cue time, the time FFmpeg's Matroska demuxer seeks by.
 *
 * <p>The reader trusts Cues only from muxing applications that write a cue for every video keyframe
 * (libavformat and mkvmerge), and only Cues the demuxer can find: through the SeekHead or before
 * the first Cluster.
 */
final class MatroskaCueReader {

  private static final long EBML = 0x1A45DFA3L;
  private static final long SEGMENT = 0x18538067L;
  private static final long SEEK_HEAD = 0x114D9B74L;
  private static final long SEEK = 0x4DBBL;
  private static final long SEEK_ID = 0x53ABL;
  private static final long SEEK_POSITION = 0x53ACL;
  private static final long INFO = 0x1549A966L;
  private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
  private static final long MUXING_APP = 0x4D80L;
  private static final long WRITING_APP = 0x5741L;
  private static final long TRACKS = 0x1654AE6BL;
  private static final long TRACK_ENTRY = 0xAEL;
  private static final long TRACK_NUMBER = 0xD7L;
  private static final long TRACK_TYPE = 0x83L;
  private static final long TRACK_TIMESTAMP_SCALE = 0x23314FL;
  private static final long CUES = 0x1C53BB6BL;
  private static final long CUE_POINT = 0xBBL;
  private static final long CUE_TIME = 0xB3L;
  private static final long CUE_TRACK_POSITIONS = 0xB7L;
  private static final long CUE_TRACK = 0xF7L;
  private static final long CLUSTER = 0x1F43B675L;
  private static final long VIDEO_TRACK_TYPE = 1;
  private static final long DEFAULT_TIMESTAMP_SCALE = 1_000_000;
  private static final long NANOSECONDS_PER_SECOND = 1_000_000_000;
  private static final long UNKNOWN_SIZE = -1;
  private static final int MAXIMUM_HEADER_BYTES = 12;
  private static final long MAXIMUM_ELEMENT_BYTES = 64L << 20;

  private MatroskaCueReader() {}

  /**
   * Reads the Cues of the video track at {@code videoTrackOrdinal}: zero for the first TrackEntry
   * whose type is video, one for the second, and so on.
   */
  static KeyframeIndex read(Path source, int videoTrackOrdinal) throws IOException {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      return read(TopLevel.read(channel), videoTrackOrdinal);
    } catch (ContainerIndexException | BufferUnderflowException | IllegalArgumentException _) {
      return unusable(
          KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
    }
  }

  private static KeyframeIndex read(TopLevel topLevel, int videoTrackOrdinal) {
    var tracks = topLevel.tracks().orElseThrow(() -> malformed("Tracks"));
    var track =
        children(tracks, TRACK_ENTRY).stream()
            .filter(entry -> unsigned(entry, TRACK_TYPE).orElse(0L) == VIDEO_TRACK_TYPE)
            .skip(videoTrackOrdinal)
            .findFirst()
            .orElseThrow(() -> malformed("video TrackEntry"));
    var trackNumber = unsigned(track, TRACK_NUMBER).orElseThrow(() -> malformed("TrackNumber"));
    if (floatValue(track, TRACK_TIMESTAMP_SCALE).orElse(1.0) != 1.0) {
      return unusable(
          KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
    }

    if (topLevel.cues().isEmpty()) {
      return unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX);
    }

    var info = topLevel.info().orElseThrow(() -> malformed("Info"));
    var cueTimes = cueTimes(topLevel.cues().get(), trackNumber);
    if (!writesCueForEveryVideoKeyframe(info) || cueTimes.isEmpty()) {
      return unusable(
          KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
    }

    var timestampScale = unsigned(info, TIMESTAMP_SCALE).orElse(DEFAULT_TIMESTAMP_SCALE);
    var divisor =
        BigInteger.valueOf(timestampScale)
            .gcd(BigInteger.valueOf(NANOSECONDS_PER_SECOND))
            .longValue();
    return new Listed(
        new TimeBase(timestampScale / divisor, NANOSECONDS_PER_SECOND / divisor),
        cueTimes.stream().map(time -> new KeyframeTime(time, time)).toList());
  }

  private static boolean writesCueForEveryVideoKeyframe(ByteBuffer info) {
    return string(info, MUXING_APP).filter(app -> app.startsWith("Lavf")).isPresent()
        || string(info, WRITING_APP).filter(app -> app.startsWith("mkvmerge")).isPresent();
  }

  private static TreeSet<Long> cueTimes(ByteBuffer cues, long trackNumber) {
    var times = new TreeSet<Long>();
    for (var point : children(cues, CUE_POINT)) {
      var listsTrack =
          children(point, CUE_TRACK_POSITIONS).stream()
              .anyMatch(positions -> unsigned(positions, CUE_TRACK).orElse(0L) == trackNumber);
      if (listsTrack) {
        times.add(unsigned(point, CUE_TIME).orElseThrow(() -> malformed("CueTime")));
      }
    }

    return times;
  }

  private static Optional<Long> unsigned(ByteBuffer parent, long id) {
    return child(parent, id).map(MatroskaCueReader::unsigned);
  }

  private static long unsigned(ByteBuffer value) {
    if (value.remaining() > Long.BYTES) {
      throw malformed("unsigned integer");
    }

    long result = 0;
    while (value.hasRemaining()) {
      result = (result << 8) | (value.get() & 0xFF);
    }

    return result;
  }

  private static Optional<Double> floatValue(ByteBuffer parent, long id) {
    return child(parent, id)
        .map(
            value ->
                switch (value.remaining()) {
                  case Float.BYTES -> (double) value.getFloat();
                  case Double.BYTES -> value.getDouble();
                  default -> throw malformed("float");
                });
  }

  private static Optional<String> string(ByteBuffer parent, long id) {
    return child(parent, id)
        .map(
            value -> {
              var bytes = new byte[value.remaining()];
              value.get(bytes);
              return new String(bytes, StandardCharsets.UTF_8);
            });
  }

  private static Optional<ByteBuffer> child(ByteBuffer parent, long id) {
    return children(parent, id).stream().findFirst();
  }

  private static List<ByteBuffer> children(ByteBuffer parent, long id) {
    var matches = new ArrayList<ByteBuffer>();
    var buffer = parent.duplicate();
    while (buffer.hasRemaining()) {
      var header = Header.read(buffer);
      if (header.size() == UNKNOWN_SIZE || header.size() > buffer.remaining()) {
        throw malformed("element size");
      }

      var end = buffer.position() + (int) header.size();
      if (header.id() == id) {
        matches.add(buffer.duplicate().limit(end).slice());
      }

      buffer.position(end);
    }

    return matches;
  }

  private static Unusable unusable(KeyframePacketScanReason reason) {
    return new Unusable(reason);
  }

  private static ContainerIndexException malformed(String detail) {
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

  /** The Info, Tracks and Cues elements of the first Segment, as the demuxer finds them. */
  private record TopLevel(
      Optional<ByteBuffer> info, Optional<ByteBuffer> tracks, Optional<ByteBuffer> cues) {

    static TopLevel read(FileChannel channel) throws IOException {
      var segment = firstSegment(channel);
      var seekPositions = new HashMap<Long, Long>();
      var found = elementsBeforeFirstCluster(channel, segment, seekPositions);
      for (var id : List.of(INFO, TRACKS, CUES)) {
        if (!found.containsKey(id) && seekPositions.containsKey(id)) {
          seek(channel, segment.dataStart() + seekPositions.get(id), id)
              .ifPresent(payload -> found.put(id, payload));
        }
      }

      return new TopLevel(
          Optional.ofNullable(found.get(INFO)),
          Optional.ofNullable(found.get(TRACKS)),
          Optional.ofNullable(found.get(CUES)));
    }

    private static FileElement firstSegment(FileChannel channel) throws IOException {
      var ebml = element(channel, 0);
      if (ebml.id() != EBML || ebml.size() == UNKNOWN_SIZE) {
        throw malformed("EBML header");
      }

      var segment = element(channel, ebml.end());
      if (segment.id() != SEGMENT) {
        throw malformed("Segment");
      }

      return segment;
    }

    /**
     * Collects the Info, Tracks and Cues elements that precede the first Cluster, and adds the
     * positions each SeekHead lists to {@code seekPositions}.
     */
    private static Map<Long, ByteBuffer> elementsBeforeFirstCluster(
        FileChannel channel, FileElement segment, Map<Long, Long> seekPositions)
        throws IOException {
      var fileSize = channel.size();
      var segmentEnd =
          segment.size() == UNKNOWN_SIZE ? fileSize : Math.min(segment.end(), fileSize);
      var found = new HashMap<Long, ByteBuffer>();
      var position = segment.dataStart();
      while (position < segmentEnd) {
        var element = element(channel, position);
        if (element.id() == CLUSTER || element.size() == UNKNOWN_SIZE) {
          break;
        }

        if (element.id() == SEEK_HEAD) {
          seekPositions(payload(channel, element), seekPositions);
        }

        if (element.id() == INFO || element.id() == TRACKS || element.id() == CUES) {
          found.putIfAbsent(element.id(), payload(channel, element));
        }

        position = element.end();
      }

      return found;
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
        var id = child(seek, SEEK_ID).map(MatroskaCueReader::unsigned);
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
