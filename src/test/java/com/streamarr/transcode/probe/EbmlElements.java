package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Builds the bytes of EBML elements, for the tests that read Matroska files. */
final class EbmlElements {

  private EbmlElements() {}

  /** The EBML header, then a Segment that holds {@code segmentChildren}. */
  static byte[] document(byte[]... segmentChildren) {
    var header = element(0x1A45DFA3, element(0x4282, "matroska".getBytes(StandardCharsets.UTF_8)));
    return concat(header, element(0x18538067, segmentChildren));
  }

  /** The CodecID element of an HEVC video track; FFmpeg keeps a video track only with one. */
  static byte[] hevcCodecId() {
    return element(0x86, "V_MPEGH/ISO/HEVC".getBytes(StandardCharsets.US_ASCII));
  }

  static byte[] element(long id, byte[]... children) {
    var payload = concat(children);
    var size = ByteBuffer.allocate(8).putLong(payload.length).array();
    size[0] = 0x01;
    return concat(unsigned(id), size, payload);
  }

  static byte[] unsigned(long value) {
    var bytes = ByteBuffer.allocate(8).putLong(value).array();
    var first = 0;
    while (first < 7 && bytes[first] == 0) {
      first++;
    }

    var trimmed = new byte[8 - first];
    System.arraycopy(bytes, first, trimmed, 0, trimmed.length);
    return trimmed;
  }

  static byte[] concat(byte[]... parts) {
    var output = new ByteArrayOutputStream();
    for (var part : parts) {
      output.writeBytes(part);
    }

    return output.toByteArray();
  }
}
