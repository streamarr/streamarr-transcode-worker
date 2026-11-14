package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.Set;

final class AvcSequenceParameterSet {

  // The H.264 profiles whose SPS carries chroma_format_idc and the fields after it (ITU-T H.264
  // 7.3.2.1.1).
  private static final Set<Integer> AVC_PROFILES_WITH_CHROMA_FORMAT =
      Set.of(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135);
  private static final int CHROMA_FORMAT_444 = 3;

  private AvcSequenceParameterSet() {}

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
    private int position;

    RbspBits(byte[] nalUnit) {
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
}
