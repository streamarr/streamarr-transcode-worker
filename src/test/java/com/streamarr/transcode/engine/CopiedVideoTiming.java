package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Stands in for the framecrc output of the FFmpeg that copies the source. */
final class CopiedVideoTiming {

  private static final String URL_SCHEME = "unix:";

  private CopiedVideoTiming() {}

  /**
   * The framecrc FFmpeg writes of the copy's video packets, whose timing and keyframes the copy's
   * own video samples state, in their track's timescale.
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
        + timescale
        + "\n"
        + stream.fragments().stream()
            .flatMap(fragment -> fragment.videoSamples().stream())
            .map(
                sample ->
                    "0, "
                        + sample.decodeTime()
                        + ", "
                        + sample.presentationTime()
                        + ", "
                        + sample.duration()
                        + ", "
                        + sample.size()
                        + ", 0x00000000"
                        + (track(stream).startOf(sample).syncSample() ? "" : ", F=0x0")
                        + "\n")
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
