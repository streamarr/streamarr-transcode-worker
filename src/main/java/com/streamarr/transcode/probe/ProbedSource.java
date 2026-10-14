package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import java.nio.file.Path;
import lombok.Builder;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/** A successful probe result together with the ffprobe output it came from. */
@Builder
record ProbedSource(
    @NonNull Path source, @NonNull JsonNode ffprobeOutput, @NonNull ProbeAttemptResult result) {}
