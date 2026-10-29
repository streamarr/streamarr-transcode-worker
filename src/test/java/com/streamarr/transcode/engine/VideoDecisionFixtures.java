package com.streamarr.transcode.engine;

import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING_CODEC_STRING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING_LEVEL;

import java.util.Optional;

/** Video decisions that engine tests share. */
final class VideoDecisionFixtures {

  private VideoDecisionFixtures() {}

  /**
   * An H.264 High encode at level 1.0, which every libx264 recording declares, of a source whose
   * codec family the job leaves unspecified.
   */
  static VideoDecision.Encode.EncodeBuilder encodeBuilder() {
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.empty())
        .sourceDynamicRange(VideoDynamicRange.unspecified())
        .outputDynamicRange(VideoDynamicRange.sdr())
        .codecProfile(VideoCodecProfile.H264_HIGH)
        .tier(Optional.empty())
        .level(ENCODED_RECORDING_LEVEL)
        .codecString(ENCODED_RECORDING_CODEC_STRING);
  }

  /** The video decision that a job for the recording carries. */
  static VideoDecision forRecording(FfmpegRecordings.Recording recording) {
    if (recording.encoder().isEmpty()) {
      return new VideoDecision.Copy(0, recording.videoCodecString());
    }

    return encodeBuilder().codecString(recording.videoCodecString()).build();
  }

  /** The video decision that the mode implies: the H.264 encode, or a copy of the same string. */
  static VideoDecision forMode(TranscodeMode mode) {
    if (mode.encodesVideo()) {
      return encodeBuilder().build();
    }

    return new VideoDecision.Copy(0, ENCODED_RECORDING_CODEC_STRING);
  }
}
