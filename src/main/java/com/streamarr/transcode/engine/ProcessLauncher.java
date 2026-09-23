package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Starts FFmpeg for one job attempt. */
@FunctionalInterface
public interface ProcessLauncher {

  Process launch(List<String> command, UUID jobAttemptId) throws IOException;
}
