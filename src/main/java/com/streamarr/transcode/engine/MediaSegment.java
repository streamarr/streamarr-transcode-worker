package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/**
 * One media segment's sequence number, the fragments the grouper held for it, in arrival order, and
 * its video samples in decode order. Some of those fragments' audio may belong to a neighbouring
 * segment, and so may the video samples on the far side of a keyframe inside the first or last of
 * them.
 */
record MediaSegment(
    int sequenceNumber,
    @NonNull List<Fragment> fragments,
    @NonNull List<CarriedSample> videoSamples) {

  MediaSegment {
    fragments = List.copyOf(fragments);
    videoSamples = List.copyOf(videoSamples);
  }
}
