package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;

/** Why a producer failed its job attempt. */
public enum ProducerFailure {
  /** FFmpeg exited with a non-zero status. */
  PROCESS_EXITED_WITH_ERROR,
  /** FFmpeg exited with status 0 without producing a media segment. */
  NO_MEDIA_SEGMENT,
  /** The sink did not accept a segment the producer closed. */
  SEGMENT_NOT_ACCEPTED,
  /** The output ended inside a box header, inside a box body, or after a moof with no mdat. */
  TRUNCATED_OUTPUT,
  /**
   * A box would make the initialization segment or a fragment exceed the server's segment cap, or a
   * fragment would make a media segment exceed it.
   */
  SEGMENT_CAP_EXCEEDED,
  /** A keyframe skipped a segment number, so the source has no keyframe inside that interval. */
  SKIPPED_SEGMENT_NUMBER,
  /**
   * An attempt that encodes video wrote a video sample shorter than half a frame at the rate it
   * forced, which FFmpeg writes when the encoder emits packets out of decode order.
   */
  SHORT_VIDEO_SAMPLE,
  /** The output is not fragmented MP4 that the producer can group into media segments. */
  MALFORMED_OUTPUT,
  /** Reading FFmpeg's standard output failed. */
  OUTPUT_UNREADABLE,
  /**
   * FFmpeg wrote nothing to its standard output for the stall timeout while the producer was
   * reading it, so the producer ended FFmpeg.
   */
  ENCODER_STALLED,
  /**
   * FFmpeg did not exit within the grace period after its complete output ended and the sink
   * accepted its last segment, so the producer ended FFmpeg.
   */
  PROCESS_DID_NOT_EXIT,
  /** The producer met a throwable it does not expect, such as a defect or an exhausted heap. */
  UNEXPECTED_ERROR;

  /** The failure of an attempt whose output was refused for the reason. */
  static ProducerFailure of(Reason reason) {
    return switch (reason) {
      case END_OF_FILE_IN_BOX_HEADER, END_OF_FILE_IN_BOX_BODY, END_OF_FILE_AFTER_MOVIE_FRAGMENT ->
          TRUNCATED_OUTPUT;
      case EXCEEDS_SEGMENT_CAP -> SEGMENT_CAP_EXCEEDED;
      case SKIPPED_SEGMENT_NUMBER -> SKIPPED_SEGMENT_NUMBER;
      case SHORT_VIDEO_SAMPLE -> SHORT_VIDEO_SAMPLE;
      case UNSIZED_BOX,
          MALFORMED_BOX,
          SAMPLE_DATA_OUTSIDE_MDAT,
          MISSING_INITIALIZATION_SEGMENT,
          MISPLACED_INITIALIZATION_SEGMENT,
          UNEXPECTED_BOX,
          MULTIPLE_VIDEO_TRACKS,
          PRESENTATION_TIME_REGRESSED ->
          MALFORMED_OUTPUT;
    };
  }
}
