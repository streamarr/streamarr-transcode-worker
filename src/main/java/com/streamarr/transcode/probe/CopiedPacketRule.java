package com.streamarr.transcode.probe;

/**
 * The rule that decides, for a stream's codec, whether a stream copy delivers each of the stream's
 * packets as one sample of the same size (ADR 0054). The sample-table measurer checks each rule
 * against the MP4 boxes and the bitrate scan against ffprobe's descriptions and packet listing.
 */
enum CopiedPacketRule {
  /** H.264, kept only when a copy needs no Annex B filter: {@link CopiedPacketBytes} decides it. */
  H264_RECORD,
  /** HEVC, kept only when a copy needs no Annex B filter: {@link CopiedPacketBytes} decides it. */
  HEVC_RECORD,
  /**
   * AV1, whose padding and temporal delimiter OBUs the mp4 muxer drops, and VP9, which the mp4
   * muxer passes through its superframe filter.
   */
  ALWAYS_CHANGED,
  /**
   * AAC, kept only with an AudioSpecificConfig and without ADTS frames, whose headers the copy's
   * {@code aac_adtstoasc} strips.
   */
  AAC_WITHOUT_ADTS,
  /**
   * E-AC-3, kept only when each packet holds 1,536 audio samples, because the mp4 muxer joins
   * shorter frames into samples of six audio blocks.
   */
  SIX_BLOCK_EAC3,
  /** Every other codec reaches the delivered segment as its packets are. */
  KEPT;

  /** The rule for ffprobe's codec name. */
  static CopiedPacketRule of(String codecName) {
    return switch (codecName) {
      case "h264" -> H264_RECORD;
      case "hevc" -> HEVC_RECORD;
      case "av1", "vp9" -> ALWAYS_CHANGED;
      case "aac" -> AAC_WITHOUT_ADTS;
      case "eac3" -> SIX_BLOCK_EAC3;
      default -> KEPT;
    };
  }
}
