package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * An AV1 sequence header OBU (AV1 5.5) written field by field, 1920x1080, without film grain.
 *
 * @param operatingPoints each operating point's seq_level_idx and seq_tier; the tier is written
 *     only above level 7, and a reduced still picture header writes only the first level
 * @param timingInfo whether timing_info is present, with an equal picture interval
 * @param decoderModel whether decoder_model_info follows the timing info, with a 5-bit buffer delay
 *     and every operating point's decoder model present
 * @param initialDisplayDelay whether every operating point carries an initial display delay
 * @param colourDescription the colour primaries, transfer characteristics and matrix coefficients;
 *     empty when color_description_present_flag is 0
 * @param subsamplingX written only for a 12-bit profile 2 header
 * @param subsamplingY written only for a 12-bit profile 2 header whose subsampling_x is 1
 * @param sized whether the OBU has its size field
 * @param extended whether the OBU has its extension byte
 */
@Builder(toBuilder = true)
record SyntheticSequenceHeader(
    int profile,
    boolean reducedStillPictureHeader,
    @NonNull List<OperatingPoint> operatingPoints,
    boolean timingInfo,
    boolean decoderModel,
    boolean initialDisplayDelay,
    boolean frameIdNumbers,
    boolean orderHint,
    @NonNull ScreenContentTools screenContentTools,
    boolean highBitDepth,
    boolean twelveBit,
    boolean monochrome,
    @NonNull Optional<ColourDescription> colourDescription,
    int subsamplingX,
    int subsamplingY,
    int chromaSamplePosition,
    boolean sized,
    boolean extended) {

  private static final int OBU_SEQUENCE_HEADER = 1;
  private static final int FRAME_DIMENSION_BITS = 11;

  /** seq_level_idx and seq_tier of one operating point. */
  record OperatingPoint(int level, int tier) {}

  /** color_primaries, transfer_characteristics and matrix_coefficients (ITU-T H.273). */
  record ColourDescription(int primaries, int transfer, int matrix) {}

  /** How the header decides seq_force_screen_content_tools and seq_force_integer_mv. */
  enum ScreenContentTools {
    /** seq_choose_screen_content_tools and seq_choose_integer_mv are 1. */
    CHOSEN,
    /** Both are 0, and the forced values are 1. */
    FORCED,
    /** seq_choose_screen_content_tools and seq_force_screen_content_tools are 0. */
    OFF
  }

  /** The OBU: its header, extension byte and leb128 size as chosen, then its payload. */
  byte[] obu() {
    var payload = payload();
    var obu = new ByteArrayOutputStream();
    obu.write((OBU_SEQUENCE_HEADER << 3) | (extended ? 0x04 : 0) | (sized ? 0x02 : 0));
    if (extended) {
      obu.write(0);
    }

    for (var rest = payload.length; sized; rest >>>= 7) {
      if (rest < 0x80) {
        obu.write(rest);
        break;
      }

      obu.write((rest & 0x7F) | 0x80);
    }

    obu.writeBytes(payload);
    return obu.toByteArray();
  }

  private byte[] payload() {
    var bits = new Bits();
    bits.put(3, profile).put(1, 0).put(1, reducedStillPictureHeader ? 1 : 0);
    if (reducedStillPictureHeader) {
      bits.put(5, operatingPoints.getFirst().level());
    } else {
      writeOperatingPoints(bits);
    }

    bits.put(4, FRAME_DIMENSION_BITS - 1).put(4, FRAME_DIMENSION_BITS - 1);
    bits.put(FRAME_DIMENSION_BITS, 1919).put(FRAME_DIMENSION_BITS, 1079);
    if (!reducedStillPictureHeader) {
      bits.put(1, frameIdNumbers ? 1 : 0);
    }

    if (frameIdNumbers) {
      bits.put(4, 12).put(3, 2);
    }

    bits.put(3, 0b001);
    if (!reducedStillPictureHeader) {
      writeInterToolsAndScreenContent(bits);
    }

    bits.put(3, 0b011);
    writeColourConfig(bits);
    // film_grain_params_present, then trailing_bits.
    return bits.put(1, 0).put(1, 1).bytes();
  }

  private void writeOperatingPoints(Bits bits) {
    bits.put(1, timingInfo ? 1 : 0);
    if (timingInfo) {
      bits.put(32, 1001).put(32, 24000).put(1, 1).put(1, 1);
      bits.put(1, decoderModel ? 1 : 0);
    }

    if (decoderModel) {
      bits.put(5, 4).put(32, 1001).put(5, 4).put(5, 4);
    }

    bits.put(1, initialDisplayDelay ? 1 : 0).put(5, operatingPoints.size() - 1);
    for (var point : operatingPoints) {
      bits.put(12, 0).put(5, point.level());
      if (point.level() > 7) {
        bits.put(1, point.tier());
      }

      if (decoderModel) {
        bits.put(1, 1).put(5, 3).put(5, 2).put(1, 0);
      }

      if (initialDisplayDelay) {
        bits.put(1, 1).put(4, 9);
      }
    }
  }

  private void writeInterToolsAndScreenContent(Bits bits) {
    bits.put(4, 0b1010).put(1, orderHint ? 1 : 0);
    if (orderHint) {
      bits.put(2, 0b00);
    }

    switch (screenContentTools) {
      case CHOSEN -> bits.put(1, 1).put(1, 1);
      case FORCED -> bits.put(1, 0).put(1, 1).put(1, 0).put(1, 1);
      case OFF -> bits.put(1, 0).put(1, 0);
    }

    if (orderHint) {
      bits.put(3, 6);
    }
  }

  private void writeColourConfig(Bits bits) {
    bits.put(1, highBitDepth ? 1 : 0);
    if (profile == 2 && highBitDepth) {
      bits.put(1, twelveBit ? 1 : 0);
    }

    if (profile != 1) {
      bits.put(1, monochrome ? 1 : 0);
    }

    bits.put(1, colourDescription.isPresent() ? 1 : 0);
    colourDescription.ifPresent(
        colour ->
            bits.put(8, colour.primaries()).put(8, colour.transfer()).put(8, colour.matrix()));
    var srgb = colourDescription.equals(Optional.of(new ColourDescription(1, 13, 0)));
    if (monochrome) {
      bits.put(1, 0);
      return;
    }

    if (!srgb) {
      writeRangeAndSubsampling(bits);
    }

    bits.put(1, 0);
  }

  private void writeRangeAndSubsampling(Bits bits) {
    bits.put(1, 0);
    if (profile == 2 && twelveBit) {
      writeTwelveBitSubsampling(bits);
      return;
    }

    if (profile == 0) {
      bits.put(2, chromaSamplePosition);
    }
  }

  private void writeTwelveBitSubsampling(Bits bits) {
    bits.put(1, subsamplingX);
    if (subsamplingX == 0) {
      return;
    }

    bits.put(1, subsamplingY);
    if (subsamplingY == 1) {
      bits.put(2, chromaSamplePosition);
    }
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  static class SyntheticSequenceHeaderBuilder {
    private List<OperatingPoint> operatingPoints = List.of(new OperatingPoint(8, 0));
    private boolean orderHint = true;
    private ScreenContentTools screenContentTools = ScreenContentTools.CHOSEN;
    private Optional<ColourDescription> colourDescription = Optional.empty();
    private boolean sized = true;
  }

  // Bits written most significant first.
  private static final class Bits {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int current;
    private int count;

    Bits put(int width, long value) {
      for (var bit = width - 1; bit >= 0; bit--) {
        current = (current << 1) | (int) ((value >> bit) & 1);
        count++;
        if (count == 8) {
          bytes.write(current);
          current = 0;
          count = 0;
        }
      }

      return this;
    }

    byte[] bytes() {
      if (count > 0) {
        bytes.write(current << (8 - count));
      }

      return bytes.toByteArray();
    }
  }
}
