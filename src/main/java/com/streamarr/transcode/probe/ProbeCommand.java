package com.streamarr.transcode.probe;

import java.util.List;
import lombok.NonNull;

/** One run of a probe tool: the tool to start and the arguments it receives. */
public record ProbeCommand(@NonNull ProbeTool tool, @NonNull List<String> arguments) {

  public ProbeCommand {
    arguments = List.copyOf(arguments);
  }

  /** The FFmpeg programs that a probe attempt starts. */
  public enum ProbeTool {
    FFPROBE,
    FFMPEG
  }
}
