package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import lombok.Builder;
import lombok.NonNull;

/**
 * Where a keyframe packet scan read the end of one stream: {@code ticks} of the stream's time base
 * on the demuxer's timeline, before the container start time is subtracted, then {@code
 * marginSeconds} later.
 */
@Builder
record ScannedStreamEnd(
    int streamIndex,
    @NonNull TimeBase timeBase,
    @NonNull BigInteger ticks,
    @NonNull BigDecimal marginSeconds) {}
