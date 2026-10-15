package com.streamarr.transcode.engine;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * ADR 0013's tag rule: the sample-entry tag that the worker writes for every H.264 and HEVC video
 * stream, whether it encodes or copies the stream. Each tag promises that the decoder configuration
 * record holds the parameter sets, which is what players need to play the stream.
 */
enum PlayableSampleEntry {
  AVC1("avc1", CodecFamily.H264, "h264_mp4toannexb", Set.of("avc1", "avc3", "dva1", "dvav")),
  HVC1("hvc1", CodecFamily.HEVC, "hevc_mp4toannexb", Set.of("hvc1", "hev1", "dvh1", "dvhe"));

  private final String tag;
  private final CodecFamily codecFamily;
  private final String annexBFilter;
  private final Set<String> sourceSampleEntries;

  PlayableSampleEntry(
      String tag, CodecFamily codecFamily, String annexBFilter, Set<String> sourceSampleEntries) {
    this.tag = tag;
    this.codecFamily = codecFamily;
    this.annexBFilter = annexBFilter;
    this.sourceSampleEntries = sourceSampleEntries;
  }

  /** The tag for the encoder's codec family; empty for a family that keeps FFmpeg's tag. */
  static Optional<PlayableSampleEntry> ofEncoder(String encoder) {
    return CodecFamily.ofEncoder(encoder)
        .flatMap(
            family ->
                Arrays.stream(values()).filter(entry -> entry.codecFamily == family).findFirst());
  }

  /**
   * The tag for a copy, from the sample entry that the copy's codec string names; empty for a codec
   * that keeps FFmpeg's tag.
   */
  static Optional<PlayableSampleEntry> ofCopy(VideoDecision.Copy copy) {
    var sourceSampleEntry = copy.codecString().split("\\.", 2)[0];
    return Arrays.stream(values())
        .filter(entry -> entry.sourceSampleEntries.contains(sourceSampleEntry))
        .findFirst();
  }

  String tag() {
    return tag;
  }

  /**
   * The bitstream filter that turns a copy's samples into Annex B. The mp4 muxer then builds the
   * record from the parameter sets that the source's record holds. When the source's record holds
   * none, the muxer builds the record from the parameter sets in the copy's first sample.
   */
  String annexBFilter() {
    return annexBFilter;
  }
}
