package com.streamarr.transcode.engine;

import lombok.Builder;
import lombok.NonNull;

/**
 * Whether one video packet of the copy of the source is a keyframe, and the duration the source's
 * demuxer read for it, in the time base of the source's video stream. A duration of zero or less is
 * unknown.
 */
@Builder
record VideoPacketTiming(boolean keyframe, long duration, @NonNull TimeBase timeBase) {}
