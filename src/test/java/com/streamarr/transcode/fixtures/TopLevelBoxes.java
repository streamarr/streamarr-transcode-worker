package com.streamarr.transcode.fixtures;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Walks the top-level ISOBMFF boxes of a recorded or built fragmented MP4 output. */
public final class TopLevelBoxes {

  // The recordings and the outputs tests build use compact box headers only.
  private static final int HEADER_BYTES = 8;

  private TopLevelBoxes() {}

  /** The output's top-level boxes, in order. */
  public static List<Box> of(byte[] output) {
    var buffer = ByteBuffer.wrap(output);
    var boxes = new ArrayList<Box>();
    var start = 0;
    while (start < output.length) {
      var size = buffer.getInt(start);
      if (size < HEADER_BYTES) {
        throw new IllegalArgumentException("box at " + start + " has no compact size: " + size);
      }

      var type = new String(output, start + 4, 4, StandardCharsets.US_ASCII);
      boxes.add(new Box(start, size, type));
      start += size;
    }

    return List.copyOf(boxes);
  }

  /** One top-level box: where it starts in the output, its size and its four-character type. */
  public record Box(int start, int size, String type) {

    public int end() {
      return start + size;
    }

    /** Whether a reader that took this many bytes of the output has begun to read the body. */
    public boolean hasBodyBegunAfter(int bytesTaken) {
      return bytesTaken > start + HEADER_BYTES;
    }
  }
}
