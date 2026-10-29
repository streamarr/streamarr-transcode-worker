package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.HevcCopies.aud;
import static com.streamarr.transcode.engine.HevcCopies.fragmentBuilder;
import static com.streamarr.transcode.engine.HevcCopies.initializationSegment;
import static com.streamarr.transcode.engine.HevcCopies.rpu;
import static com.streamarr.transcode.engine.HevcCopies.sample;
import static com.streamarr.transcode.engine.HevcCopies.slice;
import static com.streamarr.transcode.engine.IsoBoxes.concat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.List;

/**
 * An HEVC copy of a Dolby Vision profile 5 source whose second sample starts with the RPU of the
 * first picture, the copy with that RPU moved behind the first picture, and the framecrc that the
 * copying FFmpeg writes of the copy's video.
 */
public final class RpuMovingCopies {

  // No packet is a keyframe, and each lasts 1001 ticks of the copy's 24000 Hz timescale, which the
  // framecrc counts in 1/48000.
  private static final String COPIED_TIMING =
      """
      #tb 0: 1/48000
      #media_type 0: video
      #codec_id 0: hevc
      0,          0,       4004,     2002,        9, 0x00000000, F=0x0
      0,       2002,       6006,     2002,       20, 0x00000000, F=0x0
      """;

  private RpuMovingCopies() {}

  public static byte[] copied() {
    return concat(
        initializationSegment(),
        fragmentBuilder().video(0, sample(aud(), slice(0))).build(),
        fragmentBuilder().video(1001, sample(aud(), rpu(0), slice(1))).build());
  }

  public static byte[] moved() {
    return concat(
        initializationSegment(),
        fragmentBuilder().video(0, sample(aud(), slice(0), rpu(0))).build(),
        fragmentBuilder().video(1001, sample(aud(), slice(1))).build());
  }

  public static String copiedTiming() {
    return COPIED_TIMING;
  }

  /** The bytes of each video sample of the fragmented MP4, in hex, in decode order. */
  public static List<String> videoSamplesOf(byte[] stream) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
          .flatMap(
              fragment ->
                  fragment.videoSamples().stream()
                      .map(
                          sample ->
                              HexFormat.of()
                                  .formatHex(
                                      fragment.mediaDataBox(),
                                      sample.mediaDataPosition(),
                                      sample.mediaDataPosition() + Math.toIntExact(sample.size()))))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
