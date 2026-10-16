package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bit depth and chroma subsampling of an FFmpeg pixel format, read from its name. The bit depth
 * is the depth of the format's first component: luma for a YCbCr or gray format, red for an RGB
 * format. FFmpeg names each format by its layout, its depth and its byte order, such as {@code
 * yuv420p10le}.
 */
record PixelFormat(int bitDepth, ChromaSubsampling chromaSubsampling) {

  private static final ChromaSubsampling NONE = ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED;
  private static final ChromaSubsampling MONOCHROME =
      ChromaSubsampling.CHROMA_SUBSAMPLING_MONOCHROME;
  private static final ChromaSubsampling YUV420 = ChromaSubsampling.CHROMA_SUBSAMPLING_420;
  private static final ChromaSubsampling YUV422 = ChromaSubsampling.CHROMA_SUBSAMPLING_422;
  private static final ChromaSubsampling YUV444 = ChromaSubsampling.CHROMA_SUBSAMPLING_444;

  private static final String BYTE_ORDER = "(?:le|be)?";

  /**
   * Planar YCbCr, with or without alpha or full range, such as yuv420p, yuvj444p or yuva422p10le.
   */
  private static final Pattern PLANAR_YUV =
      Pattern.compile("yuva?j?(4\\d\\d)p(\\d+)?(?:msb)?" + BYTE_ORDER);

  /** Semi-planar YCbCr with the depth in the name, such as p010le, p216le or p412be. */
  private static final Pattern SEMI_PLANAR_YUV = Pattern.compile("p([024])(\\d\\d)" + BYTE_ORDER);

  /** Packed 4:2:2 and 4:4:4 YCbCr with the depth in the name, such as y210le or xv36be. */
  private static final Pattern PACKED_YUV = Pattern.compile("(y2|xv)(\\d\\d)" + BYTE_ORDER);

  /** Gray, with or without alpha, as integers or floats, such as gray, gray10le or yaf32le. */
  private static final Pattern GRAY = Pattern.compile("(?:gray|ya)f?(\\d+)?" + BYTE_ORDER);

  /** Planar RGB, with or without alpha, as integers or floats, such as gbrp, gbrap12le. */
  private static final Pattern PLANAR_RGB = Pattern.compile("gbra?pf?(\\d+)?(?:msb)?" + BYTE_ORDER);

