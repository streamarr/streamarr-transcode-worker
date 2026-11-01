package com.streamarr.transcode.engine;

import lombok.NonNull;

/**
 * How an encode deinterlaces the source, before every other filter (streamarr/streamarr-server
 * #653). The job names the field that is displayed first and how many frames the deinterlacer
 * writes; the job's output frame rate already counts those frames.
 */
public record Deinterlace(@NonNull FirstField firstField, @NonNull Output output) {

  /** The field of each frame that was captured, and is displayed, first. */
  public enum FirstField {
    TOP,
    BOTTOM
  }

  /** How many frames the deinterlacer writes. */
  public enum Output {
    ONE_FRAME_PER_FIELD,
    ONE_FRAME_PER_FRAME
  }

  // bwdif deinterlaces every frame with the job's field order, never with each frame's own flags:
  // decoders can flag interlaced pictures as progressive, and an unflagged frame would otherwise
  // read as top field first.
  String filter() {
    var mode =
        switch (output) {
          case ONE_FRAME_PER_FIELD -> "send_field";
          case ONE_FRAME_PER_FRAME -> "send_frame";
        };
    var parity =
        switch (firstField) {
          case TOP -> "tff";
          case BOTTOM -> "bff";
        };
    return "bwdif=mode=" + mode + ":parity=" + parity + ":deint=all";
  }
}
