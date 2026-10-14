package com.streamarr.transcode.engine;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    @NonNull VideoDecision videoDecision,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision) {}
