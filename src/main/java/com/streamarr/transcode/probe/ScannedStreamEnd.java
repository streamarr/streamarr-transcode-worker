package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import lombok.NonNull;

/**
 * Where a keyframe packet scan read the end of one stream's packets: {@code ticks} of the stream's
 * time base on the demuxer's timeline, before the container start time is subtracted.
 */
record ScannedStreamEnd(int streamIndex, @NonNull TimeBase timeBase, long ticks) {}
