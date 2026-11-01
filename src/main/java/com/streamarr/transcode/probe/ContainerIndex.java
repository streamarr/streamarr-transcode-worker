package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackMeasurement;
import java.util.List;
import lombok.NonNull;

/**
 * What a container index gives: the keyframes of the chosen video stream and, when the index lists
 * every sample, the measurement of each video and audio stream. An empty list measures nothing.
 */
record ContainerIndex(@NonNull KeyframeIndex keyframes, @NonNull List<TrackMeasurement> measured) {

  ContainerIndex {
    measured = List.copyOf(measured);
  }

  static ContainerIndex keyframesOnly(@NonNull KeyframeIndex keyframes) {
    return new ContainerIndex(keyframes, List.of());
  }
}
