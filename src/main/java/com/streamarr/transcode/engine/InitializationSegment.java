package com.streamarr.transcode.engine;

import java.util.Arrays;
import lombok.NonNull;

/** The {@code ftyp} and {@code moov} boxes exactly as read; equal when their bytes are equal. */
record InitializationSegment(byte @NonNull [] bytes) implements Mp4Unit {

  @Override
  public boolean equals(Object other) {
    return other instanceof InitializationSegment(var otherBytes)
        && Arrays.equals(bytes, otherBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "InitializationSegment[" + bytes.length + " bytes]";
  }
}
