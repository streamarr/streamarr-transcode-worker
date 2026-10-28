package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the timing of each packet from what FFmpeg's framecrc muxer writes of one video stream: a
 * header whose {@code #tb 0:} line names the stream's time base, then one line for each packet with
 * its stream index, decode time, presentation time, duration, size and checksum, and its flags when
 * they are other than a keyframe's alone.
 */
final class FramecrcTimings {

  private static final int KEYFRAME = 0x1;
  private static final Pattern TIME_BASE = Pattern.compile("#tb 0: (\\d+)/(\\d+)");
  private static final Pattern RECORD =
      Pattern.compile(
          "0, *-?\\d+, *-?\\d+, *(-?\\d+), *\\d+, 0x[0-9a-f]{8}"
              + "(?:, F=0x([0-9A-F]{1,8}))?(?:, S=.*)?");

  private final BufferedReader lines;
  private Optional<TimeBase> timeBase = Optional.empty();

  FramecrcTimings(InputStream framecrc) {
    this.lines = new BufferedReader(new InputStreamReader(framecrc, StandardCharsets.US_ASCII));
  }

  /**
   * The next packet's timing; empty at the end of the framecrc.
   *
   * @throws FragmentedMp4Exception when a line is neither a header line nor the record of a packet
   *     of the one stream, or a record precedes the time base
   */
  Optional<VideoPacketTiming> next() throws IOException {
    for (var line = lines.readLine(); line != null; line = lines.readLine()) {
      if (line.startsWith("#tb")) {
        timeBase = Optional.of(timeBaseOf(line));
        continue;
      }

      if (!line.startsWith("#")) {
        return Optional.of(packetOf(line));
      }
    }

    return Optional.empty();
  }

  private static TimeBase timeBaseOf(String line) {
    var matcher = TIME_BASE.matcher(line);
    if (!matcher.matches()) {
      throw unusable("FFmpeg wrote the time base " + line);
    }

    try {
      return new TimeBase(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)));
    } catch (IllegalArgumentException _) {
      throw unusable("FFmpeg wrote the time base " + line);
    }
  }

  private VideoPacketTiming packetOf(String line) {
    var matcher = RECORD.matcher(line);
    if (!matcher.matches()) {
      throw unusable("FFmpeg wrote a line that is no record of the video stream: " + line);
    }

    var flags = Optional.ofNullable(matcher.group(2)).map(hex -> Integer.parseUnsignedInt(hex, 16));
    try {
      return VideoPacketTiming.builder()
          .keyframe(flags.map(packetFlags -> (packetFlags & KEYFRAME) != 0).orElse(true))
          .duration(Long.parseLong(matcher.group(1)))
          .timeBase(
              timeBase.orElseThrow(() -> unusable("a record precedes the time base: " + line)))
          .build();
    } catch (NumberFormatException _) {
      throw unusable("a packet's duration does not fit 64 bits: " + line);
    }
  }

  private static FragmentedMp4Exception unusable(String detail) {
    return new FragmentedMp4Exception(Reason.UNUSABLE_VIDEO_TIMING, detail);
  }
}
