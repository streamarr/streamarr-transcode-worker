package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rewrites the initialization segment that FFmpeg writes into the one the producer delivers. It
 * removes the SEI messages from every {@code hvc1} decoder configuration record, then writes the
 * static HDR metadata that a copy plans into that record as one prefix SEI array, and resizes each
 * box that holds a record. When a copy's source keeps its parameter sets only in its samples,
 * FFmpeg builds the record from the first sample and copies that sample's SEI messages into it.
 * Attempts that start at different keyframes would then write different records, which ADR 0037
 * forbids, so the record holds only what the job names. The samples keep their SEI messages.
 */
final class InitializationSegmentRewrite {

  // The NAL unit types of prefix and suffix SEI messages (ITU-T H.265 table 7-1).
  private static final Set<Integer> SEI_TYPES = Set.of(39, 40);
  // The stsd fields before its sample entries: version, flags and entry count.
  private static final int STSD_FIELDS = 8;

  private static final int PREFIX_SEI = 39;

  // The SEI NAL units that every hvc1 record of the segment holds after the rewrite.
  private final List<byte[]> seiUnits;

  private InitializationSegmentRewrite(List<byte[]> seiUnits) {
    this.seiUnits = seiUnits;
  }

  /**
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when a
   *     box on the way to a record declares a size that its parent cannot hold; with {@link
   *     FragmentedMp4Exception.Reason#OUTPUT_MISMATCH} when a record ends before its arrays do
   */
  static InitializationSegment rewrite(VideoDecision decision, InitializationSegment segment) {
    var rewrite = new InitializationSegmentRewrite(plannedSeiUnitsOf(decision));
    return new InitializationSegment(
        rewrite.rebuiltChildren("initialization segment", ByteBuffer.wrap(segment.bytes())));
  }

  /** The static HDR metadata SEI NAL units that the decision plans for an hvc1 record, in order. */
  static List<byte[]> plannedSeiUnitsOf(VideoDecision decision) {
    if (!(decision instanceof VideoDecision.Copy copy)) {
      return List.of();
    }

    return copy.plannedOutput().map(HevcStaticMetadataSei::nalUnitsOf).orElse(List.of());
  }

  private byte[] rebuiltChildren(String parent, ByteBuffer content) {
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

  private byte[] rebuiltBody(String type, ByteBuffer body) {
    return switch (type) {
      case "moov", "trak", "mdia", "minf", "stbl" -> rebuiltChildren(type, body);
      case "stsd" -> rebuiltAfter(type, body, STSD_FIELDS);
      case "hvc1" -> rebuiltAfter(type, body, CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES);
      case "hvcC" -> readable(() -> withPlannedSeiArray(body));
      default -> bytesOf(body);
    };
  }

  private byte[] rebuiltAfter(String type, ByteBuffer body, int fieldBytes) {
    var children = rebuiltChildren(type, new BoxView(type, body).after(fieldBytes).body());
    return concat(bytesOf(body.slice(0, fieldBytes)), children);
  }

  private byte[] withPlannedSeiArray(ByteBuffer hvcC) {
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

    if (!seiUnits.isEmpty()) {
      kept.writeBytes(plannedSeiArray());
      keptArrays++;
    }

    return concat(
        bytesOf(hvcC.slice(0, CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS)),
        new byte[] {(byte) keptArrays},
        kept.toByteArray(),
        bytesOf(reading));
  }

  // array_completeness stays 0: the samples keep their own SEI messages (ISO/IEC 14496-15
  // 8.3.2.1.3).
  private byte[] plannedSeiArray() {
    var array = ByteBuffer.allocate(3 + seiUnits.stream().mapToInt(unit -> 2 + unit.length).sum());
    array.put((byte) PREFIX_SEI).putShort((short) seiUnits.size());
    seiUnits.forEach(unit -> array.putShort((short) unit.length).put(unit));
    return array.array();
  }

  // A record that cannot be read is an output mismatch, as the output check reports it.
  private static byte[] readable(Supplier<byte[]> reading) {
    try {
      return reading.get();
    } catch (FragmentedMp4Exception e) {
      throw new FragmentedMp4Exception(
          Reason.OUTPUT_MISMATCH,
          "the video codec configuration cannot be read: " + e.getMessage());
    }
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
