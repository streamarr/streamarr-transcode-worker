package com.streamarr.transcode.engine;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * One {@code moof} with the {@code mdat} that follows it, each box's bytes exactly as read, the
 * tracks its movie declares, the start of its video track when it carries one, the shortest
 * duration among its video samples, in the video track's timescale, and the samples of its video
 * and sound tracks in decode order.
 */
@Builder
record Fragment(
    @NonNull List<byte[]> boxes,
    @NonNull MovieTracks tracks,
    @NonNull Optional<VideoStart> videoStart,
    @NonNull OptionalLong shortestVideoSampleDuration,
    @NonNull List<Sample> videoSamples,
    @NonNull List<Sample> audioSamples)
    implements Mp4Unit {

  Fragment {
    boxes = List.copyOf(boxes);
    videoSamples = List.copyOf(videoSamples);
    audioSamples = List.copyOf(audioSamples);
  }

  /** The {@code mdat} box's bytes, where each sample's position counts from. */
  byte[] mediaDataBox() {
    return boxes.getLast();
  }

  long byteLength() {
    return boxes.stream().mapToLong(box -> box.length).sum();
  }
}
