package com.streamarr.transcode.engine;

import java.util.ArrayList;
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
   * An FFmpeg that copies the source into fragmented MP4, and an FFmpeg that encodes that copy once
   * the worker has moved each Dolby Vision RPU to the end of the sample of the picture it belongs
   * to, as {@link FragmentedMp4RpuMover} says.
   *
   * @param copyCommand the copying FFmpeg's command, whose last output, the framecrc of the copy's
   *     video packets, lacks the URL it writes to
   */
  record RpuMovingPipe(@NonNull List<String> copyCommand, @NonNull List<String> encodeCommand)
      implements FfmpegPipeline {

    private static final String VIDEO_TIMING_URL = "<video timing socket>";

    public RpuMovingPipe {
      copyCommand = List.copyOf(copyCommand);
      encodeCommand = List.copyOf(encodeCommand);
    }

    /** The copying FFmpeg's command, whose framecrc of the copy's video goes to the URL. */
    List<String> copyCommandWritingVideoTimingTo(@NonNull String url) {
      var command = new ArrayList<>(copyCommand);
      command.add(url);
      return List.copyOf(command);
    }

    @Override
    public String commandLine() {
      return String.join(" ", copyCommandWritingVideoTimingTo(VIDEO_TIMING_URL))
          + " | "
          + String.join(" ", encodeCommand);
    }
  }
}
