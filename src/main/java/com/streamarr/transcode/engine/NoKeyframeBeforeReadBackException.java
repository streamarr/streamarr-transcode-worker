package com.streamarr.transcode.engine;

/**
 * A held encode's search for the video's last keyframe found no keyframe before the attempt's seek
 * position, so the attempt has no picture to hold (ADR 0037, early ends).
 */
public class NoKeyframeBeforeReadBackException extends TranscodeException {

  NoKeyframeBeforeReadBackException() {
    super("The source's video has no keyframe before the attempt's seek position");
  }
}
