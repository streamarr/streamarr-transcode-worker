package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import com.streamarr.transcode.engine.PictureFormat;
import com.streamarr.transcode.engine.VideoCodecProfile;
import com.streamarr.transcode.engine.VideoDynamicRange;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;

// Translates an encode's output bit depth and chroma subsampling into the engine's picture format.
// An older server sends neither on an SDR encode, which then writes 8 bits and 4:2:0; a PQ or HLG
// encode always sends both, and writes 10 bits.
final class EncodedPictureFormats {

  private static final int EIGHT_BITS = 8;
  private static final int TEN_BITS = 10;

  private EncodedPictureFormats() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static PictureFormat of(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video, VideoDynamicRange output) {
    var hdr = output.compatibleBase() != CompatibleBase.SDR;
    if (hdr && (!video.hasOutputBitDepth() || !setsChroma(video))) {
      throw new InvalidJobSpecificationException(
          "A " + output.compatibleBase() + " encode sets its bit depth and chroma subsampling");
    }

    var bitDepth = video.hasOutputBitDepth() ? video.getOutputBitDepth() : EIGHT_BITS;
    if (bitDepth != EIGHT_BITS && bitDepth != TEN_BITS) {
      throw new InvalidJobSpecificationException(
          "An encode writes 8 or 10 bits, not " + Integer.toUnsignedString(bitDepth));
    }

    if (hdr && bitDepth != TEN_BITS) {
      throw new InvalidJobSpecificationException(
          "A " + output.compatibleBase() + " encode writes 10 bits, not " + bitDepth);
    }

    var chroma = video.getOutputChromaSubsampling();
    if (chroma != ChromaSubsampling.CHROMA_SUBSAMPLING_420
        && chroma != ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED) {
      throw new InvalidJobSpecificationException("An encode writes 4:2:0, not " + chroma);
    }

    return new PictureFormat(bitDepth, com.streamarr.transcode.engine.ChromaSubsampling.CHROMA_420);
  }

  /** Refuses a codec profile that cannot hold the picture format. */
  static void requireProfileThatHolds(VideoCodecProfile profile, PictureFormat format) {
    var holds =
        switch (profile) {
          case H264_HIGH, HEVC_MAIN -> format.bitDepth() == EIGHT_BITS;
          case HEVC_MAIN_10 -> format.bitDepth() == TEN_BITS;
          case AV1_MAIN -> true;
        };
    if (!holds) {
      throw new InvalidJobSpecificationException(
          "Codec profile " + profile + " does not hold " + format.bitDepth() + "-bit video");
    }
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static boolean setsChroma(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return video.getOutputChromaSubsampling() != ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED;
  }
}
