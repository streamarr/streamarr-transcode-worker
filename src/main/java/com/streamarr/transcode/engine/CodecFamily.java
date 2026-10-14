package com.streamarr.transcode.engine;

import java.util.Arrays;
import java.util.Optional;

/** A video codec family that the worker can encode, with the software encoder it falls back to. */
public enum CodecFamily {
  H264("h264", "libx264"),
  HEVC("hevc", "libx265"),
  AV1("av1", "libsvtav1");

  private final String familyName;
  private final String softwareEncoder;

  CodecFamily(String familyName, String softwareEncoder) {
    this.familyName = familyName;
    this.softwareEncoder = softwareEncoder;
  }

  static Optional<CodecFamily> named(String familyName) {
    return Arrays.stream(values())
        .filter(family -> family.familyName.equals(familyName))
        .findFirst();
  }

  String familyName() {
    return familyName;
  }

  String softwareEncoder() {
    return softwareEncoder;
  }

  // FFmpeg names each hardware encoder after its codec family, such as h264_nvenc.
  boolean isHardwareEncoderOfThisFamily(String encoder) {
    return encoder.startsWith(familyName + "_");
  }
}
