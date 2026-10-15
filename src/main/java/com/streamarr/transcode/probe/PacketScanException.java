package com.streamarr.transcode.probe;

/** A packet scan whose FFmpeg run or output the worker cannot use. */
final class PacketScanException extends RuntimeException {

  PacketScanException(String message) {
    super(message);
  }
}
