package com.streamarr.transcode.fixtures;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import java.util.List;
import java.util.stream.Stream;

public final class RecordingFixtures {

  /** A constant 23.976 fps encode, the output most tests replay as FFmpeg's. */
  public static final String ENCODED_RECORDING = "01-encode-cfr.fmp4";

  private RecordingFixtures() {}

  /** The names the worker uploads the recording's segments under, in delivery order. */
  public static List<String> uploadNames(Recording recording) {
    return Stream.concat(
            Stream.of("init.mp4"),
            recording.segments().stream().map(segment -> "segment" + segment.number() + ".m4s"))
        .toList();
  }
}
