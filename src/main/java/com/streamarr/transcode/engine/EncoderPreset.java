package com.streamarr.transcode.engine;

import java.util.Locale;

/**
 * One name on the preset scale that serves every software encoder (ADR 0044). libx264 and libx265
 * take the name itself, and libsvtav1 takes the number the scale maps the name to.
 */
public enum EncoderPreset {
  ULTRAFAST(13),
  SUPERFAST(12),
  VERYFAST(11),
  FASTER(10),
  FAST(9),
  MEDIUM(8),
  SLOW(7),
  SLOWER(6),
  VERYSLOW(5);

  private final int svtAv1Preset;

  EncoderPreset(int svtAv1Preset) {
    this.svtAv1Preset = svtAv1Preset;
  }

  public String scaleName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public int svtAv1Preset() {
    return svtAv1Preset;
  }
}
