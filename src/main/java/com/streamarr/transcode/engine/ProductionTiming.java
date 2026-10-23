package com.streamarr.transcode.engine;

import java.time.Duration;
import lombok.NonNull;

/**
 * A media segment's media duration and the wall-clock time that the worker took to produce it. The
 * production time leaves out every wait in which the producer held FFmpeg back until an earlier
 * segment's delivery was accepted.
 */
public record ProductionTiming(@NonNull Duration mediaDuration, @NonNull Duration productionTime) {}
