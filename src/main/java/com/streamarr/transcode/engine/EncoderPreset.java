package com.streamarr.transcode.engine;

import java.util.Locale;

/**
 * One name on the preset scale (ADR 0044). libx264 and libx265 take the name itself, libsvtav1
 * takes the number the scale maps the name to, and the keyframe-verified NVENC encoders take the
 * scale's NVENC preset.
 */
public enum EncoderPreset {
  SUPERFAST(12, "p2"),
  VERYFAST(11, "p3"),
  FASTER(10, "p4"),
  FAST(9, "p5"),
  MEDIUM(8, "p6"),
  SLOW(7, "p7"),
  SLOWER(6, "p7"),
  VERYSLOW(5, "p7");

  private final int svtAv1Preset;
  private final String nvencPreset;

  EncoderPreset(int svtAv1Preset, String nvencPreset) {
    this.svtAv1Preset = svtAv1Preset;
    this.nvencPreset = nvencPreset;
  }

  public String scaleName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public int svtAv1Preset() {
    return svtAv1Preset;
  }

  public String nvencPreset() {
    return nvencPreset;
  }
}
