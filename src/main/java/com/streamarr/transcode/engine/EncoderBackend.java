package com.streamarr.transcode.engine;

import lombok.Builder;
import lombok.NonNull;

/** The encoder that the worker runs for a codec family, and whether it encodes in hardware. */
@Builder
public record EncoderBackend(
    @NonNull CodecFamily codecFamily, @NonNull String encoder, boolean hardware) {}
