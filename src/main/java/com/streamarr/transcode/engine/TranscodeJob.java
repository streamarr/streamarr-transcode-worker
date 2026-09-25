package com.streamarr.transcode.engine;

import lombok.Builder;

@Builder
public record TranscodeJob(TranscodeRequest request, String videoEncoder) {}
