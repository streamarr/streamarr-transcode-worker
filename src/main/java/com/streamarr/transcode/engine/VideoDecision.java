package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/** Whether the job copies or encodes the video, and the codec string the variant advertises. */
public sealed interface VideoDecision {

  /** The chosen video stream's absolute index in the source container. */
  int streamIndex();

  /** The codec string that every initialization segment of the variant declares for its video. */
  String codecString();

  /** Whether the job lists the bitstream rewrite, which only a stream copy can. */
  default boolean lists(BitstreamRewrite rewrite) {
    return false;
  }

  /**
   * A stream copy, whose codec string the probe computed for the source stream.
   *
   * @param keepsPacketBytes whether the copy delivers every packet of the source stream with its
   *     bytes unchanged, which the probe decides from the source's decoder configuration record;
   *     false when the job does not say so
   * @param bitstreamRewrites the changes that the copy makes to the source's video; empty for a
   *     copy that passes it through unchanged
   * @param plannedOutput the output that the server planned for the copy; empty for a copy that
   *     passes the source's range through
   */
  @Builder
  record Copy(
      int streamIndex,
      @NonNull String codecString,
      boolean keepsPacketBytes,
      @NonNull Set<BitstreamRewrite> bitstreamRewrites,
      @NonNull Optional<PlannedCopyOutput> plannedOutput)
      implements VideoDecision {

    public Copy {
      bitstreamRewrites = Set.copyOf(bitstreamRewrites);
      var dolbyVision = plannedOutput.flatMap(PlannedCopyOutput::dolbyVision);
      if (bitstreamRewrites.contains(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
          && dolbyVision.isEmpty()) {
        throw new IllegalArgumentException(
            "a copy that normalizes its Dolby Vision record plans a Dolby Vision output");
      }

      if (bitstreamRewrites.contains(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1)
          && !(bitstreamRewrites.contains(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
              && dolbyVision
                  .filter(PlannedCopyOutput.DolbyVisionOutput::isProfile81)
                  .isPresent())) {
        throw new IllegalArgumentException(
            "a copy that converts profile 7 to profile 8.1 plans a profile 8.1 output and"
                + " normalizes its record");
      }
    }

    @Override
    public boolean lists(BitstreamRewrite rewrite) {
      return bitstreamRewrites.contains(rewrite);
    }

    // Lombok builder default — its generated setter assigns it
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class CopyBuilder {
      private Set<BitstreamRewrite> bitstreamRewrites = Set.of();
      private Optional<PlannedCopyOutput> plannedOutput = Optional.empty();
    }
  }

  /**
   * An encode, which forces the codec profile, the tier and the level.
   *
   * @param sourceCodecFamily the codec family of the chosen source stream; empty for a codec of no
   *     family the worker encodes
   * @param tier empty for H.264, which has no tier
   * @param level the level as major × 10 + minor, for example 41 for level 4.1
   * @param sourcePacketsCarryPresentationTimes whether the probe saw a presentation time on every
   *     packet of the source stream that it read; false also when it could not tell
   * @param deinterlace how the encode deinterlaces an interlaced source; empty for every other
   *     source
   * @param holdsLastFrameToAdvertisedEnd whether the encode repeats the last picture its filters
   *     produce from the source up to the request's advertised end, also when the attempt starts
   *     after the source's last frame
   * @param softwareEncoder whether the encode runs on the codec family's software encoder, which
   *     the server asks for after a hardware encoder failed (ADR 0044)
   * @param outputPictureFormat the bit depth and chroma subsampling that the encoder writes
   * @param staticHdrMetadata the HDR10 static metadata that the encoder writes; present exactly
   *     when the output is PQ
   * @param assumedSourceMatrix the matrix with which the encode reads the source's pixels; empty
   *     when the encode reads each frame with the matrix that the frame names
   */
  @Builder
  record Encode(
      int streamIndex,
      @NonNull Optional<CodecFamily> sourceCodecFamily,
      @NonNull VideoCodecProfile codecProfile,
      @NonNull Optional<VideoCodecTier> tier,
      int level,
      @NonNull String codecString,
      @NonNull VideoDynamicRange sourceDynamicRange,
      @NonNull VideoDynamicRange outputDynamicRange,
      boolean sourcePacketsCarryPresentationTimes,
      @NonNull Optional<Deinterlace> deinterlace,
      boolean holdsLastFrameToAdvertisedEnd,
      boolean softwareEncoder,
      @NonNull PictureFormat outputPictureFormat,
      @NonNull Optional<StaticHdrMetadata> staticHdrMetadata,
      @NonNull Optional<AssumedSourceMatrix> assumedSourceMatrix)
      implements VideoDecision {

    // A PQ or HLG picture in 8 bits bands visibly, and FFmpeg's tonemapx filter aborts the process
    // when it reshapes Dolby Vision into PQ in an 8-bit format.
    public Encode {
      if (outputsHdr(outputDynamicRange) && outputPictureFormat.bitDepth() < 10) {
        throw new IllegalArgumentException(
            "a PQ or HLG encode writes 10 bits, not " + outputPictureFormat.bitDepth());
      }

      var outputsPq = outputDynamicRange.compatibleBase() == VideoDynamicRange.CompatibleBase.PQ;
      if (outputsPq != staticHdrMetadata.isPresent()) {
        throw new IllegalArgumentException(
            "an encode carries static HDR metadata exactly when its output is PQ");
      }

      if (assumedSourceMatrix.isPresent()
          && !AssumedSourceMatrix.isAssumableFor(sourceDynamicRange)) {
        throw new IllegalArgumentException(
            "only an encode of an SDR or untagged source assumes a source matrix");
      }
    }

    private static boolean outputsHdr(VideoDynamicRange range) {
      return switch (range.compatibleBase()) {
        case PQ, HLG -> true;
        case SDR, NONE, UNSPECIFIED -> false;
      };
    }

    // Lombok builder defaults — their generated setters assign them
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class EncodeBuilder {
      private Optional<Deinterlace> deinterlace = Optional.empty();
      private PictureFormat outputPictureFormat = PictureFormat.EIGHT_BIT_420;
      private Optional<StaticHdrMetadata> staticHdrMetadata = Optional.empty();
      private Optional<AssumedSourceMatrix> assumedSourceMatrix = Optional.empty();
    }
  }
}
