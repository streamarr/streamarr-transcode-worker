package com.streamarr.transcode.probe;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Lo/Ro centre and surround levels that an AC-3 or E-AC-3 frame signals. FFmpeg's decoder
 * exports the lorocmixlev and lorosurmixlev levels when the stream carries them, and otherwise
 * cmixlev and surmixlev; its ashowinfo filter prints them beside the Lt/Rt levels.
 */
public record DownmixLevels(double center, double surround) {

  // ashowinfo's line: "side data - downmix: preferred downmix type - unknown Mix levels: center
  // 0.594604 (1.414214 ltrt) - surround 0.500000 (1.414214 ltrt) - lfe 0.000000".
  private static final Pattern LINE =
      Pattern.compile(
          "side data - downmix: .*Mix levels: center ([0-9.]+) \\([0-9.]+ ltrt\\)"
              + " - surround ([0-9.]+) \\([0-9.]+ ltrt\\)");

  static Optional<DownmixLevels> parse(String line) {
    var match = LINE.matcher(line);
    if (!match.find()) {
      return Optional.empty();
    }

    return Optional.of(
        new DownmixLevels(Double.parseDouble(match.group(1)), Double.parseDouble(match.group(2))));
  }
}
