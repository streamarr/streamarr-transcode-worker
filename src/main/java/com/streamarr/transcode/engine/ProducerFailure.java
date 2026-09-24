package com.streamarr.transcode.engine;

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
  /** The output is not fragmented MP4 that the producer can group into media segments. */
  MALFORMED_OUTPUT,
  /** Reading FFmpeg's standard output failed. */
  OUTPUT_UNREADABLE,
  /** The producer met a throwable it does not expect, such as a defect or an exhausted heap. */
  UNEXPECTED_ERROR
}
