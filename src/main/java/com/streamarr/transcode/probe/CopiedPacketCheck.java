package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a stream copy does to one stream's packets, as far as ffprobe's description of the stream
 * and the stream's first packet tell (ADR 0054). The E-AC-3 rule needs each packet's duration, so
 * the step that lists the packets checks it.
 */
@RequiredArgsConstructor
final class CopiedPacketCheck {

  private static final int FIRST_PACKET_OUTPUT_BYTES = 1 << 20;

  @NonNull private final BoundedFfprobeRunner boundedFfprobe;
  @NonNull private final ObjectMapper objectMapper;

  /** What a stream copy does to a stream's packets. */
  enum PacketCopy {
    KEEPS_PACKETS,
    CHANGES_PACKETS,
    /** The stream's first packet is too large to be one of its codec's. */
    UNREADABLE
  }

  /** What a copy does to the packets of the stream that {@code description} describes. */
  PacketCopy of(ProbedSource probed, int streamIndex, JsonNode description)
      throws ExecutionException, InterruptedException {
    return switch (CopiedPacketRule.of(text(description, "codec_name").orElse(""))) {
      case H264_RECORD, HEVC_RECORD ->
          keepsPacketsWhen(CopiedPacketBytes.of(description, probed.formatName()).orElse(false));
      case ALWAYS_CHANGED -> PacketCopy.CHANGES_PACKETS;
      case AAC_WITHOUT_ADTS ->
          Extradata.of(description).isPresent()
              ? firstAacPacketCopy(probed, streamIndex)
              : PacketCopy.CHANGES_PACKETS;
      case SIX_BLOCK_EAC3 ->
          keepsPacketsWhen(
              number(description, "sample_rate", Long::parseLong)
                  .filter(rate -> rate > 0)
                  .isPresent());
      case KEPT -> PacketCopy.KEEPS_PACKETS;
    };
  }

  private static PacketCopy keepsPacketsWhen(boolean isKept) {
    return isKept ? PacketCopy.KEEPS_PACKETS : PacketCopy.CHANGES_PACKETS;
  }

  /**
   * The mp4 muxer adds {@code aac_adtstoasc} when the first AAC packet it receives starts with an
   * ADTS sync word. A read that fails says nothing about the packet, so it fails the step.
   */
  private PacketCopy firstAacPacketCopy(ProbedSource probed, int streamIndex)
      throws ExecutionException, InterruptedException {
    var arguments = firstPacketRead(probed, streamIndex, List.of("packet=data", "-show_data"));
    var output = boundedFfprobe.run(arguments, FIRST_PACKET_OUTPUT_BYTES);
    if (output.isEmpty()) {
      return PacketCopy.UNREADABLE;
    }

    if (output.get().exitCode() != 0) {
      throw new PacketScanException(
          "ffprobe's first AAC packet read exited with code " + output.get().exitCode());
    }

    var packets = objectMapper.readTree(output.get().stdout()).path("packets");
    if (packets.isEmpty()) {
      return PacketCopy.KEEPS_PACKETS;
    }

    var data = Extradata.dumped(packets.get(0).path("data").asString("")).orElse(new byte[0]);
    var isAdts = data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xF0) == 0xF0;
    return keepsPacketsWhen(!isAdts);
  }

  /** The arguments of a read of the stream's first packet that shows the entries. */
  static List<String> firstPacketRead(ProbedSource probed, int streamIndex, List<String> entries) {
    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    arguments.addAll(
        List.of("-select_streams", String.valueOf(streamIndex), "-read_intervals", "%+#1"));
    arguments.add("-show_entries");
    arguments.addAll(entries);
    arguments.add(probed.source().toString());
    return arguments;
  }
}
