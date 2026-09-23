package com.streamarr.transcode.engine;

/** Why a producer failed its job attempt. */
public enum ProducerFailure {
  /** Reading FFmpeg's standard output failed. */
  OUTPUT_UNREADABLE
}
