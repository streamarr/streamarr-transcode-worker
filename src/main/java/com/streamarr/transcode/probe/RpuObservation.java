package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import java.util.Optional;
import lombok.NonNull;

/**
 * What the probe saw of a video stream's Dolby Vision RPUs: whether the frames or packets that it
 * read hold one, the header of the first one, and where the packets place each picture's RPU.
 */
record RpuObservation(
    @NonNull Presence presence,
    @NonNull Optional<DolbyVisionRpuHeader> firstHeader,
    @NonNull DolbyVisionRpuPlacement placement) {}
