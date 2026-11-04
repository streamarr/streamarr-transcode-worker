package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The prefix SEI NAL units that carry a copy's planned static HDR metadata, each message in a NAL
 * unit of its own (ADR 0036): the mastering display color volume (payload type 137, ITU-T H.265
 * D.2.28) and then the content light level (payload type 144, D.2.35). Each field takes the planned
 * value without conversion, because the planned values use the units of these fields.
 */
final class HevcStaticMetadataSei {

  // nal_unit_type 39 (PREFIX_SEI_NUT), nuh_layer_id 0, nuh_temporal_id_plus1 1.
  private static final byte[] PREFIX_SEI_HEADER = {0x4E, 0x01};
  private static final int MASTERING_DISPLAY = 137;
  private static final int CONTENT_LIGHT_LEVEL = 144;
  private static final int MASTERING_DISPLAY_BYTES = 24;
  private static final int CONTENT_LIGHT_LEVEL_BYTES = 4;
  private static final int RBSP_STOP_BYTE = 0x80;

  private HevcStaticMetadataSei() {}

  /** The NAL units, without length prefixes; empty when the output plans neither message. */
  static List<byte[]> nalUnitsOf(PlannedCopyOutput output) {
    var units = new ArrayList<byte[]>();
    output.masteringDisplay().map(HevcStaticMetadataSei::masteringDisplay).ifPresent(units::add);
    output.contentLightLevel().map(HevcStaticMetadataSei::contentLightLevel).ifPresent(units::add);
    return List.copyOf(units);
  }

  // The SEI message lists the primaries as green, blue and red (ITU-T H.265 D.3.28).
  private static byte[] masteringDisplay(PlannedCopyOutput.MasteringDisplay display) {
    var payload = ByteBuffer.allocate(MASTERING_DISPLAY_BYTES);
    for (var primary :
        List.of(display.green(), display.blue(), display.red(), display.whitePoint())) {
      payload.putShort((short) primary.x()).putShort((short) primary.y());
    }

    payload.putInt((int) display.maxLuminance()).putInt((int) display.minLuminance());
    return nalUnit(MASTERING_DISPLAY, payload.array());
  }

  private static byte[] contentLightLevel(PlannedCopyOutput.ContentLightLevel level) {
    var payload =
        ByteBuffer.allocate(CONTENT_LIGHT_LEVEL_BYTES)
            .putShort((short) level.maxContentLightLevel())
            .putShort((short) level.maxFrameAverageLightLevel());
    return nalUnit(CONTENT_LIGHT_LEVEL, payload.array());
  }

  // Each payload type and size is below 255, so each takes one byte.
  private static byte[] nalUnit(int payloadType, byte[] payload) {
    var rbsp = new ByteArrayOutputStream();
    rbsp.write(payloadType);
    rbsp.write(payload.length);
    rbsp.writeBytes(payload);
    rbsp.write(RBSP_STOP_BYTE);
    var unit = new ByteArrayOutputStream();
    unit.writeBytes(PREFIX_SEI_HEADER);
    unit.writeBytes(escaped(rbsp.toByteArray()));
    return unit.toByteArray();
  }

  // Inserts an emulation prevention byte wherever two zero bytes precede a byte of at most 0x03.
  private static byte[] escaped(byte[] rbsp) {
    var escaped = new ByteArrayOutputStream(rbsp.length + rbsp.length / 2);
    var zeros = 0;
    for (var octet : rbsp) {
      var value = Byte.toUnsignedInt(octet);
      if (zeros >= 2 && value <= 0x03) {
        escaped.write(0x03);
        zeros = 0;
      }

      zeros = value == 0 ? zeros + 1 : 0;
      escaped.write(value);
    }

    return escaped.toByteArray();
  }
}
