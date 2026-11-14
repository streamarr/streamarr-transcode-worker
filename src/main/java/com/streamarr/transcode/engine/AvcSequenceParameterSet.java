package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;
import lombok.Builder;

final class AvcSequenceParameterSet {

  // The H.264 profiles whose SPS carries chroma_format_idc and the fields after it (ITU-T H.264
  // 7.3.2.1.1).
  private static final Set<Integer> AVC_PROFILES_WITH_CHROMA_FORMAT =
      Set.of(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135);
  private static final int CHROMA_FORMAT_444 = 3;

  private AvcSequenceParameterSet() {}

  @Builder(toBuilder = true)
  record ColourDescription(int primaries, int transfer, int matrix, boolean fullRange) {}

  @Builder
  private record ColourLayout(
      int start,
      int end,
      int stopBit,
      boolean addsVui,
      int videoFormat,
      ColourDescription colour) {}

  static ColourDescription colourDescriptionOf(byte[] nalUnit) {
    return colourLayout(new RbspBits(nalUnit)).colour();
  }

  static byte[] withMatrix(byte[] nalUnit, AssumedSourceMatrix matrix) {
    var source = new RbspBits(nalUnit);
    var layout = colourLayout(source);
    var output = new BitOutput();
    output.copy(source.bytes, 0, layout.start());
    if (layout.addsVui()) {
      output.bits(4, 3); // vui_parameters_present_flag, no aspect ratio or overscan
    }

    output.bits(1, 1);
    output.bits(layout.videoFormat(), 3);
    output.bits(layout.colour().fullRange() ? 1 : 0, 1);
    output.bits(1, 1);
    output.bits(layout.colour().primaries(), 8);
    output.bits(layout.colour().transfer(), 8);
    output.bits(matrix == AssumedSourceMatrix.BT709 ? 1 : 6, 8);
    if (layout.addsVui()) {
      output.bits(0, 6); // remaining VUI presence flags
    }

    // Retain the unmodified syntax through rbsp_stop_one_bit, then align the new length.
    output.copy(source.bytes, layout.end(), layout.stopBit() + 1);
    return output.nalUnit(source.trailingBytesAfter(layout.stopBit()));
  }

  private static ColourLayout colourLayout(RbspBits source) {
    try {
      return readColourLayout(source);
    } catch (RbspBits.EndOfSequenceParameterSet _) {
      throw new FragmentedMp4Exception(
          FragmentedMp4Exception.Reason.OUTPUT_MISMATCH,
          "the H.264 sequence parameter set cannot be read");
    }
  }

  private static ColourLayout readColourLayout(RbspBits source) {
    if (source.bytes.length == 0 || (source.bytes[0] & 0x9F) != 7) {
      throw new RbspBits.EndOfSequenceParameterSet();
    }

    if (!frameMacroblocksOnly(source)) {
      source.skip(1);
    }

    source.skip(1);
    if (source.bits(1) == 1) {
      for (var side = 0; side < 4; side++) {
        source.skipUnsignedExpGolomb();
      }
    }

    var start = source.position;
    var defaultColour = ColourDescription.builder().primaries(2).transfer(2).matrix(2).build();
    if (source.bits(1) == 0) {
      var stopBit = trailingBits(source);
      return ColourLayout.builder()
          .start(start)
          .stopBit(stopBit)
          .end(start + 1)
          .addsVui(true)
          .videoFormat(5)
          .colour(defaultColour)
          .build();
    }

    skipAspectAndOverscan(source);
    start = source.position;
    var videoFormat = 5;
    var colour = defaultColour;
    if (source.bits(1) == 1) {
      videoFormat = source.bits(3);
      colour = videoColour(source);
    }

    var end = source.position;
    skipRemainingVui(source);
    var stopBit = trailingBits(source);
    return ColourLayout.builder()
        .start(start)
        .stopBit(stopBit)
        .end(end)
        .videoFormat(videoFormat)
        .colour(colour)
        .build();
  }

  private static ColourDescription videoColour(RbspBits source) {
    var colour =
        ColourDescription.builder()
            .primaries(2)
            .transfer(2)
            .matrix(2)
            .fullRange(source.bits(1) == 1);
    if (source.bits(1) == 1) {
      colour.primaries(source.bits(8)).transfer(source.bits(8)).matrix(source.bits(8));
    }

    return colour.build();
  }

