package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Reads byte ranges of a source file for the container index readers. */
final class ContainerFileBytes {

  private ContainerFileBytes() {}

  /**
   * Reads {@code length} bytes at {@code position}. A range past the end of the file is unusable.
   */
  static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
    var buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, position + buffer.position()) < 0) {
        throw new ContainerIndexException(
            "Unusable container index: bytes past the end of the file");
      }
    }

    return buffer.flip();
  }
}
