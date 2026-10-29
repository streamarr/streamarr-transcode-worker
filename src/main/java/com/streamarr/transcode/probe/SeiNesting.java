package com.streamarr.transcode.probe;

import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The SEI messages that nest other SEI messages, whose nested messages FFmpeg ignores: scalable
 * nesting (payloadType 30), MVC scalable nesting (payloadType 37) and MVCD scalable nesting
 * (payloadType 48) for H.264 (ITU-T H.264, F.13.1.7, G.13.1.2 and H.13.1.2), and scalable nesting
 * (payloadType 133) for HEVC. Each nesting message's header ends at a byte boundary, and its nested
 * messages follow.
 *
 * <p>Other nesting messages can also hold messages that FFmpeg ignores, but the walk does not read
 * inside them: regional nesting (payloadType 157), MCTS extraction information nesting (payloadType
 * 159) and bitstream partition nesting (payloadType 162) for HEVC, and SEI processing order nesting
 * (payloadType 214), which ITU-T H.274 defines, for H.264 and HEVC.
 */
final class SeiNesting {

  private static final int H264_SCALABLE_NESTING = 30;
  private static final int H264_MVC_SCALABLE_NESTING = 37;
  private static final int H264_MVCD_SCALABLE_NESTING = 48;
  private static final int HEVC_SCALABLE_NESTING = 133;
  private static final int HEVC_REGIONAL_NESTING = 157;
  private static final int HEVC_MCTS_EXTRACTION_INFORMATION_NESTING = 159;
  private static final int HEVC_BITSTREAM_PARTITION_NESTING = 162;
  private static final int SEI_PROCESSING_ORDER_NESTING = 214;

  private SeiNesting() {}

  static boolean isNesting(NalSyntax syntax, int payloadType) {
    return switch (syntax) {
      case H264 ->
          payloadType == H264_SCALABLE_NESTING
              || payloadType == H264_MVC_SCALABLE_NESTING
              || payloadType == H264_MVCD_SCALABLE_NESTING;
      case HEVC -> payloadType == HEVC_SCALABLE_NESTING;
    };
  }

  /** Whether the message nests messages that the walk does not read. */
  static boolean isUnreadNesting(NalSyntax syntax, int payloadType) {
    return switch (syntax) {
      case H264 -> payloadType == SEI_PROCESSING_ORDER_NESTING;
      case HEVC ->
          payloadType == HEVC_REGIONAL_NESTING
              || payloadType == HEVC_MCTS_EXTRACTION_INFORMATION_NESTING
              || payloadType == HEVC_BITSTREAM_PARTITION_NESTING
              || payloadType == SEI_PROCESSING_ORDER_NESTING;
    };
  }

  /**
   * The offset of the first nested message in the nesting message's payload; empty when the header
   * runs past the payload.
   */
  static OptionalInt nestedMessagesOffset(NalSyntax syntax, int payloadType, byte[] payload) {
    Consumer<BitReader> skipHeader =
        switch (syntax) {
          case H264 -> h264NestingHeaderSkip(payloadType);
          case HEVC -> SeiNesting::skipHevcScalableNestingHeader;
        };
    var reader = new BitReader(payload);
    try {
      skipHeader.accept(reader);
    } catch (BitReader.TruncatedBitstreamException _) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(reader.alignedBytes());
  }

  private static Consumer<BitReader> h264NestingHeaderSkip(int payloadType) {
    return switch (payloadType) {
      case H264_SCALABLE_NESTING -> SeiNesting::skipScalableNestingHeader;
      case H264_MVC_SCALABLE_NESTING -> SeiNesting::skipMvcScalableNestingHeader;
      default -> SeiNesting::skipMvcdScalableNestingHeader;
    };
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

  private static void skipMvcdScalableNestingHeader(BitReader reader) {
    var isOperationPoint = reader.flag();
    if (isOperationPoint) {
      var isTextureOnly = reader.flag();
      var viewComponents = reader.unsignedExpGolomb() + 1;
      for (var index = 0L; index < viewComponents; index++) {
        // sei_op_view_id, then sei_op_depth_flag and sei_op_texture_flag unless texture only.
        reader.bits(isTextureOnly ? 10 : 12);
      }

      // sei_op_temporal_id.
      reader.bits(3);
      return;
    }

    var isForAllViewComponents = reader.flag();
    if (isForAllViewComponents) {
      return;
    }

    var viewComponents = reader.unsignedExpGolomb() + 1;
    for (var index = 0L; index < viewComponents; index++) {
      // sei_view_id and sei_view_applicability_flag.
      reader.bits(11);
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