  private static void skipAspectAndOverscan(RbspBits source) {
    if (source.bits(1) == 1 && source.bits(8) == 255) {
      source.skip(16);
      source.skip(16);
    }

    if (source.bits(1) == 1) {
      source.skip(1);
    }
  }

  private static void skipRemainingVui(RbspBits source) {
    if (source.bits(1) == 1) {
      source.skipUnsignedExpGolomb();
      source.skipUnsignedExpGolomb();
    }

    if (source.bits(1) == 1) {
      source.skip(32);
      source.skip(32);
      source.skip(1);
    }

    var nalHrd = skipHrdIfPresent(source);
    var vclHrd = skipHrdIfPresent(source);
    if (nalHrd || vclHrd) {
      source.skip(1);
    }

    source.skip(1);
    if (source.bits(1) == 1) {
      source.skip(1);
      for (var field = 0; field < 6; field++) {
        source.skipUnsignedExpGolomb();
      }
    }
  }

  private static boolean skipHrdIfPresent(RbspBits source) {
    if (source.bits(1) == 0) {
      return false;
    }

    var cpbCountMinusOne = source.unsignedExpGolomb();
    if (cpbCountMinusOne < 0 || cpbCountMinusOne > 31) {
      throw new RbspBits.EndOfSequenceParameterSet();
    }

    source.skip(8);
    for (var cpb = 0; cpb <= cpbCountMinusOne; cpb++) {
      source.skipUnsignedExpGolomb();
      source.skipUnsignedExpGolomb();
      source.skip(1);
    }

    source.skip(20);
    return true;
  }

  // Locate the stop bit from the syntax, not the last set bit: decoders may ignore bytes
  // following the aligned RBSP. Keep those bytes after the rewritten RBSP as well.
  private static int trailingBits(RbspBits source) {
    var stopBit = source.position;
    if (source.bits(1) != 1 || source.bits((-source.position) & 7) != 0) {
      throw new RbspBits.EndOfSequenceParameterSet();
    }

    return stopBit;
  }

  static boolean codesFields(byte[] nalUnit) {
    try {
      return !frameMacroblocksOnly(new RbspBits(nalUnit));
    } catch (RbspBits.EndOfSequenceParameterSet _) {
      return false;
    }
  }

  // Reads ITU-T H.264 7.3.2.1.1 up to frame_mbs_only_flag.
  private static boolean frameMacroblocksOnly(RbspBits sps) {
    sps.skip(8);
    var profile = sps.bits(8);
    sps.skip(16);
    sps.skipUnsignedExpGolomb();
    if (AVC_PROFILES_WITH_CHROMA_FORMAT.contains(profile)) {
      skipChromaFormatAndScalingLists(sps);
    }

    sps.skipUnsignedExpGolomb();
    skipPictureOrderCount(sps);
    sps.skipUnsignedExpGolomb();
    sps.skip(1);
    sps.skipUnsignedExpGolomb();
    sps.skipUnsignedExpGolomb();
    return sps.bits(1) == 1;
  }

  private static void skipChromaFormatAndScalingLists(RbspBits sps) {
    var chromaFormat = sps.unsignedExpGolomb();
    if (chromaFormat == CHROMA_FORMAT_444) {
      sps.skip(1);
    }

    sps.skipUnsignedExpGolomb();
    sps.skipUnsignedExpGolomb();
    sps.skip(1);
    if (sps.bits(1) == 0) {
      return;
    }

    var lists = chromaFormat == CHROMA_FORMAT_444 ? 12 : 8;
    for (var list = 0; list < lists; list++) {
      if (sps.bits(1) == 1) {
        skipScalingList(sps, list < 6 ? 16 : 64);
      }
    }
  }

  // ITU-T H.264 7.3.2.1.1.1: a list ends early once a delta makes the next scale 0.
  private static void skipScalingList(RbspBits sps, int size) {
    var lastScale = 8;
    var nextScale = 8;
    for (var entry = 0; entry < size && nextScale != 0; entry++) {
      nextScale = Math.floorMod(lastScale + sps.signedExpGolomb(), 256);
      lastScale = nextScale == 0 ? lastScale : nextScale;
    }
  }

