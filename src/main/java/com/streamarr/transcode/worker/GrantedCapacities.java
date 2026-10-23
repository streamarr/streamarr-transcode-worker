package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import lombok.NonNull;

/** The probe capacities that the worker grants the server at one moment. */
record GrantedCapacities(
    int backgroundProbeCapacity,
    int playbackProbeCapacity,
    int keyframePacketScanCapacity,
    @NonNull BackgroundProbeCapacityControl backgroundProbeCapacityControl) {}
