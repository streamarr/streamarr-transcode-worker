package com.streamarr.transcode.engine;

/** A job attempt asks the worker to encode a codec family that the worker does not advertise. */
public class UnadvertisedCodecFamilyException extends TranscodeException {

  public UnadvertisedCodecFamilyException(String codecFamily) {
    super("The worker advertises no encoder for codec family " + codecFamily);
  }
}
