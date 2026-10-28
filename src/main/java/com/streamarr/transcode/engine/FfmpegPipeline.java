package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/** The FFmpeg processes that one job attempt runs, and the command line of each. */
public sealed interface FfmpegPipeline {

  /** The pipeline as one shell-like line, for the log. */
  String commandLine();

  /** One FFmpeg that reads the source and writes the attempt's fragmented MP4. */
  record SingleProcess(@NonNull List<String> command) implements FfmpegPipeline {

    public SingleProcess {
      command = List.copyOf(command);
    }

    @Override
    public String commandLine() {
      return String.join(" ", command);
    }
  }

  /**
   * An FFmpeg that copies the source into MPEG-TS, and an FFmpeg that encodes that copy once the
   * worker has moved each Dolby Vision RPU that starts a sample to the end of the picture it
   * belongs to.
   */
  record RpuMovingPipe(@NonNull List<String> copyCommand, @NonNull List<String> encodeCommand)
      implements FfmpegPipeline {

    public RpuMovingPipe {
      copyCommand = List.copyOf(copyCommand);
      encodeCommand = List.copyOf(encodeCommand);
    }

    @Override
    public String commandLine() {
      return String.join(" ", copyCommand) + " | " + String.join(" ", encodeCommand);
    }
  }
}
