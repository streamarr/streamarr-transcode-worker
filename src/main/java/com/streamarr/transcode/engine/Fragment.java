package com.streamarr.transcode.engine;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * One {@code moof} with the {@code mdat} that follows it, each box's bytes exactly as read, and the
 * start of its video track when it carries one.
 */
record Fragment(@NonNull List<byte[]> boxes, @NonNull Optional<VideoStart> videoStart)
    implements Mp4Unit {

  Fragment {
    boxes = List.copyOf(boxes);
  }

  long byteLength() {
    return boxes.stream().mapToLong(box -> box.length).sum();
  }
}
