package com.streamarr.transcode.engine;

/** Why a producer failed its job attempt. */
public enum ProducerFailure {
  /** FFmpeg exited with a non-zero status. */
  PROCESS_EXITED_WITH_ERROR,
  /** FFmpeg exited with status 0 without producing a media segment. */
  NO_MEDIA_SEGMENT,
  /** The output ended inside a box header, inside a box body, or after a moof with no mdat. */
  TRUNCATED_OUTPUT,
  /** The initialization segment or a media segment would exceed the server's segment cap. */
  SEGMENT_CAP_EXCEEDED,
  /** A keyframe skipped a segment number, so the source has no keyframe inside that interval. */
  SKIPPED_SEGMENT_NUMBER,
  /** The output is not fragmented MP4 that the producer can group into media segments. */
  MALFORMED_OUTPUT,
  /** Reading FFmpeg's standard output failed. */
  OUTPUT_UNREADABLE
}
