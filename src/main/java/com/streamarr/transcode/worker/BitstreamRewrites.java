package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.BitstreamRewrite;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Translates the bitstream rewrites that a copy lists (ADR 0036) into the engine's, and refuses one
// that the engine does not apply.
final class BitstreamRewrites {

  private BitstreamRewrites() {}

  /**
   * The rewrites, each listed once.
   *
   * @throws InvalidJobSpecificationException when the copy lists a rewrite twice, or one that the
   *     engine does not apply
   */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Set<BitstreamRewrite> of(
      List<build.buf.gen.streamarr.transcode.v1.BitstreamRewrite> listed) {
    var rewrites = EnumSet.noneOf(BitstreamRewrite.class);
    for (var rewrite : listed) {
      if (!rewrites.add(of(rewrite))) {
        throw new InvalidJobSpecificationException("The copy lists " + rewrite + " twice");
      }
    }

    return rewrites;
  }

  /** Every rewrite that the engine applies, which the worker declares when it registers. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static List<build.buf.gen.streamarr.transcode.v1.BitstreamRewrite> declared() {
    return Arrays.stream(BitstreamRewrite.values()).map(BitstreamRewrites::toProto).toList();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static build.buf.gen.streamarr.transcode.v1.BitstreamRewrite toProto(
      BitstreamRewrite rewrite) {
    return switch (rewrite) {
      case STRIP_DOLBY_VISION ->
          build.buf.gen.streamarr.transcode.v1.BitstreamRewrite
              .BITSTREAM_REWRITE_STRIP_DOLBY_VISION;
      case STRIP_HDR10_PLUS ->
          build.buf.gen.streamarr.transcode.v1.BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS;
      case NORMALIZE_DOLBY_VISION_RECORD ->
          build.buf.gen.streamarr.transcode.v1.BitstreamRewrite
              .BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static BitstreamRewrite of(build.buf.gen.streamarr.transcode.v1.BitstreamRewrite value) {
    return switch (value) {
      case BITSTREAM_REWRITE_STRIP_DOLBY_VISION -> BitstreamRewrite.STRIP_DOLBY_VISION;
      case BITSTREAM_REWRITE_STRIP_HDR10_PLUS -> BitstreamRewrite.STRIP_HDR10_PLUS;
      case BITSTREAM_REWRITE_NORMALIZE_DOLBY_VISION_RECORD ->
          BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD;
      case BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
          BITSTREAM_REWRITE_UNSPECIFIED,
          UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("The worker does not apply " + value);
    };
  }
}
