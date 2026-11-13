package com.streamarr.transcode.fixtures;

public final class FfmpegFilterHelpFixtures {

  /**
   * {@code -h filter=agapfill} of the build of streamarr/streamarr-ffmpeg#4 at 309a80bd,
   * Streamarr's FFmpeg 8.1.3 fork with the agapfill filter.
   */
  public static final String GAP_FILL_FILTER_HELP =
      """
      Filter agapfill
        Fill gaps between audio frames with silence.
          Inputs:
             #0: default (audio)
          Outputs:
             #0: default (audio)
      agapfill AVOptions:
         threshold         <duration>   ..F.A...... set the longest forward step between frames \
      that is not a gap (default 0.002)
         start             <duration>   ..F.A...... set the time from which silence leads the \
      first frame (default INT64_MAX)
         max_gap           <duration>   ..F.A...... set the longest silence to write, 0 for no \
      limit (default 0)
         packet_size       <int>        ..F.A...... set the number of samples in each frame of \
      silence (from 1 to 65536) (default 4096)
      """;

  /** {@code -h filter=agapfill} of an FFmpeg without the filter, such as FFmpeg 8.1.3 itself. */
  public static final String UNKNOWN_GAP_FILL_FILTER_HELP = "Unknown filter 'agapfill'.\n";

  private FfmpegFilterHelpFixtures() {}
}
