package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;

import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What ffprobe prints for a video stream and for the probe's first-frames and first-packets reads
 * of it, built from hexadecimal NAL units and side data entries.
 */
final class FirstReads {

  private FirstReads() {}

  /** One sample whose NAL units each take a 4-byte length. */
  static String lengthPrefixed(String... units) {
    var sample = new StringBuilder();
    for (var unit : units) {
      var length = HexFormat.ofDelimiter(" ").parseHex(unit).length;
      sample
          .append(
              " %02x %02x %02x %02x "
                  .formatted(
                      length >> 24, (length >> 16) & 0xFF, (length >> 8) & 0xFF, length & 0xFF))
          .append(unit);
    }

    return sample.toString().trim().replaceAll(" +", " ");
  }

  /** One sample whose NAL units each follow a start code. */
  static String annexB(String... units) {
    var sample = new StringBuilder();
    for (var unit : units) {
      sample.append(" 00 00 00 01 ").append(unit);
    }

    return sample.toString().trim().replaceAll(" +", " ");
  }

  /** The first-packets read's output: one packet for each sample. */
  static String packets(String... samples) {
    var packets = new StringBuilder();
    for (var sample : samples) {
      if (!packets.isEmpty()) {
        packets.append(',');
      }

      packets
          .append("{\"data\":\"")
          .append(ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(sample)))
          .append("\"}");
    }

    return "{\"packets\":[" + packets + "]}";
  }

  /**
   * The first-packets read's output with new extradata side data on one packet, as FFmpeg's MP4
   * demuxer attaches it when a sample switches to another sample description.
   */
  static String withNewExtradata(String packets, int packet) {
    var output = (ObjectNode) new ObjectMapper().readTree(packets);
    ((ObjectNode) output.path("packets").get(packet))
        .putArray("side_data_list")
        .addObject()
        .put("side_data_type", "New Extradata");
    return output.toString();
  }

  static String extradata(String hex) {
    return "\"extradata\":\"%s\"".formatted(ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(hex)));
  }

  /** The first-frames read's output: one frame for each argument, holding that side data. */
  static String frames(String... sideData) {
    var frames = new StringBuilder();
    for (var data : sideData) {
      if (!frames.isEmpty()) {
        frames.append(',');
      }

      frames.append("{\"stream_index\":0,\"side_data_list\":[").append(data).append("]}");
    }

    return "{\"frames\":[" + frames + "]}";
  }

  static String output(String fields) {
    return """
        {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",%s}]}
        """
        .formatted(fields);
  }
}
