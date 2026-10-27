package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import tools.jackson.databind.JsonNode;

/**
 * Whether the probe's walks of a stream's configuration record and first packets can miss units.
 * The walks read no extradata other than an avcC, hvcC or av1C configuration record, and an avcC
 * record holds only parameter sets. Other extradata, such as the Annex B extradata of MPEG-TS and
 * raw streams, can hold units that the probe does not read, and FFmpeg's HEVC decoder applies the
 * SEI NAL units of Annex B extradata to the frames.
 */
final class WalkCoverage {

  private WalkCoverage() {}

  /** Whether the stream holds units that the walks do not read, so that they rule nothing out. */
  static boolean missesUnits(JsonNode stream) {
    var extradata = Extradata.of(stream);
    if (extradata.isEmpty()) {
      return false;
    }

    var syntax = NalSyntax.of(text(stream, "codec_name").orElse(""));
    return syntax.isEmpty() || syntax.get().lengthSize(extradata.get()).isEmpty();
  }
}
