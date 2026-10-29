package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Stands in for the framecrc output of the FFmpeg that copies the source. */
final class CopiedVideoTiming {

  private static final String URL_SCHEME = "unix:";
  // The framecrc's time base differs from the track's timescale, as a source's often does.
  private static final long TICKS_PER_TRACK_TICK = 2;

  private CopiedVideoTiming() {}

  /**
   * The framecrc FFmpeg writes of the copy's video packets, whose timing and keyframes the copy's
   * own video samples state, in a time base twice as fine as their track's timescale, with the
   * header lines and padded columns that FFmpeg writes after the time base.
   */
  static String framecrcOf(byte[] copy) throws IOException {
    var stream = Mp4Stream.read(Mp4Stream.readerOf(copy));
    var timescale =
        stream.fragments().stream()
            .flatMap(fragment -> fragment.tracks().video().stream())
            .mapToLong(VideoTrack::timescale)
            .findFirst()
            .orElse(1);
    return "#tb 0: 1/"
        + TICKS_PER_TRACK_TICK * timescale
        + "\n#media_type 0: video\n#codec_id 0: hevc\n"
        + stream.fragments().stream()
            .flatMap(fragment -> fragment.videoSamples().stream())
            .map(
                sample ->
                    String.format(
                        Locale.ROOT,
                        "0, %10d, %10d, %8d, %8d, 0x00000000%s\n",
                        TICKS_PER_TRACK_TICK * sample.decodeTime(),
                        TICKS_PER_TRACK_TICK * sample.presentationTime(),
                        TICKS_PER_TRACK_TICK * sample.duration(),
                        sample.size(),
                        track(stream).startOf(sample).syncSample() ? "" : ", F=0x0"))
            .collect(Collectors.joining());
  }

  private static VideoTrack track(Mp4Stream stream) {
    return stream.fragments().getFirst().tracks().video().orElseThrow();
  }

  /**
   * Writes the framecrc to the socket that the copying FFmpeg's last argument names, as its second
   * output does, before the test starts that FFmpeg's stand-in, and closes the connection.
   */
  static void writeTo(List<String> copyCommand, String framecrc) throws IOException {
    var url = copyCommand.getLast();
    assertThat(url).as("the copying FFmpeg's last output").startsWith(URL_SCHEME);
    var socket = UnixDomainSocketAddress.of(Path.of(url.substring(URL_SCHEME.length())));
    try (var ffmpeg = SocketChannel.open(socket)) {
      var bytes = ByteBuffer.wrap(framecrc.getBytes(StandardCharsets.US_ASCII));
      while (bytes.hasRemaining()) {
        ffmpeg.write(bytes);
      }
    }
  }
}
