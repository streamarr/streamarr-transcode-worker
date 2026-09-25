package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/** The fragments of one media segment, in arrival order, and the segment's sequence number. */
record MediaSegment(int sequenceNumber, @NonNull List<Fragment> fragments) {

  MediaSegment {
    fragments = List.copyOf(fragments);
  }

  long byteLength() {
    return fragments.stream().mapToLong(Fragment::byteLength).sum();
  }
}
