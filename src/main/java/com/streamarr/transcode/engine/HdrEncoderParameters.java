package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.PlannedCopyOutput.Chromaticity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parameters with which libx265 and SVT-AV1 write a PQ or HLG encode's colour description and a
 * PQ encode's HDR10 static metadata into the bitstream, from the job's values, never from the
 * source's frames. Each encoder takes its own units: libx265 the SEI messages' units, and SVT-AV1
 * chromaticities from 0 to 1 and luminances in cd/m².
 */
final class HdrEncoderParameters {

  private static final BigDecimal CHROMATICITY_UNITS = BigDecimal.valueOf(50_000);
  private static final BigDecimal LUMINANCE_UNITS = BigDecimal.valueOf(10_000);

  private HdrEncoderParameters() {}

  /** libx265's parameters; empty for an SDR encode. */
  static List<String> x265(VideoDecision.Encode encode) {
    var transfer = HdrTransfer.of(encode.outputDynamicRange());
    if (transfer.isEmpty()) {
      return List.of();
    }

    var parameters = new ArrayList<String>();
    encode.staticHdrMetadata().ifPresent(_ -> parameters.add("hdr10=1"));
    parameters.addAll(
        List.of(
            "colorprim=bt2020",
            "transfer=" + transfer.get().ffmpegName(),
            "colormatrix=bt2020nc",
            "range=limited"));
    encode
        .staticHdrMetadata()
        .ifPresent(
            metadata -> {
              var display = metadata.masteringDisplay();
              parameters.add(
                  String.format(
                      Locale.ROOT,
                      "master-display=G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d)",
                      display.green().x(),
                      display.green().y(),
                      display.blue().x(),
                      display.blue().y(),
                      display.red().x(),
                      display.red().y(),
                      display.whitePoint().x(),
                      display.whitePoint().y(),
                      display.maxLuminance(),
                      display.minLuminance()));
              parameters.add(maxContentLightLevel("max-cll=", metadata));
            });
    return List.copyOf(parameters);
  }

  /** SVT-AV1's parameters; empty for an SDR encode. */
  static List<String> svtAv1(VideoDecision.Encode encode) {
    var transfer = HdrTransfer.of(encode.outputDynamicRange());
    if (transfer.isEmpty()) {
      return List.of();
    }

    var parameters =
        new ArrayList<>(
            List.of(
                "color-primaries=bt2020",
                "transfer-characteristics=" + transfer.get().svtAv1Name(),
                "matrix-coefficients=bt2020-ncl",
                "color-range=studio"));
    encode
        .staticHdrMetadata()
        .ifPresent(
            metadata -> {
              var display = metadata.masteringDisplay();
              parameters.add(
                  "mastering-display=G"
                      + chromaticity(display.green())
                      + "B"
                      + chromaticity(display.blue())
                      + "R"
                      + chromaticity(display.red())
                      + "WP"
                      + chromaticity(display.whitePoint())
                      + "L("
                      + luminance(display.maxLuminance())
                      + ","
                      + luminance(display.minLuminance())
                      + ")");
              parameters.add(maxContentLightLevel("content-light=", metadata));
            });
    return List.copyOf(parameters);
  }

  private static String maxContentLightLevel(String key, StaticHdrMetadata metadata) {
    var level = metadata.contentLightLevel();
    return key + level.maxContentLightLevel() + "," + level.maxFrameAverageLightLevel();
  }

  private static String chromaticity(Chromaticity value) {
    return "("
        + fraction(value.x(), CHROMATICITY_UNITS)
        + ","
        + fraction(value.y(), CHROMATICITY_UNITS)
        + ")";
  }

  private static String luminance(long units) {
    return fraction(units, LUMINANCE_UNITS);
  }

  // Both unit sizes divide a power of ten, so every value has an exact decimal form.
  private static String fraction(long units, BigDecimal unitsPerOne) {
    return BigDecimal.valueOf(units).divide(unitsPerOne).stripTrailingZeros().toPlainString();
  }
}
