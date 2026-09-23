package com.streamarr.transcode.fixtures;

public final class FfmpegMuxerHelpFixtures {

  /** The lines of FFmpeg 8.1.2's {@code -h muxer=mp4} that name the fragmented MP4 options. */
  public static final String FRAGMENTED_MP4_MUXER_HELP =
      """
      Muxer mp4 [MP4 (MPEG-4 Part 14)]:
      mov/mp4/tgp/psp/tg2/ipod/ismv/f4v muxer AVOptions:
        -frag_duration     <int>        E.......... Maximum fragment duration
        -movflags          <flags>      E.......... MOV muxer flags (default 0)
           cmaf                         E.......... Write CMAF compatible fragmented MP4
           delay_moov                   E.......... Delay writing the initial moov
           frag_discont                 E.......... Signal that the next fragment is discontinuous
           frag_keyframe                E.......... Fragment at video keyframes
           skip_trailer                 E.......... Skip writing the mfra/tfra/mfro trailer
        -min_frag_duration <int>        E.......... Minimum fragment duration
      """;

  private FfmpegMuxerHelpFixtures() {}
}
