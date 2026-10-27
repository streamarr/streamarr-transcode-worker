package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.MatroskaFile.children;
import static com.streamarr.transcode.probe.MatroskaFile.floatValue;
import static com.streamarr.transcode.probe.MatroskaFile.malformed;
import static com.streamarr.transcode.probe.MatroskaFile.string;
import static com.streamarr.transcode.probe.MatroskaFile.unsigned;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import com.streamarr.transcode.probe.MatroskaFile.TopLevel;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

  private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
  private static final long MUXING_APP = 0x4D80L;
  private static final long WRITING_APP = 0x5741L;
  private static final long TRACK_NUMBER = 0xD7L;
  private static final long TRACK_TIMESTAMP_SCALE = 0x23314FL;
  private static final long CUE_POINT = 0xBBL;
  private static final long CUE_TIME = 0xB3L;
  private static final long CUE_TRACK_POSITIONS = 0xB7L;
  private static final long CUE_TRACK = 0xF7L;
  private static final long DEFAULT_TIMESTAMP_SCALE = 1_000_000;
  private static final long NANOSECONDS_PER_SECOND = 1_000_000_000;

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
    var track = MatroskaFile.videoTrack(tracks, videoTrackOrdinal);
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

  private static Unusable unusable(KeyframePacketScanReason reason) {
    return new Unusable(reason);
  }
}
