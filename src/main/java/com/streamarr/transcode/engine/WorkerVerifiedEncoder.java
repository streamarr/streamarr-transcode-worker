package com.streamarr.transcode.engine;

import lombok.Builder;
import lombok.NonNull;

/**
 * An encoder backend that passed the worker's test encode, with whether ADR 0037 lists its encoder
 * as keyframe-verified.
 */
@Builder
public record WorkerVerifiedEncoder(@NonNull EncoderBackend backend, boolean keyframeVerified) {}
