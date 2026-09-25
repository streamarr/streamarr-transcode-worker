package com.streamarr.transcode.engine;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * One {@code moof} with the {@code mdat} that follows it, each box's bytes exactly as read, the
 * start of its video track when it carries one, and the shortest duration among its video samples,
 * in the video track's timescale.
 */
record Fragment(
    @NonNull List<byte[]> boxes,
    @NonNull Optional<VideoStart> videoStart,
    @NonNull OptionalLong shortestVideoSampleDuration)
    implements Mp4Unit {

  Fragment {
    boxes = List.copyOf(boxes);
  }

  long byteLength() {
    return boxes.stream().mapToLong(box -> box.length).sum();
  }
}
