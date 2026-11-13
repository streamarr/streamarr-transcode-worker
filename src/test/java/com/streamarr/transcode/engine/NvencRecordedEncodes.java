package com.streamarr.transcode.engine;

import java.util.Optional;

/**
 * The encode decision of a recording that src/test/fmp4-recorder/record-nvenc.sh plans, from its
 * encoder and the range it names: {@code sdr} for 8-bit BT.709 from the H.264 source, and {@code
 * sdr10}, {@code pq} or {@code hlg} for Main 10 from the HEVC source of that range.
 */
final class NvencRecordedEncodes {

  // The constructed static HDR metadata of the recorder's PQ source: a P3 D65 display of 1000
  // cd/m² and 0.005 cd/m², MaxCLL 1000 and MaxFALL 400.
  private static final StaticHdrMetadata PQ_SOURCE_METADATA =
      new StaticHdrMetadata(
          PlannedCopyOutput.MasteringDisplay.builder()
              .red(new PlannedCopyOutput.Chromaticity(34000, 16000))
              .green(new PlannedCopyOutput.Chromaticity(13250, 34500))
              .blue(new PlannedCopyOutput.Chromaticity(7500, 3000))
              .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
              .maxLuminance(10_000_000)
              .minLuminance(50)
              .build(),
          new PlannedCopyOutput.ContentLightLevel(1000, 400));

  private NvencRecordedEncodes() {}

  /** The decision without its tier, level and codec string, which each recording's plan names. */
  static VideoDecision.Encode.EncodeBuilder encodeOf(String encoder, String range) {
    if (range.equals("sdr")) {
      return VideoDecision.Encode.builder()
          .sourceCodecFamily(Optional.of(CodecFamily.H264))
          .sourceDynamicRange(VideoDynamicRange.sdr())
          .outputDynamicRange(VideoDynamicRange.sdr())
          .codecProfile(
              encoder.startsWith("hevc")
                  ? VideoCodecProfile.HEVC_MAIN
                  : VideoCodecProfile.H264_HIGH);
    }

    var dynamicRange = dynamicRangeOf(range);
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourceDynamicRange(dynamicRange)
        .outputDynamicRange(dynamicRange)
        .codecProfile(VideoCodecProfile.HEVC_MAIN_10)
        .outputPictureFormat(new PictureFormat(10, ChromaSubsampling.CHROMA_420))
        .staticHdrMetadata(range.equals("pq") ? Optional.of(PQ_SOURCE_METADATA) : Optional.empty());
  }

  private static VideoDynamicRange dynamicRangeOf(String range) {
    var base =
        switch (range) {
          case "sdr10" -> VideoDynamicRange.CompatibleBase.SDR;
          case "pq" -> VideoDynamicRange.CompatibleBase.PQ;
          case "hlg" -> VideoDynamicRange.CompatibleBase.HLG;
          default -> throw new AssertionError("The recorder plans no range " + range);
        };
    return new VideoDynamicRange(
        base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
  }
}
