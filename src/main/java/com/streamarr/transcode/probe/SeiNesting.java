package com.streamarr.transcode.probe;

import java.util.OptionalInt;

/**
 * The SEI messages that nest other SEI messages, whose nested messages FFmpeg ignores: scalable
 * nesting (payloadType 30) and MVC scalable nesting (payloadType 37) for H.264 (ITU-T H.264,
 * F.13.1.7 and G.13.1.2), and scalable nesting (payloadType 133) for HEVC. Each nesting message's
 * header ends at a byte boundary, and its nested messages follow.
 */
final class SeiNesting {

  private static final int H264_SCALABLE_NESTING = 30;
  private static final int H264_MVC_SCALABLE_NESTING = 37;
  private static final int HEVC_SCALABLE_NESTING = 133;

  private SeiNesting() {}

  static boolean isNesting(NalSyntax syntax, int payloadType) {
    return switch (syntax) {
      case H264 -> payloadType == H264_SCALABLE_NESTING || payloadType == H264_MVC_SCALABLE_NESTING;
      case HEVC -> payloadType == HEVC_SCALABLE_NESTING;
    };
  }

  /**
   * The offset of the first nested message in the nesting message's payload; empty when the header
   * runs past the payload.
   */
  static OptionalInt nestedMessagesOffset(NalSyntax syntax, int payloadType, byte[] payload) {
    var reader = new BitReader(payload);
    try {
      switch (syntax) {
        case H264 -> {
          if (payloadType == H264_SCALABLE_NESTING) {
            skipScalableNestingHeader(reader);
          } else {
            skipMvcScalableNestingHeader(reader);
          }
        }
        case HEVC -> skipHevcScalableNestingHeader(reader);
      }
    } catch (BitReader.TruncatedBitstreamException _) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(reader.alignedBytes());
  }

  private static void skipScalableNestingHeader(BitReader reader) {
    var isForAllLayerRepresentations = reader.flag();
    if (isForAllLayerRepresentations) {
      return;
    }

    var layerRepresentations = reader.unsignedExpGolomb() + 1;
    for (var index = 0L; index < layerRepresentations; index++) {
      // sei_dependency_id and sei_quality_id.
      reader.bits(7);
    }

    // sei_temporal_id.
    reader.bits(3);
  }

  private static void skipMvcScalableNestingHeader(BitReader reader) {
    var isOperationPoint = reader.flag();
    if (isOperationPoint) {
      skipViewIds(reader);
      // sei_op_temporal_id.
      reader.bits(3);
      return;
    }

    var isForAllViewComponents = reader.flag();
    if (!isForAllViewComponents) {
      skipViewIds(reader);
    }
  }

  private static void skipViewIds(BitReader reader) {
    var viewComponents = reader.unsignedExpGolomb() + 1;
    for (var index = 0L; index < viewComponents; index++) {
      reader.bits(10);
    }
  }

  private static void skipHevcScalableNestingHeader(BitReader reader) {
    // bitstream_subset_flag.
    reader.flag();
    var isOperationPoint = reader.flag();
    if (isOperationPoint) {
      var firstOperationPoint = reader.flag() ? 1L : 0L;
      var lastOperationPoint = reader.unsignedExpGolomb();
      for (var index = firstOperationPoint; index <= lastOperationPoint; index++) {
        // nesting_max_temporal_id_plus1, then nesting_op_idx.
        reader.bits(3);
        reader.unsignedExpGolomb();
      }

      return;
    }

    var isForAllLayers = reader.flag();
    if (isForAllLayers) {
      return;
    }

    // nesting_no_op_max_temporal_id_plus1.
    reader.bits(3);
    var layers = reader.unsignedExpGolomb() + 1;
    for (var index = 0L; index < layers; index++) {
      reader.bits(6);
    }
  }
}
