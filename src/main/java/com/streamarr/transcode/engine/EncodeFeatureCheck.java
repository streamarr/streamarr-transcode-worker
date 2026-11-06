package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The worker's own test encode of one encode feature with one encoder: one frame at 64x64 through
 * the filters and encoder arguments that a job's command uses, written as fragmented MP4. The
 * encoder passes when its initialization segment declares 10-bit 4:2:0 and, for an HDR feature, the
 * BT.2020 colour description of its transfer.
 */
final class EncodeFeatureCheck {

  private static final String LAVFI_SOURCE = "color=size=64x64:rate=24";
  private static final int TEST_HEVC_LEVEL = 10;
  private static final int TEST_AV1_LEVEL = 20;
  private static final PictureFormat TEN_BIT_420 =
      new PictureFormat(10, ChromaSubsampling.CHROMA_420);
  // Decision 173's fallback, which a job without the source's own values carries.
  private static final StaticHdrMetadata FALLBACK_METADATA =
      new StaticHdrMetadata(
          PlannedCopyOutput.MasteringDisplay.builder()
              .red(new PlannedCopyOutput.Chromaticity(34000, 16000))
              .green(new PlannedCopyOutput.Chromaticity(13250, 34500))
              .blue(new PlannedCopyOutput.Chromaticity(7500, 3000))
              .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
              .maxLuminance(10_000_000)
              .minLuminance(1)
              .build(),
          new PlannedCopyOutput.ContentLightLevel(0, 0));

  private final EncodeFeature feature;
  private final VideoDecision.Encode encode;

  private EncodeFeatureCheck(EncodeFeature feature, CodecFamily family) {
    this.feature = feature;
    var output = outputOf(feature);
    this.encode =
        VideoDecision.Encode.builder()
            .sourceCodecFamily(Optional.empty())
            .sourceDynamicRange(output)
            .outputDynamicRange(output)
            .codecProfile(
                family == CodecFamily.HEVC
                    ? VideoCodecProfile.HEVC_MAIN_10
                    : VideoCodecProfile.AV1_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(family == CodecFamily.HEVC ? TEST_HEVC_LEVEL : TEST_AV1_LEVEL)
            .codecString("")
            .outputPictureFormat(TEN_BIT_420)
            .staticHdrMetadata(
                output.compatibleBase() == VideoDynamicRange.CompatibleBase.PQ
                    ? Optional.of(FALLBACK_METADATA)
                    : Optional.empty())
            .build();
  }

  /** The test encode of the feature with an encoder of the codec family, HEVC or AV1. */
  static EncodeFeatureCheck of(EncodeFeature feature, CodecFamily family) {
    if (family == CodecFamily.H264) {
      throw new IllegalArgumentException("H.264 encodes only 8-bit SDR");
    }

    return new EncodeFeatureCheck(feature, family);
  }

  /**
   * FFmpeg's arguments after its path.
   *
   * @param dolbyVisionFrame the one-frame Dolby Vision profile 5 input of the reshape's test
   */
  List<String> arguments(String encoder, Path dolbyVisionFrame) {
    var arguments = new ArrayList<>(List.of("-nostdin", "-v", "quiet"));
    if (feature == EncodeFeature.DOLBY_VISION_TO_PQ) {
      arguments.addAll(List.of("-i", dolbyVisionFrame.toString()));
    } else {
      arguments.addAll(List.of("-f", "lavfi", "-i", LAVFI_SOURCE));
    }

    arguments.addAll(List.of("-frames:v", "1", "-an", "-vf", filters()));
    arguments.addAll(FfmpegCommandBuilder.encoderArguments(encoder, encode));
    arguments.addAll(
        List.of(
            "-f",
            "mp4",
            "-movflags",
            String.join("+", FfmpegCommandBuilder.MP4_MOVFLAGS),
            "pipe:1"));
    return List.copyOf(arguments);
  }

  /** Whether FFmpeg's output declares the feature's picture format and colour description. */
  boolean passes(byte[] output) {
    var initialization = initializationOf(output);
    if (initialization.isEmpty()) {
      return false;
    }

    try {
      var sampleEntry =
          CodecConfigurationReader.firstVideoSampleEntryOf(initialization.get()).orElse(null);
      if (sampleEntry == null
          || !CodecConfigurationReader.videoOf(sampleEntry)
              .pictureFormat()
              .equals(Optional.of(TEN_BIT_420))) {
        return false;
      }

      return HdrTransfer.of(encode.outputDynamicRange())
          .map(
              transfer ->
                  CodecConfigurationReader.colourDescriptionOf(sampleEntry)
                      .equals(
                          Optional.of(
                              new CodecConfigurationReader.ColourDescription(
                                  9, transfer.transferCharacteristics(), 9))))
          .orElse(true);
    } catch (FragmentedMp4Exception _) {
      return false;
    }
  }

  private String filters() {
    return switch (feature) {
      case TEN_BIT -> "format=yuv420p10le";
      case PQ, HLG ->
          "format=yuv420p10le,"
              + FfmpegCommandBuilder.frameColourFilter(
                  HdrTransfer.of(encode.outputDynamicRange()).orElseThrow());
      case DOLBY_VISION_TO_PQ ->
          FfmpegCommandBuilder.DOLBY_VISION_TO_PQ_RESHAPE_FILTERS
              + ","
              + FfmpegCommandBuilder.frameColourFilter(HdrTransfer.PQ);
    };
  }

  private static VideoDynamicRange outputOf(EncodeFeature feature) {
    var base =
        switch (feature) {
          case TEN_BIT -> VideoDynamicRange.CompatibleBase.SDR;
          case PQ, DOLBY_VISION_TO_PQ -> VideoDynamicRange.CompatibleBase.PQ;
          case HLG -> VideoDynamicRange.CompatibleBase.HLG;
        };
    return new VideoDynamicRange(
        base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
  }

  // The top-level boxes up to and including the moov; empty when the output holds no moov.
  private static Optional<InitializationSegment> initializationOf(byte[] output) {
    var boxes = ByteBuffer.wrap(output);
    while (boxes.remaining() >= 8) {
      var start = boxes.position();
      var size = Integer.toUnsignedLong(boxes.getInt());
      var type = new byte[4];
      boxes.get(type);
      if (size < 8 || size > output.length - start) {
        return Optional.empty();
      }

      boxes.position(start + (int) size);
      if (new String(type, StandardCharsets.US_ASCII).equals("moov")) {
        return Optional.of(new InitializationSegment(Arrays.copyOf(output, boxes.position())));
      }
    }

    return Optional.empty();
  }
}
