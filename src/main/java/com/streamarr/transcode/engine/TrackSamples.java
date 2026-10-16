package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/** The samples one {@code traf} carries for its track, in decode order. */
record TrackSamples(long trackId, @NonNull List<Sample> samples) {}
