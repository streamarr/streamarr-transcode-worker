package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.RebuiltSegment.SampleOf;
import java.util.List;
import lombok.NonNull;

/**
 * One media segment's sequence number, the fragments the grouper held for it, in arrival order, and
 * its video samples in decode order. Some of those fragments' audio may belong to a neighbouring
 * segment.
 */
record MediaSegment(
    int sequenceNumber, @NonNull List<Fragment> fragments, @NonNull List<SampleOf> videoSamples) {

  MediaSegment {
    fragments = List.copyOf(fragments);
    videoSamples = List.copyOf(videoSamples);
  }

  long byteLength() {
    return fragments.stream().mapToLong(Fragment::byteLength).sum();
  }
}
