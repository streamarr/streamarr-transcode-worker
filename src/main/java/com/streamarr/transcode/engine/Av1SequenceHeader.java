package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * The first sequence header OBU (AV1 5.5) of an AV1 sample, with a size field, and the fields that
 * an av1C record repeats in its second and third bytes (AV1 ISOBMFF binding 2.3.3): seq_profile and
 * the first operating point's seq_level_idx, then that point's seq_tier and color_config's
 * high_bitdepth, twelve_bit, mono_chrome, subsampling_x, subsampling_y and chroma_sample_position.
 */
final class Av1SequenceHeader {

  private static final int OBU_SEQUENCE_HEADER = 1;
  private static final int OBU_HAS_SIZE_FIELD = 0x02;
  private static final int PROFILE_PROFESSIONAL = 2;
  private static final int PROFILE_HIGH = 1;
  private static final int MAXIMUM_LEVEL_WITHOUT_TIER = 7;
  // color_config's colour description of sRGB, which implies 4:4:4 (AV1 6.4.2).
  private static final int CP_BT_709 = 1;
  private static final int TC_SRGB = 13;
  private static final int MC_IDENTITY = 0;

  private final byte[] obu;
  private final int profileAndLevel;
  private final int tierDepthAndChroma;

  private Av1SequenceHeader(byte[] obu, int profileAndLevel, int tierDepthAndChroma) {
    this.obu = obu;
    this.profileAndLevel = profileAndLevel;
    this.tierDepthAndChroma = tierDepthAndChroma;
  }

  /**
   * The first sequence header among the sample's OBUs.
   *
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the sample holds none;
   *     with {@link Reason#MALFORMED_BOX} when an OBU before it cannot be read or it ends before
   *     its color_config does
   */
  static Av1SequenceHeader firstIn(ByteBuffer sample) {
    var position = sample.position();
    while (position < sample.limit()) {
      var obu = Av1Obu.at(sample, position);
      if (obu.type() == OBU_SEQUENCE_HEADER) {
        return of(sample, obu);
      }

      position = obu.end();
    }

    throw new FragmentedMp4Exception(
        Reason.OUTPUT_MISMATCH, "the first AV1 video sample holds no sequence header");
  }

  /** The OBU's bytes, with a size field also when the sample's copy has none. */
  byte[] obu() {
    return obu.clone();
  }

  /** av1C's second byte: seq_profile, then seq_level_idx[0]. */
  int profileAndLevel() {
    return profileAndLevel;
  }

  /** av1C's third byte: seq_tier[0] first, chroma_sample_position last. */
  int tierDepthAndChroma() {
    return tierDepthAndChroma;
  }

  private static Av1SequenceHeader of(ByteBuffer sample, Av1Obu obu) {
    var payload = sample.slice(obu.payloadStart(), obu.end() - obu.payloadStart());
    var bits = new PayloadBits(payload);
    var profile = bits.read(3);
    bits.read(1);
    var reduced = bits.flag();
    var firstPoint = reduced ? new OperatingPoint(bits.read(5), 0) : operatingPoints(bits);
    skipFrameSizeAndTools(bits, reduced);
    var colour = colourConfig(bits, profile);
    return new Av1SequenceHeader(
        sizedObu(sample, obu, payload),
        (profile << 5) | firstPoint.level(),
        (firstPoint.tier() << 7) | colour);
  }

  // The header byte with obu_has_size_field set, its extension byte, a leb128 size and the payload.
  private static byte[] sizedObu(ByteBuffer sample, Av1Obu obu, ByteBuffer payload) {
    var header = Byte.toUnsignedInt(sample.get(obu.start()));
    if ((header & OBU_HAS_SIZE_FIELD) != 0) {
      return bytesOf(sample.slice(obu.start(), obu.end() - obu.start()));
    }

    var sized = new ByteArrayOutputStream();
    sized.write(header | OBU_HAS_SIZE_FIELD);
    sized.writeBytes(bytesOf(sample.slice(obu.start() + 1, obu.payloadStart() - obu.start() - 1)));
    for (var rest = payload.remaining(); ; rest >>>= 7) {
      if (rest < 0x80) {
        sized.write(rest);
        break;
      }

      sized.write((rest & 0x7F) | 0x80);
    }

    sized.writeBytes(bytesOf(payload));
    return sized.toByteArray();
  }

  // timing_info, decoder_model_info and every operating point (AV1 5.5.1).
  private static OperatingPoint operatingPoints(PayloadBits bits) {
    var bufferDelayLength = 0;
    if (bits.flag()) {
      bufferDelayLength = timingAndDecoderModelInfo(bits);
    }

    var initialDisplayDelay = bits.flag();
    var count = bits.read(5) + 1;
    var first = new OperatingPoint(0, 0);
    for (var point = 0; point < count; point++) {
      bits.read(12);
      var level = bits.read(5);
      var tier = level > MAXIMUM_LEVEL_WITHOUT_TIER ? bits.read(1) : 0;
      if (bufferDelayLength > 0 && bits.flag()) {
        bits.read(2 * bufferDelayLength + 1);
      }

      if (initialDisplayDelay && bits.flag()) {
        bits.read(4);
      }

      if (point == 0) {
        first = new OperatingPoint(level, tier);
      }
    }

    return first;
  }

