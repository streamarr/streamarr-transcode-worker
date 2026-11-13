package com.streamarr.transcode.engine;

import lombok.Builder;

/**
 * A job attempt's request and the encoder that runs it.
 *
 * @param gpuDolbyVisionReshapeVerified whether the encoder passed the worker's own test encode of
 *     the Dolby Vision reshape on the GPU, so that the attempt may decode, scale and reshape there
 */
@Builder
public record TranscodeJob(
    TranscodeRequest request, String videoEncoder, boolean gpuDolbyVisionReshapeVerified) {}
