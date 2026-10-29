package com.streamarr.transcode.probe;

import tools.jackson.databind.JsonNode;

/**
 * Whether the probe's walks of a stream's extradata and first packets can miss units. The walks
 * read an avcC, hvcC or av1C record, Annex B extradata as MPEG-TS and raw H.264 and HEVC streams
 * give it, and the bare OBUs that FFmpeg gives an AV1 stream in IVF or in a raw .obu file. Any
 * other extradata, and extradata that the walks cannot delimit, can hold units that the probe does
 * not read. A packet can also carry a replacement record as new extradata side data, which FFmpeg's
 * MP4 demuxer attaches when a sample switches to another sample description. ffprobe prints that
 * side data's type but not its bytes, and FFmpeg's decoders apply the replacement. A cut-short
 * first-frames read leaves packets past the walk that can hold any unit, and so does a
 * first-packets read that printed fewer packets than it asked for.
 */
final class WalkCoverage {

  private static final String NEW_EXTRADATA = "New Extradata";

  private WalkCoverage() {}

  /** Whether the stream holds units that the walks do not read, so that they rule nothing out. */
  static boolean missesUnits(JsonNode stream) {
    return missesPacketUnits(stream) || isUnwalkedExtradata(stream) || isCutShort(stream);
  }

  /**
   * Whether the probe cannot delimit every NAL unit of every packet that the walk covers: a packet
   * carries a replacement record, or the first-packets read printed fewer packets than it asked
   * for.
   */
  static boolean missesPacketUnits(JsonNode stream) {
    return isReplacingExtradata(stream)
        || stream.path(FirstPacketsProbe.FIRST_PACKETS_CUT_SHORT).asBoolean();
  }

  private static boolean isCutShort(JsonNode stream) {
    return FirstFrames.of(stream).filter(FirstFrames::cutShort).isPresent();
  }

  private static boolean isReplacingExtradata(JsonNode stream) {
    return stream
        .path(FirstPacketsProbe.FIRST_PACKETS)
        .valueStream()
        .flatMap(packet -> packet.path("side_data_list").valueStream())
        .anyMatch(side -> NEW_EXTRADATA.equals(side.path("side_data_type").asString("")));
  }

  /**
   * Whether the extradata takes a form that the walks do not read, or, for H.264 and HEVC, holds a
   * NAL unit that they cannot delimit. The AV1 walk itself tells the OBUs that it cannot delimit.
   */
  private static boolean isUnwalkedExtradata(JsonNode stream) {
    var extradata = Extradata.of(stream);
    if (extradata.isEmpty()) {
      return false;
    }

    return !ProbedCodecFamily.of(stream).walksEveryUnitOf(extradata.get());
  }
}
