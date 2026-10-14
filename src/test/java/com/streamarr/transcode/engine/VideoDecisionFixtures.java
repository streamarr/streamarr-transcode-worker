package com.streamarr.transcode.engine;

import java.util.Optional;

/** Video decisions that engine tests share. */
final class VideoDecisionFixtures {

  /** The codec string of the libx264 recordings: H.264 High at level 1.0. */
  static final String ENCODED_CODEC_STRING = "avc1.64000A";

  private VideoDecisionFixtures() {}

  /** An H.264 High encode at level 1.0, which every libx264 recording declares. */
  static VideoDecision.Encode.EncodeBuilder encodeBuilder() {
    return VideoDecision.Encode.builder()
        .codecProfile(VideoCodecProfile.H264_HIGH)
        .tier(Optional.empty())
        .level(10)
        .codecString(ENCODED_CODEC_STRING);
  }

  /** The video decision that a job for the recording carries. */
  static VideoDecision forRecording(FfmpegRecordings.Recording recording) {
    if (recording.encoder().isEmpty()) {
      return new VideoDecision.Copy(recording.videoCodecString());
    }

    return encodeBuilder().codecString(recording.videoCodecString()).build();
  }

  /** The video decision that the mode implies: the H.264 encode, or a copy of the same string. */
  static VideoDecision forMode(TranscodeMode mode) {
    if (mode.encodesVideo()) {
      return encodeBuilder().build();
    }

    return new VideoDecision.Copy(ENCODED_CODEC_STRING);
  }
}