  private static void skipPictureOrderCount(RbspBits sps) {
    switch (sps.unsignedExpGolomb()) {
      case 0 -> sps.skipUnsignedExpGolomb();
      case 1 -> {
        sps.skip(1);
        sps.signedExpGolomb();
        sps.signedExpGolomb();
        var cycle = sps.unsignedExpGolomb();
        for (var frame = 0; frame < cycle; frame++) {
          sps.signedExpGolomb();
        }
      }
      default -> {
        // pic_order_cnt_type 2 carries no fields.
      }
    }
  }

  /** The bits of a NAL unit's payload, without its emulation prevention bytes. */
  private static final class RbspBits {

    // No SPS field that this reader reaches exceeds 32 bits.
    private static final int LONGEST_EXP_GOLOMB_PREFIX = 31;

    private final byte[] bytes;
    private final byte[] original;
    private int position;

    RbspBits(byte[] nalUnit) {
      this.original = nalUnit;
      this.bytes = withoutEmulationPrevention(nalUnit);
    }

    private static byte[] withoutEmulationPrevention(byte[] nalUnit) {
      var payload = new ByteArrayOutputStream(nalUnit.length);
      var zeros = 0;
      for (var value : nalUnit) {
        if (zeros >= 2 && value == 3) {
          zeros = 0;
          continue;
        }

        zeros = value == 0 ? zeros + 1 : 0;
        payload.write(value);
      }

      return payload.toByteArray();
    }

    byte[] trailingBytesAfter(int stopBit) {
      var remaining = stopBit / 8 + 1;
      var offset = 0;
      var zeros = 0;
      while (remaining > 0) {
        var value = original[offset++];
        if (zeros >= 2 && value == 3) {
          zeros = 0;
          continue;
        }

        zeros = value == 0 ? zeros + 1 : 0;
        remaining--;
      }

      return Arrays.copyOfRange(original, offset, original.length);
    }

    int bits(int count) {
      var value = 0;
      for (var bit = 0; bit < count; bit++) {
        if (position >= bytes.length * 8) {
          throw new EndOfSequenceParameterSet();
        }

        value = (value << 1) | ((bytes[position >> 3] >> (7 - (position & 7))) & 1);
        position++;
      }

      return value;
    }

    void skip(int count) {
      bits(count);
    }

    int unsignedExpGolomb() {
      var leadingZeros = 0;
      while (bits(1) == 0) {
        leadingZeros++;
        if (leadingZeros > LONGEST_EXP_GOLOMB_PREFIX) {
          throw new EndOfSequenceParameterSet();
        }
      }

      return (int) ((1L << leadingZeros) - 1 + bits(leadingZeros));
    }

    void skipUnsignedExpGolomb() {
      unsignedExpGolomb();
    }

    int signedExpGolomb() {
      var code = unsignedExpGolomb();
      return (code & 1) == 1 ? (code + 1) / 2 : -(code / 2);
    }

    /** The SPS ended, or held an impossible value, before frame_mbs_only_flag. */
    private static final class EndOfSequenceParameterSet extends RuntimeException {
      EndOfSequenceParameterSet() {
        super(null, null, false, false);
      }
    }
  }

  private static final class BitOutput {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int current;
    private int filled;

    void copy(byte[] source, int start, int end) {
      for (var bit = start; bit < end; bit++) {
        bits((source[bit >> 3] >> (7 - (bit & 7))) & 1, 1);
      }
    }

    void bits(int value, int count) {
      for (var bit = count - 1; bit >= 0; bit--) {
        appendBit((value >>> bit) & 1);
      }
    }

    private void appendBit(int value) {
      current = (current << 1) | value;
      filled++;
      if (filled == 8) {
        bytes.write(current);
        filled = 0;
        current = 0;
      }
    }

    byte[] nalUnit(byte[] trailingBytes) {
      if (filled != 0) {
        bytes.write(current << (8 - filled));
      }

      var escaped = new ByteArrayOutputStream();
      var zeros = 0;
      for (var value : bytes.toByteArray()) {
        if (zeros == 2 && Byte.toUnsignedInt(value) <= 3) {
          escaped.write(3);
          zeros = 0;
        }

        escaped.write(value);
        zeros = value == 0 ? zeros + 1 : 0;
      }

      escaped.writeBytes(trailingBytes);
      return escaped.toByteArray();
    }
  }
}
