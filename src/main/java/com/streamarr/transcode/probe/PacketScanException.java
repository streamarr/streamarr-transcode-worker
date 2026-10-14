package com.streamarr.transcode.probe;

/** A packet scan whose ffprobe run or output the worker cannot use. */
final class PacketScanException extends RuntimeException {

  PacketScanException(String message) {
    super(message);
  }
}
