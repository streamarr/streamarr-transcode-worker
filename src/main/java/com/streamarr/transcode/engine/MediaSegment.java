package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/**
 * The fragments whose video one media segment carries, in arrival order, and the segment's sequence
 * number. Some of their audio may belong to a neighbouring segment.
 */
record MediaSegment(int sequenceNumber, @NonNull List<Fragment> fragments) {

  MediaSegment {
    fragments = List.copyOf(fragments);
  }

  long byteLength() {
    return fragments.stream().mapToLong(Fragment::byteLength).sum();
  }
}
