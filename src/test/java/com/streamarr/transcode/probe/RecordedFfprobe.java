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
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import lombok.Builder;
import lombok.NonNull;
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
    return probe(
        Answers.builder()
            .ffprobeOutput(fixture(fixture))
            .packetLines(packets(fixture))
            .firstFrames(recordedFirstFrames(fixture))
            .firstPackets(recordedFirstPackets(fixture))
            .build());
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
    return probe(Answers.builder().ffprobeOutput(ffprobeOutput).packetLines(packetLines).build());
  }

  /** Probes with the answers that the scripted ffprobe gives, and expects the probe to succeed. */
  static ProbeMediaInfo probe(Answers answers) {
    var result = probeResult(answers);
    assertThat(result.hasMedia()).as("the probe succeeds").isTrue();
    return result.getMedia();
  }

  /**
   * What the scripted ffprobe prints. The first-frames read prints {@code firstFrames}, or fails
   * when the answers hold none, so that the probe reads no frames.
   */
  @Builder
  record Answers(
      @NonNull String ffprobeOutput,
      String packetLines,
      Optional<String> firstFrames,
      Optional<String> firstPackets) {

    Answers {
      packetLines = packetLines == null ? "" : packetLines;
      firstFrames = firstFrames == null ? Optional.empty() : firstFrames;
      firstPackets = firstPackets == null ? Optional.empty() : firstPackets;
    }

    static final class AnswersBuilder {
      AnswersBuilder firstFrames(String output) {
        this.firstFrames = Optional.of(output);
        return this;
      }

      private AnswersBuilder firstFrames(Optional<String> output) {
        this.firstFrames = output;
        return this;
      }

      /** What the first-packets read prints; without it, the read fails. */
      AnswersBuilder firstPackets(String output) {
        this.firstPackets = Optional.of(output);
        return this;
      }

      private AnswersBuilder firstPackets(Optional<String> output) {
        this.firstPackets = output;
        return this;
      }
    }
  }

  static ProbeAttemptResult probeResult(String ffprobeOutput) {
    return probeResult(Answers.builder().ffprobeOutput(ffprobeOutput).build());
  }

  /** Probes with the given ffprobe output while the frame-rate scan prints packets forever. */
  static ProbeAttemptResult probeResultWithEndlessScan(
      String ffprobeOutput, IntFunction<String> packetLines) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              if (isFrameRateScan(command.arguments())) {
                return new EndlessOutputProcess(packetLines);
              }

              if (isFirstFramesRead(command.arguments())) {
                return ScriptedProcess.builder().output(new byte[0]).exitCode(1).build();
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
              var isScan = isFrameRateScan(command.arguments());
              return ScriptedProcess.builder()
                  .output(isScan ? new byte[0] : ffprobeOutput.getBytes(StandardCharsets.UTF_8))
                  .exitCode(isScan ? exitCode : 0)
                  .build();
            });
    return executor.probe(SOURCE, request()).result();
  }

  private static ProbeAttemptResult probeResult(Answers answers) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
                return ScriptedProcess.builder().output(new byte[0]).build();
              }

              if (isFirstPacketsRead(command.arguments())) {
                return scripted(answers.firstPackets());
              }

              if (isFirstFramesRead(command.arguments())) {
                return answers
                    .firstFrames()
                    .map(
                        frames ->
                            ScriptedProcess.builder()
                                .output(frames.getBytes(StandardCharsets.UTF_8))
                                .build())
                    .orElseGet(
                        () -> ScriptedProcess.builder().output(new byte[0]).exitCode(1).build());
              }

              var output =
                  isFrameRateScan(command.arguments())
                      ? answers.packetLines()
                      : answer(answers.ffprobeOutput(), command.arguments());
              return ScriptedProcess.builder()
                  .output(output.getBytes(StandardCharsets.UTF_8))
                  .build();
            });
    return executor.probe(SOURCE, request()).result();
  }

  private static boolean isFrameRateScan(List<String> arguments) {
    return arguments.contains("packet=pts,dts,size,flags");
  }

  private static Process scripted(Optional<String> output) {
    return output
        .map(printed -> ScriptedProcess.builder().output(printed.getBytes(StandardCharsets.UTF_8)))
        .orElseGet(() -> ScriptedProcess.builder().output(new byte[0]).exitCode(1))
        .build();
  }

  private static boolean isFirstPacketsRead(List<String> arguments) {
    return arguments.contains("packet=data");
  }

  private static boolean isFirstFramesRead(List<String> arguments) {
    return arguments.stream().anyMatch(argument -> argument.startsWith("frame="));
  }

  /**
   * The first-packets read that the pinned worker image recorded into {@code
   * src/test/resources/probe/packet-data}; none when the fixture has no recorded read.
   */
  static Optional<String> recordedFirstPackets(String fixture) {
    try (InputStream recorded =
        RecordedFfprobe.class.getResourceAsStream("/probe/packet-data/" + fixture + ".json")) {
      return recorded == null
          ? Optional.empty()
          : Optional.of(new String(recorded.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The first-frames read that the pinned worker image recorded into {@code
   * src/test/resources/probe/frames}; none when the fixture has no recorded read.
   */
  static Optional<String> recordedFirstFrames(String fixture) {
    try (InputStream recorded =
        RecordedFfprobe.class.getResourceAsStream("/probe/frames/" + fixture + ".json")) {
      return recorded == null
          ? Optional.empty()
          : Optional.of(new String(recorded.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  /**
   * The bytes as ffprobe's -show_data prints them, escaped for a JSON string: each line holds an
   * offset, up to 16 bytes in groups of two, and the bytes as ASCII after column 51.
   */
  static String ffprobeDump(byte[] bytes) {
    var dump = new StringBuilder("\\n");
    for (var offset = 0; offset < bytes.length; offset += 16) {
      var line = new StringBuilder("%08x: ".formatted(offset));
      var end = Math.min(offset + 16, bytes.length);
      for (var index = offset; index < end; index++) {
        line.append("%02x".formatted(bytes[index]));
        if ((index - offset) % 2 == 1) {
          line.append(' ');
        }
      }

      line.append(" ".repeat(51 - line.length())).append(".".repeat(end - offset));
      dump.append(line).append("\\n");
    }

    return dump.toString();
  }
}
