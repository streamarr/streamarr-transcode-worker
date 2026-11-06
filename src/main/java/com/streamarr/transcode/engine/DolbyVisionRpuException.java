package com.streamarr.transcode.engine;

/**
 * A Dolby Vision RPU cannot be read as the RPU syntax lays it out, or cannot be converted to
 * profile 8.1: it is truncated, its CRC32 does not match, or it carries a profile or a field value
 * that the conversion does not handle.
 */
final class DolbyVisionRpuException extends RuntimeException {

  DolbyVisionRpuException(String detail) {
    super(detail);
  }
}
