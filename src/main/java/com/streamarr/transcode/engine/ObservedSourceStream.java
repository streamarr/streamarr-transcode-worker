package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * What FFmpeg read of one chosen source stream, on the attempt's zero-based timeline.
 *
 * @param sourceIndex the stream's index within the source container
 * @param lastStart the latest presentation time of a sample it read
 * @param end the latest end, presentation time plus duration, of a sample it read
 * @param lastKeyframeStart the presentation time of the last keyframe it read; empty when it read
 *     none
 */
@Builder
record ObservedSourceStream(
    int sourceIndex,
    @NonNull Duration lastStart,
    @NonNull Duration end,
    @NonNull Optional<Duration> lastKeyframeStart) {}
