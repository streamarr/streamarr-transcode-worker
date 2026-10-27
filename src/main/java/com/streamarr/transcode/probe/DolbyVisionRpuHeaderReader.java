package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import java.util.Optional;

/**
 * Reads the profile-guessing fields of a Dolby Vision RPU header (rpu_data_header), as FFmpeg 8.1's
 * RPU parser does, from the RPU's bytes after its NAL unit header and without emulation prevention,
 * or from the bytes that an AV1 stream's EMDF container wraps.
 */
final class DolbyVisionRpuHeaderReader {

  private static final int RPU_NAL_PREFIX = 25;
  private static final int RPU_TYPE = 2;
  // rpu_format's bits that, when set, leave the bit depths and layer flags out of the header.
  private static final int FORMAT_WITHOUT_BIT_DEPTHS = 0x700;
  private static final int FIXED_POINT_COEFFICIENTS = 0;
  // FFmpeg rejects an RPU whose VDR bit depth lies outside 8 to 16.
  private static final int DEEPEST_VDR_BIT_DEPTH = 16;

  private DolbyVisionRpuHeaderReader() {}

  /**
   * The header; empty when the bytes are no type 2 RPU, or when its header carries no sequence
   * information or no bit depths.
   */
  static Optional<DolbyVisionRpuHeader> read(byte[] rpu) {
    try {
      var reader = new BitReader(rpu);
      return reader.bits(8) == RPU_NAL_PREFIX ? header(reader) : Optional.empty();
    } catch (TruncatedBitstreamException _) {
      return Optional.empty();
    }
  }

  /**
   * The header of an RPU without its rpu_nal_prefix, as an AV1 stream's EMDF container holds it;
   * empty as for {@link #read}.
   */
  static Optional<DolbyVisionRpuHeader> readWithoutPrefix(byte[] rpu) {
    try {
      return header(new BitReader(rpu));
    } catch (TruncatedBitstreamException _) {
      return Optional.empty();
    }
  }

  private static Optional<DolbyVisionRpuHeader> header(BitReader reader) {
    if (reader.bits(6) != RPU_TYPE) {
      return Optional.empty();
    }

    var format = (int) reader.bits(11);
    var profile = (int) reader.bits(4);
    reader.bits(4);
    if (!reader.flag()) {
      return Optional.empty();
    }

    reader.bits(1);
    if (reader.bits(2) == FIXED_POINT_COEFFICIENTS) {
      reader.unsignedExpGolomb();
    }

    reader.bits(2);
    var fullRange = reader.flag();
    if ((format & FORMAT_WITHOUT_BIT_DEPTHS) != 0) {
      return Optional.empty();
    }

    reader.unsignedExpGolomb();
    reader.unsignedExpGolomb();
    var vdrBitDepth = reader.unsignedExpGolomb() + 8;
    if (vdrBitDepth > DEEPEST_VDR_BIT_DEPTH) {
      return Optional.empty();
    }

    reader.bits(4);
    return Optional.of(
        DolbyVisionRpuHeader.newBuilder()
            .setVdrRpuProfile(profile)
            .setBaseLayerFullRange(fullRange)
            .setEnhancementLayerSpatialResamplingFilter(reader.flag())
            .setDisableResidual(reader.flag())
            .setVdrBitDepth((int) vdrBitDepth)
            .build());
  }
}
