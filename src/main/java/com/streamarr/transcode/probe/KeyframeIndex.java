package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import java.util.List;
import lombok.NonNull;

/** What a container index says about the keyframes of the chosen video stream. */
sealed interface KeyframeIndex {

  /**
   * The keyframes the index lists, in decode-time order. Times count ticks of the time base on the
   * demuxer's timeline, before the container start time is subtracted.
   */
  record Listed(@NonNull TimeBase timeBase, @NonNull List<KeyframeTime> keyframes)
      implements KeyframeIndex {

    public Listed {
      keyframes = List.copyOf(keyframes);
    }
  }

  /** The index cannot give every keyframe, so only a packet scan can. */
  record Unusable(@NonNull KeyframePacketScanReason reason) implements KeyframeIndex {}

  record KeyframeTime(long presentationTime, long decodeTime) {}

  record TimeBase(long numerator, long denominator) {}
}
