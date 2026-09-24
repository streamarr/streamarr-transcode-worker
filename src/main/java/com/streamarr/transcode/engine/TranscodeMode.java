package com.streamarr.transcode.engine;

public enum TranscodeMode {
  REMUX,
  AUDIO_TRANSCODE,
  VIDEO_TRANSCODE,
  FULL_TRANSCODE;

  /** Whether the mode encodes the video; a mode that does not is a stream copy. */
  public boolean encodesVideo() {
    return switch (this) {
      case VIDEO_TRANSCODE, FULL_TRANSCODE -> true;
      case REMUX, AUDIO_TRANSCODE -> false;
    };
  }
}
