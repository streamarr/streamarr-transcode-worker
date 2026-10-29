package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Stands in for the framecrc output of the FFmpeg that copies the source. */
public final class CopiedVideoTiming {

  private static final String URL_SCHEME = "unix:";

  private CopiedVideoTiming() {}

  /**
   * Writes the framecrc to the socket that the copying FFmpeg's last argument names, as its second
   * output does, before the test starts that FFmpeg's stand-in, and closes the connection.
   */
  public static void writeTo(List<String> copyCommand, String framecrc) throws IOException {
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
