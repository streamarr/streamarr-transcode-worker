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
  /**
   * The output ended inside a box header, inside a box body, or after a moof with no mdat, or the
   * timing of the copy of the source's video ended before the copy's video samples did.
   */
  TRUNCATED_OUTPUT,
  /**
   * A box would make the initialization segment or a fragment exceed the server's segment cap, a
   * media segment would exceed it, or the producer's budget of two caps cannot also hold what
   * writing a media segment needs, or the next box beside what the reader holds, such as segments
   * awaiting their audio, while no delivery is in flight to free room.
   */
  SEGMENT_CAP_EXCEEDED,
  /**
   * A box or fragment of the copy of the source that the encoding FFmpeg reads through the Dolby
   * Vision RPU mover is larger than what the mover reads, which no segment boundary changes.
   */
  COPY_UNIT_TOO_LARGE,
  /** A keyframe skipped a segment number, so the source has no keyframe inside that interval. */
  SKIPPED_SEGMENT_NUMBER,
  /**
   * An attempt that encodes video wrote a video sample shorter than half a frame at the rate it
   * forced, which FFmpeg writes when the encoder emits packets out of decode order.
   */
  SHORT_VIDEO_SAMPLE,
  /**
   * The output is not fragmented MP4 that the producer can group into media segments, or the timing
   * of the copy of the source's video does not match the copy.
   */
  MALFORMED_OUTPUT,
  /**
   * The initialization segment declares video other than the job's video decision, so the producer
   * delivered nothing.
   */
  OUTPUT_MISMATCH,
  /**
   * The stream that the job names as its audio is not an audio stream of the source: the
   * initialization segment declares no sound track, or a second video track, which only an audio
   * map that names a video stream adds.
   */
  NOT_AN_AUDIO_STREAM,
  /**
   * A copy that converts single-track Dolby Vision profile 7 to profile 8.1 met a picture whose RPU
   * it cannot convert, so the producer delivered nothing more; every attempt with the same decision
   * meets the same picture (streamarr/streamarr-server#651).
   */
  DOLBY_VISION_CONVERSION_FAILED,
  /**
   * Reading FFmpeg's standard output failed, or the copy of the source did not reach the FFmpeg
   * that encodes it.
   */
  OUTPUT_UNREADABLE,
  /**
   * FFmpeg wrote nothing to its standard output for the stall timeout while the producer was
   * reading it, so the producer ended FFmpeg.
   */
  ENCODER_STALLED,
  /**
   * FFmpeg did not exit within the grace period after its output ended on a box boundary and the
   * sink accepted the segment in delivery, so the producer ended FFmpeg and delivered no segment
   * that the end of the output left open.
   */
  PROCESS_DID_NOT_EXIT,
  /** The producer met a throwable it does not expect, such as a defect or an exhausted heap. */
  UNEXPECTED_ERROR;

  /** The failure of an attempt whose output was refused for the reason. */
  static ProducerFailure of(Reason reason) {
    return switch (reason) {
      case END_OF_FILE_IN_BOX_HEADER,
          END_OF_FILE_IN_BOX_BODY,
          END_OF_FILE_AFTER_MOVIE_FRAGMENT,
          END_OF_VIDEO_TIMING ->
          TRUNCATED_OUTPUT;
      case EXCEEDS_SEGMENT_CAP -> SEGMENT_CAP_EXCEEDED;
      case SKIPPED_SEGMENT_NUMBER -> SKIPPED_SEGMENT_NUMBER;
      case SHORT_VIDEO_SAMPLE -> SHORT_VIDEO_SAMPLE;
      case OUTPUT_MISMATCH -> OUTPUT_MISMATCH;
      case DOLBY_VISION_CONVERSION_FAILED -> DOLBY_VISION_CONVERSION_FAILED;
      case MULTIPLE_VIDEO_TRACKS, NO_SOUND_TRACK -> NOT_AN_AUDIO_STREAM;
      case UNSIZED_BOX,
          MALFORMED_BOX,
          SAMPLE_DATA_OUTSIDE_MDAT,
          MISSING_INITIALIZATION_SEGMENT,
          MISPLACED_INITIALIZATION_SEGMENT,
          UNEXPECTED_BOX,
          PRESENTATION_TIME_REGRESSED,
          UNUSABLE_VIDEO_TIMING ->
          MALFORMED_OUTPUT;
    };
  }
}
