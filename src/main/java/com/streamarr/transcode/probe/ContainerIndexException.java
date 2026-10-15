package com.streamarr.transcode.probe;

/** A container index whose structure the worker cannot read. */
final class ContainerIndexException extends RuntimeException {

  ContainerIndexException(String message) {
    super(message);
  }
}
