package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A stream's extradata, which ffprobe's -show_data prints as a hex dump. Each line holds an 8-digit
 * offset, a colon and a space, up to 16 bytes as hexadecimal in groups of two, and the bytes as
 * ASCII from column 51. For MP4 and Matroska, the extradata is the decoder configuration record.
 */
final class Extradata {

  /** The offset, then the 40 columns that hold the hexadecimal bytes and their spaces. */
  private static final Pattern LINE = Pattern.compile("[0-9a-f]{8}: ([0-9a-f ]{1,40}).*");

  private Extradata() {}

  /** The extradata's bytes; empty when ffprobe printed none or printed a malformed dump. */
  static Optional<byte[]> of(JsonNode stream) {
    return dumped(FfprobeJson.text(stream, "extradata").orElse(""));
  }

  /** The bytes of a -show_data hex dump; empty when the dump holds none or is malformed. */
  static Optional<byte[]> dumped(String dump) {
    var bytes = new ByteArrayOutputStream();
    for (var line : dump.split("\n")) {
      if (line.isBlank()) {
        continue;
      }

      var match = LINE.matcher(line);
      if (!match.matches()) {
        return Optional.empty();
      }

      var hex = match.group(1).replace(" ", "");
      if (hex.length() % 2 != 0) {
        return Optional.empty();
      }

      bytes.writeBytes(HexFormat.of().parseHex(hex));
    }

    return bytes.size() == 0 ? Optional.empty() : Optional.of(bytes.toByteArray());
  }
}