  // buffer_delay_length_minus_1 + 1, or 0 without decoder_model_info.
  private static int timingAndDecoderModelInfo(PayloadBits bits) {
    bits.read(32);
    bits.read(32);
    if (bits.flag()) {
      bits.skipUvlc();
    }

    if (!bits.flag()) {
      return 0;
    }

    var bufferDelayLength = bits.read(5) + 1;
    bits.read(32);
    bits.read(10);
    return bufferDelayLength;
  }

  // From frame_width_bits_minus_1 to enable_restoration.
  private static void skipFrameSizeAndTools(PayloadBits bits, boolean reduced) {
    var widthBits = bits.read(4) + 1;
    var heightBits = bits.read(4) + 1;
    bits.read(widthBits);
    bits.read(heightBits);
    if (!reduced && bits.flag()) {
      bits.read(7);
    }

    bits.read(3);
    if (!reduced) {
      skipInterToolsAndScreenContent(bits);
    }

    bits.read(3);
  }

  private static void skipInterToolsAndScreenContent(PayloadBits bits) {
    bits.read(4);
    var orderHint = bits.flag();
    if (orderHint) {
      bits.read(2);
    }

    var screenContentTools = bits.flag() ? 2 : bits.read(1);
    if (screenContentTools > 0 && !bits.flag()) {
      bits.read(1);
    }

    if (orderHint) {
      bits.read(3);
    }
  }

  // color_config (AV1 5.5.2) as av1C's third byte holds it, without seq_tier.
  private static int colourConfig(PayloadBits bits, int profile) {
    var highBitDepth = bits.read(1);
    var twelveBit = 0;
    if (profile == PROFILE_PROFESSIONAL && highBitDepth == 1) {
      twelveBit = bits.read(1);
    }

    var monochrome = 0;
    if (profile != PROFILE_HIGH) {
      monochrome = bits.read(1);
    }

    var depthAndMonochrome = (highBitDepth << 6) | (twelveBit << 5) | (monochrome << 4);
    var srgb = readsSrgbColourDescription(bits);
    if (monochrome == 1) {
      bits.read(1);
      return depthAndMonochrome | 0x0C;
    }

    if (srgb) {
      return depthAndMonochrome;
    }

    bits.read(1);
    var subsampling = subsamplingOf(bits, profile, twelveBit == 1);
    var chromaSamplePosition = subsampling == 0x0C ? bits.read(2) : 0;
    return depthAndMonochrome | subsampling | chromaSamplePosition;
  }

  // Reads color_description_present_flag and the description: sRGB has no color_range field and
  // implies 4:4:4 unless the video is monochrome.
  private static boolean readsSrgbColourDescription(PayloadBits bits) {
    if (!bits.flag()) {
      return false;
    }

    var primaries = bits.read(8);
    var transfer = bits.read(8);
    var matrix = bits.read(8);
    return primaries == CP_BT_709 && transfer == TC_SRGB && matrix == MC_IDENTITY;
  }

  // subsampling_x and subsampling_y in av1C's bit positions.
  private static int subsamplingOf(PayloadBits bits, int profile, boolean twelveBit) {
    return switch (profile) {
      case 0 -> 0x0C;
      case PROFILE_HIGH -> 0;
      default -> professionalSubsamplingOf(bits, twelveBit);
    };
  }

  private static int professionalSubsamplingOf(PayloadBits bits, boolean twelveBit) {
    if (!twelveBit) {
      return 0x08;
    }

    if (bits.read(1) == 0) {
      return 0;
    }

    return 0x08 | (bits.read(1) << 2);
  }

  private static byte[] bytesOf(ByteBuffer buffer) {
    var bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  private record OperatingPoint(int level, int tier) {}

  // A payload's bits, most significant first.
  private static final class PayloadBits {

    private static final int UVLC_MAXIMUM_LEADING_ZEROS = 32;

    private final ByteBuffer payload;
    private long position;

    PayloadBits(ByteBuffer payload) {
      this.payload = payload;
    }

    // Up to 32 bits as an unsigned value, which fits an int for every field read above but the
    // timing fields, whose values are skipped.
    int read(int count) {
      var value = 0L;
      for (var bit = 0; bit < count; bit++) {
        value = (value << 1) | next();
      }

      return (int) value;
    }

    boolean flag() {
      return next() == 1;
    }

    // uvlc() (AV1 4.10.3): leading zeros, a one, and as many value bits, none at 32 zeros or more.
    void skipUvlc() {
      var leadingZeros = 0;
      while (next() == 0) {
        leadingZeros++;
      }

      if (leadingZeros < UVLC_MAXIMUM_LEADING_ZEROS) {
        read(leadingZeros);
      }
    }

    private int next() {
      if (position >= 8L * payload.remaining()) {
        throw FragmentedMp4Exception.malformed(
            "the in-band AV1 sequence header ends before its color_config does");
      }

      var octet = payload.get(payload.position() + (int) (position >>> 3));
      var bit = (octet >> (7 - (int) (position & 7))) & 1;
      position++;
      return bit;
    }
  }
}
