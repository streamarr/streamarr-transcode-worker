package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.fakes.EndlessOutputProcess;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Probes with ffprobe output that the pinned worker image recorded into {@code
 * src/test/resources/probe}, or with output a test writes itself. The request asks for a packet
 * scan, which finds no packets, so a probe never reads the source file.
 */
final class RecordedFfprobe {

  private static final Path SOURCE = Path.of("/media/source");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RecordedFfprobe() {}

  static ProbeMediaInfo probeFixture(String fixture) {
    return probeOutput(fixture(fixture), packets(fixture));
  }

  static ProbeStreamInfo streamOf(String fixture, int index) {
    return probeFixture(fixture).getStreamsList().stream()
        .filter(stream -> stream.getIndex() == index)
        .findFirst()
        .orElseThrow(() -> new AssertionError(fixture + " has no stream " + index));
  }

  static ProbeMediaInfo probeOutput(String ffprobeOutput) {
    return probeOutput(ffprobeOutput, "");
  }

  /**
   * Probes with the given ffprobe output, and answers the frame-rate rule's packet scan with the
   * given packet lines.
   */
  static ProbeMediaInfo probeOutput(String ffprobeOutput, String packetLines) {
    var result = probeResult(ffprobeOutput, packetLines);
    assertThat(result.hasMedia()).as("the probe succeeds").isTrue();
    return result.getMedia();
  }

  static ProbeAttemptResult probeResult(String ffprobeOutput) {
    return probeResult(ffprobeOutput, "");
  }

  /** Probes with the given ffprobe output while the frame-rate scan prints packets forever. */
  static ProbeAttemptResult probeResultWithEndlessScan(
      String ffprobeOutput, IntFunction<String> packetLines) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              if (command.arguments().contains("-read_intervals")) {
                return new EndlessOutputProcess(packetLines);
              }

              var output =
                  switch (command.tool()) {
                    case FFPROBE -> answer(ffprobeOutput, command.arguments());
                    case FFMPEG -> "";
                  };
              return ScriptedProcess.builder()
                  .output(output.getBytes(StandardCharsets.UTF_8))
                  .build();
            });
    return executor.probe(SOURCE, request()).result();
  }

  /** Probes with the given ffprobe output while the frame-rate scan exits with the given code. */
  static ProbeAttemptResult probeResultWithScanExitCode(String ffprobeOutput, int exitCode) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              var isScan = command.arguments().contains("-read_intervals");
              return ScriptedProcess.builder()
                  .output(isScan ? new byte[0] : ffprobeOutput.getBytes(StandardCharsets.UTF_8))
                  .exitCode(isScan ? exitCode : 0)
                  .build();
            });
    return executor.probe(SOURCE, request()).result();
  }

  private static ProbeAttemptResult probeResult(String ffprobeOutput, String packetLines) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                ScriptedProcess.builder()
                    .output(
                        switch (command.tool()) {
                          case FFPROBE ->
                              (command.arguments().contains("-read_intervals")
                                      ? packetLines
                                      : answer(ffprobeOutput, command.arguments()))
                                  .getBytes(StandardCharsets.UTF_8);
                          case FFMPEG -> new byte[0];
                        })
                    .build());
    return executor.probe(SOURCE, request()).result();
  }

  /**
   * What ffprobe prints for the arguments, given its output with -show_data for every stream:
   * extradata only with -show_data, and with -select_streams only the index and extradata of the
   * selected streams. V selects the video streams that are not attached pictures, and a the audio
   * streams. Output that is not JSON comes back unchanged.
   */
  private static String answer(String recorded, List<String> arguments) {
    JsonNode output;
    try {
      output = MAPPER.readTree(recorded);
    } catch (JacksonException _) {
      return recorded;
    }

    if (!(output instanceof ObjectNode answer)) {
      return recorded;
    }

    var selector = arguments.indexOf("-select_streams");
    if (selector < 0) {
      return withoutExtradataUnlessShown(answer, arguments.contains("-show_data")).toString();
    }

    var selected = MAPPER.createArrayNode();
    answer
        .path("streams")
        .valueStream()
        .filter(stream -> isSelected(stream, arguments.get(selector + 1)))
        .forEach(
            stream ->
                selected
                    .addObject()
                    .put("index", stream.path("index").asInt())
                    .setAll(
                        stream.has("extradata")
                            ? MAPPER
                                .createObjectNode()
                                .put("extradata", stream.path("extradata").asString())
                            : MAPPER.createObjectNode()));
    return MAPPER.createObjectNode().set("streams", selected).toString();
  }

  private static ObjectNode withoutExtradataUnlessShown(ObjectNode output, boolean shown) {
    if (!shown) {
      output.path("streams").forEach(stream -> ((ObjectNode) stream).remove("extradata"));
    }

    return output;
  }

  private static boolean isSelected(JsonNode stream, String selector) {
    var type = stream.path("codec_type").asString("");
    return switch (selector) {
      case "V" ->
          "video".equals(type) && stream.path("disposition").path("attached_pic").asInt() != 1;
      case "a" -> "audio".equals(type);
      default -> throw new AssertionError("an unexpected stream selector " + selector);
    };
  }

  /**
   * The frame-rate scan that the pinned worker image recorded into {@code
   * src/test/resources/probe/packets}; no packets when the fixture has no recorded scan.
   */
  static String packets(String fixture) {
    try (InputStream recorded =
        RecordedFfprobe.class.getResourceAsStream("/probe/packets/" + fixture + ".csv")) {
      return recorded == null ? "" : new String(recorded.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String fixture(String fixture) {
    try (InputStream recorded =
        RecordedFfprobe.class.getResourceAsStream("/probe/" + fixture + ".json")) {
      assertThat(recorded).as("recorded fixture " + fixture).isNotNull();
      return new String(recorded.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setScanKeyframePackets(true)
        .build();
  }
}
