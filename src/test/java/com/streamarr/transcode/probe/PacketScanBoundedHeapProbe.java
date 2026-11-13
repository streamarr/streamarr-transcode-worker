package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a keyframe packet scan in a JVM whose heap the caller bounds. The scanned source is MPEG-TS
 * with MPEG-2 video and as many AC-3 streams as the second argument names. The scan prints as many
 * video keyframes as the third argument names, and one packet of each AC-3 stream. The probe writes
 * the keyframe count that it reports, whether it reports a measurement, and its measurement part
 * count to the file that the first argument names.
 */
final class PacketScanBoundedHeapProbe {

  private PacketScanBoundedHeapProbe() {}

  static void main(String[] arguments) throws Exception {
    var audioStreams = Integer.parseInt(arguments[1]);
    var keyframes = Integer.parseInt(arguments[2]);
    var streams = streams(audioStreams);
    var packetScan = packetScan(audioStreams, keyframes);
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
                return ScriptedProcess.builder().output(packetScan).build();
              }

              var isFirstRead = command.arguments().contains("-read_intervals");
              return ScriptedProcess.builder()
                  .output((isFirstRead ? "" : streams).getBytes(StandardCharsets.UTF_8))
                  .build();
            });
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setScanKeyframePackets(true)
            .build();

    var source = Files.createTempFile("bounded-heap-scan", ".media");
    source.toFile().deleteOnExit();

    var report = executor.probe(source, request);

    var media = report.result().getMedia();
    Files.writeString(
        Path.of(arguments[0]),
        "keyframes=%d measured=%s parts=%d"
            .formatted(
                media.getKeyframes().getPositions().getKeyframeCount(),
                media.hasStreamMeasurements(),
                report.streamMeasurementParts().size()));
  }

  private static String streams(int audioStreams) {
    var streams =
        new StringBuilder(
            "{\"format\":{\"format_name\":\"mpegts\",\"start_time\":\"0.000000\"},\"streams\":["
                + "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"mpeg2video\","
                + "\"time_base\":\"1/90000\",\"has_b_frames\":1}");
    for (var index = 1; index <= audioStreams; index++) {
      streams.append(
          (",{\"index\":%d,\"codec_type\":\"audio\",\"codec_name\":\"ac3\","
                  + "\"sample_rate\":\"48000\",\"time_base\":\"1/90000\"}")
              .formatted(index));
    }

    return streams.append("]}").toString();
  }

  // Keyframes 3,750 ticks apart, with each AC-3 stream's one packet after the first keyframe.
  private static byte[] packetScan(int audioStreams, int keyframes) {
    var lines = new StringBuilder("0, 0, 0, 3750, 1, 0x00000000\n");
    for (var output = 1; output <= audioStreams; output++) {
      lines.append("%d, 0, 0, 2880, 1, 0x00000000\n".formatted(output));
    }

    for (var keyframe = 1L; keyframe < keyframes; keyframe++) {
      lines.append("0, %d, %d, 3750, 1, 0x00000000\n".formatted(keyframe * 3750, keyframe * 3750));
    }

    return lines.toString().getBytes(StandardCharsets.UTF_8);
  }
}
