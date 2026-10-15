package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Removes the SEI messages from every {@code hvc1} decoder configuration record of an
 * initialization segment, and resizes each box that holds a record. When a copy's source keeps its
 * parameter sets only in its samples, FFmpeg builds the record from the first sample and copies
 * that sample's SEI messages into it. Attempts that start at different keyframes then write
 * different records, which ADR 0037 forbids. The samples keep their SEI messages.
 */
final class HevcRecordSeiRemoval {

  // The NAL unit types of prefix and suffix SEI messages (ITU-T H.265 table 7-1).
  private static final Set<Integer> SEI_TYPES = Set.of(39, 40);
  // The stsd fields before its sample entries: version, flags and entry count.
  private static final int STSD_FIELDS = 8;

  private HevcRecordSeiRemoval() {}

  /**
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when a
   *     box on the way to a record declares a size that its parent cannot hold, or a record ends
   *     before its arrays do
   */
  static InitializationSegment removeSeiMessages(InitializationSegment segment) {
    return new InitializationSegment(
        rebuiltChildren("initialization segment", ByteBuffer.wrap(segment.bytes())));
  }

  private static byte[] rebuiltChildren(String parent, ByteBuffer content) {
    var rebuilt = new ByteArrayOutputStream();
    while (content.hasRemaining()) {
      var start = content.position();
      var header = BoxHeader.read(new BoxFields(parent, content));
      var bodyBytes = header.size() - header.length();
      if (bodyBytes < 0 || bodyBytes > content.remaining()) {
        throw FragmentedMp4Exception.malformed(
            header.type() + " in " + parent + " declares " + header.size() + " bytes");
      }

      var headerBytes = bytesOf(content.slice(start, header.length()));
      var body = content.slice(content.position(), (int) bodyBytes);
      content.position(content.position() + (int) bodyBytes);
      rebuilt.writeBytes(resized(headerBytes, rebuiltBody(header.type(), body)));
    }

    return rebuilt.toByteArray();
  }

  private static byte[] rebuiltBody(String type, ByteBuffer body) {
    return switch (type) {
      case "moov", "trak", "mdia", "minf", "stbl" -> rebuiltChildren(type, body);
      case "stsd" -> rebuiltAfter(type, body, STSD_FIELDS);
      case "hvc1" -> rebuiltAfter(type, body, CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES);
      case "hvcC" -> withoutSeiArrays(body);
      default -> bytesOf(body);
    };
  }

  private static byte[] rebuiltAfter(String type, ByteBuffer body, int fieldBytes) {
    var children = rebuiltChildren(type, new BoxView(type, body).after(fieldBytes).body());
    return concat(bytesOf(body.slice(0, fieldBytes)), children);
  }

  private static byte[] withoutSeiArrays(ByteBuffer hvcC) {
    var reading = hvcC.duplicate();
    var fields = new BoxFields("hvcC", reading);
    fields.skip(CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS);
    var arrays = fields.u8();
    var kept = new ByteArrayOutputStream();
    var keptArrays = 0;
    for (var array = 0; array < arrays; array++) {
      var arrayStart = reading.position();
      var type = fields.u8() & 0x3F;
      var nalUnits = fields.u16();
      for (var nalUnit = 0; nalUnit < nalUnits; nalUnit++) {
        fields.skip(fields.u16());
      }

      if (!SEI_TYPES.contains(type)) {
        kept.writeBytes(bytesOf(hvcC.slice(arrayStart, reading.position() - arrayStart)));
        keptArrays++;
      }
    }

    return concat(
        bytesOf(hvcC.slice(0, CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS)),
        new byte[] {(byte) keptArrays},
        kept.toByteArray(),
        bytesOf(reading));
  }

  // The box's own header, with its 32-bit or 64-bit size changed to fit the body.
  private static byte[] resized(byte[] header, byte[] body) {
    var box = ByteBuffer.allocate(header.length + body.length).put(header).put(body);
    if (header.length == BoxHeader.LARGE_LENGTH) {
      return box.putLong(BoxHeader.COMPACT_LENGTH, box.capacity()).array();
    }

    return box.putInt(0, box.capacity()).array();
  }

  private static byte[] bytesOf(ByteBuffer buffer) {
    var bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  private static byte[] concat(byte[]... parts) {
    var joined = new ByteArrayOutputStream();
    for (var part : parts) {
      joined.writeBytes(part);
    }

    return joined.toByteArray();
  }
}
