package com.streamarr.transcode.engine;

/** Why a producer failed its job attempt. */
public enum ProducerFailure {
  /** FFmpeg exited with a non-zero status. */
  PROCESS_EXITED_WITH_ERROR,
  /** Reading FFmpeg's standard output failed. */
  OUTPUT_UNREADABLE
}
