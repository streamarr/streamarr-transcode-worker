package com.streamarr.transcode.probe;

import java.util.List;
import lombok.NonNull;

/** One run of a probe tool: the tool to start, the arguments it receives and its CPU priority. */
public record ProbeCommand(
    @NonNull ProbeTool tool, @NonNull List<String> arguments, @NonNull CpuPriority cpuPriority) {

  public ProbeCommand {
    arguments = List.copyOf(arguments);
  }

  /** A command at the worker's own CPU priority. */
  public ProbeCommand(ProbeTool tool, List<String> arguments) {
    this(tool, arguments, CpuPriority.NORMAL);
  }

  ProbeCommand atLowestCpuPriority() {
    return new ProbeCommand(tool, arguments, CpuPriority.LOWEST);
  }

  /** The FFmpeg programs that a probe attempt starts. */
  public enum ProbeTool {
    FFPROBE,
    FFMPEG
  }

  /**
   * The CPU priority of the tool's process. A probe that no playback request waits for runs at the
   * lowest priority, nice 19, so that the kernel gives the worker's job attempts the CPU first.
   */
  public enum CpuPriority {
    NORMAL,
    LOWEST
  }
}