  private static final Map<String, PixelFormat> FIXED_LAYOUTS =
      Map.ofEntries(
          Map.entry("nv12", new PixelFormat(8, YUV420)),
          Map.entry("nv21", new PixelFormat(8, YUV420)),
          Map.entry("nv15", new PixelFormat(10, YUV420)),
          Map.entry("nv16", new PixelFormat(8, YUV422)),
          Map.entry("nv20", new PixelFormat(10, YUV422)),
          Map.entry("nv24", new PixelFormat(8, YUV444)),
          Map.entry("nv42", new PixelFormat(8, YUV444)),
          Map.entry("yuyv422", new PixelFormat(8, YUV422)),
          Map.entry("uyvy422", new PixelFormat(8, YUV422)),
          Map.entry("yvyu422", new PixelFormat(8, YUV422)),
          Map.entry("uyyvyy411", new PixelFormat(8, NONE)),
          Map.entry("vuya", new PixelFormat(8, YUV444)),
          Map.entry("vuyx", new PixelFormat(8, YUV444)),
          Map.entry("ayuv", new PixelFormat(8, YUV444)),
          Map.entry("uyva", new PixelFormat(8, YUV444)),
          Map.entry("vyu444", new PixelFormat(8, YUV444)),
          Map.entry("ayuv64", new PixelFormat(16, YUV444)),
          Map.entry("v30x", new PixelFormat(10, YUV444)),
          Map.entry("monow", new PixelFormat(1, MONOCHROME)),
          Map.entry("monob", new PixelFormat(1, MONOCHROME)),
          Map.entry("pal8", new PixelFormat(8, NONE)),
          Map.entry("xyz12", new PixelFormat(12, NONE)),
          Map.entry("rgb24", new PixelFormat(8, NONE)),
          Map.entry("bgr24", new PixelFormat(8, NONE)),
          Map.entry("argb", new PixelFormat(8, NONE)),
          Map.entry("rgba", new PixelFormat(8, NONE)),
          Map.entry("abgr", new PixelFormat(8, NONE)),
          Map.entry("bgra", new PixelFormat(8, NONE)),
          Map.entry("0rgb", new PixelFormat(8, NONE)),
          Map.entry("rgb0", new PixelFormat(8, NONE)),
          Map.entry("0bgr", new PixelFormat(8, NONE)),
          Map.entry("bgr0", new PixelFormat(8, NONE)),
          Map.entry("rgb48", new PixelFormat(16, NONE)),
          Map.entry("bgr48", new PixelFormat(16, NONE)),
          Map.entry("rgba64", new PixelFormat(16, NONE)),
          Map.entry("bgra64", new PixelFormat(16, NONE)),
          Map.entry("rgbf16", new PixelFormat(16, NONE)),
          Map.entry("rgbaf16", new PixelFormat(16, NONE)),
          Map.entry("rgbf32", new PixelFormat(32, NONE)),
          Map.entry("rgbaf32", new PixelFormat(32, NONE)),
          Map.entry("rgb96", new PixelFormat(32, NONE)),
          Map.entry("rgba128", new PixelFormat(32, NONE)),
          Map.entry("x2rgb10", new PixelFormat(10, NONE)),
          Map.entry("x2bgr10", new PixelFormat(10, NONE)),
          Map.entry("rgb565", new PixelFormat(5, NONE)),
          Map.entry("bgr565", new PixelFormat(5, NONE)),
          Map.entry("rgb555", new PixelFormat(5, NONE)),
          Map.entry("bgr555", new PixelFormat(5, NONE)),
          Map.entry("rgb444", new PixelFormat(4, NONE)),
          Map.entry("bgr444", new PixelFormat(4, NONE)),
          Map.entry("rgb8", new PixelFormat(3, NONE)),
          Map.entry("bgr8", new PixelFormat(3, NONE)),
          Map.entry("rgb4", new PixelFormat(1, NONE)),
          Map.entry("bgr4", new PixelFormat(1, NONE)),
          Map.entry("rgb4_byte", new PixelFormat(1, NONE)),
          Map.entry("bgr4_byte", new PixelFormat(1, NONE)));

  /** The pixel format that FFmpeg names, or empty when the name is not one this reader knows. */
  static Optional<PixelFormat> named(String name) {
    var fixed = FIXED_LAYOUTS.get(name.replaceFirst("(le|be)$", ""));
    if (fixed != null) {
      return Optional.of(fixed);
    }

    return matching(PLANAR_YUV, name)
        .map(match -> new PixelFormat(depth(match.group(2)), planarSubsampling(match.group(1))))
        .or(() -> matching(SEMI_PLANAR_YUV, name).map(PixelFormat::semiPlanar))
        .or(() -> matching(PACKED_YUV, name).map(PixelFormat::packed))
        .or(() -> matching(GRAY, name).map(m -> new PixelFormat(depth(m.group(1)), MONOCHROME)))
        .or(() -> matching(PLANAR_RGB, name).map(m -> new PixelFormat(depth(m.group(1)), NONE)));
  }

  private static Optional<Matcher> matching(Pattern pattern, String name) {
    var matcher = pattern.matcher(name);
    return matcher.matches() ? Optional.of(matcher) : Optional.empty();
  }

  private static int depth(String digits) {
    return digits == null ? 8 : Integer.parseInt(digits);
  }

  private static ChromaSubsampling planarSubsampling(String layout) {
    return switch (layout) {
      case "420" -> YUV420;
      case "422" -> YUV422;
      case "444" -> YUV444;
      default -> NONE;
    };
  }

  private static PixelFormat semiPlanar(Matcher match) {
    var subsampling =
        switch (match.group(1)) {
          case "0" -> YUV420;
          case "2" -> YUV422;
          default -> YUV444;
        };
    return new PixelFormat(Integer.parseInt(match.group(2)), subsampling);
  }

  /** y210 to y216 are 4:2:2; xv30, xv36 and xv48 are 4:4:4 with 10, 12 or 16 bits. */
  private static PixelFormat packed(Matcher match) {
    var digits = Integer.parseInt(match.group(2));
    if ("y2".equals(match.group(1))) {
      return new PixelFormat(digits, YUV422);
    }

    return new PixelFormat(digits / 3, YUV444);
  }
}
