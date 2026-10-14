package com.streamarr.transcode.worker;

/** The job contradicts itself or lacks a value that the worker needs to run it. */
final class InvalidJobSpecificationException extends RuntimeException {

  InvalidJobSpecificationException(String message) {
    super(message);
  }
}
