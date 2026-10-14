package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.Duration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@Slf4j
public final class FfprobeExecutor {

  public static final int PROBE_VERSION = 1;

  /** The probe version whose results carry keyframe positions. */
  public static final int KEYFRAME_POSITIONS_PROBE_VERSION = 3;

  private static final int AVERROR_INVALIDDATA = -1094995529;
  private static final int AVERROR_EOF = -541478725;

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final Function<List<String>, Process> processFactory;

  public static FfprobeExecutor forBinary(@NonNull Path binary) {
    return new FfprobeExecutor(new ObjectMapper(), arguments -> start(binary, arguments));
  }

  private static Process start(Path binary, List<String> arguments) {
    var command = new ArrayList<String>();
    command.add(binary.toString());
    command.addAll(arguments);
    try {
      return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start ffprobe", e);
    }
  }

  public ProbeAttemptReport probe(Path source, ProbeRequest request) {
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion());
    if (!isSupported(request)) {
      return ProbeAttemptReport.resultOnly(
          result.setFailure(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION).build());
    }

    if (Thread.currentThread().isInterrupted()) {
      return ProbeAttemptReport.resultOnly(
          result.setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED).build());
    }

    var attempt = new Attempt(source, request, result);
    try {
      return execute(attempt);
    } catch (InterruptedException | ClosedByInterruptException _) {
      // An interrupt closes a FileChannel that the keyframe step is reading.
      Thread.currentThread().interrupt();
      return ProbeAttemptReport.resultOnly(
          result.setFailure(ProbeFailure.PROBE_FAILURE_CANCELLED).build());
    } catch (Exception failure) {
      log.warn(
          "ffprobe attempt {} failed for source {}",
          fromProto(request.getProbeAttemptId()),
          source,
          failure);
      return ProbeAttemptReport.resultOnly(
          result.setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED).build());
    }
  }

  private boolean isSupported(ProbeRequest request) {
    if (request.getProbeVersion() == KEYFRAME_POSITIONS_PROBE_VERSION) {
      return true;
    }

    return request.getProbeVersion() == PROBE_VERSION && !request.getScanKeyframePackets();
  }

  private ProbeAttemptReport execute(Attempt attempt)
      throws ExecutionException, InterruptedException, IOException {
    var output =
        run(
            List.of(
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-show_streams",
                "-show_format",
                "-show_error",
                attempt.source().toString()));
    JsonNode json;
    try {
      json = objectMapper.readTree(output.stdout());
    } catch (RuntimeException failure) {
      return ProbeAttemptReport.resultOnly(unusableOutput(output, attempt, failure));
    }

    var result = readResult(json, output, attempt);
    if (attempt.request().getProbeVersion() != KEYFRAME_POSITIONS_PROBE_VERSION
        || !result.hasMedia()) {
      return ProbeAttemptReport.resultOnly(result);
    }

    var probed =
        ProbedSource.builder().source(attempt.source()).ffprobeOutput(json).result(result).build();
    if (attempt.request().getScanKeyframePackets()) {
      return new KeyframePacketScan(this::runLines).addKeyframes(probed);
    }

    return new ContainerIndexProbe(objectMapper, this::run).addKeyframes(probed);
  }

  private ProcessOutput run(List<String> arguments)
      throws ExecutionException, InterruptedException {
    var finished = run(arguments, InputStream::readAllBytes);
    return new ProcessOutput(finished.output(), finished.exitCode());
  }

  private int runLines(List<String> arguments, Consumer<String> lines)
      throws ExecutionException, InterruptedException {
    StdoutReader<Void> reader =
        stdout -> {
          new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))
              .lines()
              .forEach(lines);
          return null;
        };
    return run(arguments, reader).exitCode();
  }

  private <T> Finished<T> run(List<String> arguments, StdoutReader<T> reader)
      throws ExecutionException, InterruptedException {
    var process = processFactory.apply(arguments);
    try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = readers.submit(() -> readOutput(process, reader));
      try {
        var exitCode = process.waitFor();
        return new Finished<>(output.get(), exitCode);
      } finally {
        if (process.isAlive()) {
          terminate(process);
        }
      }
    }
  }

  private <T> T readOutput(Process process, StdoutReader<T> reader) throws IOException {
    try (var output = process.getInputStream()) {
      return reader.read(output);
    }
  }

  private void terminate(Process process) {
    process.destroyForcibly();
    process.onExit().join();
  }

  private ProbeAttemptResult readResult(JsonNode json, ProcessOutput output, Attempt attempt) {
    try {
      return interpret(json, output.exitCode(), attempt);
    } catch (Exception failure) {
      return unusableOutput(output, attempt, failure);
    }
  }

  private ProbeAttemptResult unusableOutput(
      ProcessOutput output, Attempt attempt, Exception failure) {
    log.warn(
        "ffprobe attempt {} for source {} returned unusable output with exit code {}",
        fromProto(attempt.result().getProbeAttemptId()),
        attempt.source(),
        output.exitCode(),
        failure);
    return attempt.result().setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED).build();
  }

  private ProbeAttemptResult interpret(JsonNode json, int exitCode, Attempt attempt) {
    var result = attempt.result();
    if (json == null || !json.isObject()) {
      throw new IllegalArgumentException("ffprobe returned no result object");
    }

    if (exitCode != 0) {
      var error = json.path("error");
      var errorCode = error.path("code").asInt();
      log.warn(
          "ffprobe attempt {} for source {} exited with code {}: error {} ({})",
          fromProto(result.getProbeAttemptId()),
          attempt.source(),
          exitCode,
          errorCode,
          error.path("string").asString());
      return result.setFailure(failureFor(errorCode)).build();
    }

    var media = parseMedia(json);
    if (media.getStreamsList().stream()
        .noneMatch(stream -> "video".equals(stream.getCodecType()))) {
      return result.setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM).build();
    }

    return result.setMedia(media).build();
  }

  private ProbeFailure failureFor(int errorCode) {
    return switch (errorCode) {
      case AVERROR_INVALIDDATA, AVERROR_EOF -> ProbeFailure.PROBE_FAILURE_INVALID_MEDIA;
      // FFmpeg returns negative ENOENT, EIO, EACCES, or ENOTDIR for these source failures.
      case -2, -5, -13, -20 -> ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE;
      default -> ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED;
    };
  }

  private ProbeMediaInfo parseMedia(JsonNode root) {
    var container = ProbeContainerInfo.newBuilder();
    var format = root.path("format");
    text(format, "format_name").ifPresent(container::setFormat);
    number(format, "bit_rate", Long::parseLong).ifPresent(container::setBitrateBitsPerSecond);
    number(format, "duration", Double::parseDouble)
        .filter(value -> Double.isFinite(value) && value >= 0)
        .map(this::duration)
        .ifPresent(container::setDuration);
    var media = ProbeMediaInfo.newBuilder().setContainer(container);
    var streams = root.path("streams");
    if (!streams.isMissingNode() && !streams.isNull() && !streams.isArray()) {
      throw new IllegalArgumentException("ffprobe returned a malformed stream list");
    }

    for (var stream : streams) {
      media.addStreams(parseStream(stream, media.getStreamsCount()));
    }

    return media.build();
  }

  private ProbeStreamInfo parseStream(JsonNode source, int position) {
    var type =
        Optional.ofNullable(source.get("codec_type"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::asString)
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () -> new IllegalArgumentException("ffprobe stream is missing codec_type"));
    var stream =
        ProbeStreamInfo.newBuilder()
            .setIndex(text(source, "index").map(Integer::parseInt).orElse(position))
            .setCodecType(type)
            .setIsDefault(source.path("disposition").path("default").asInt() == 1)
            .setIsForced(source.path("disposition").path("forced").asInt() == 1);
    text(source, "codec_name").ifPresent(stream::setCodec);
    text(source.path("tags"), "language").ifPresent(stream::setLanguage);
    number(source, "bit_rate", Long::parseLong).ifPresent(stream::setBitrateBitsPerSecond);
    number(source, "width", Integer::parseInt).ifPresent(stream::setWidth);
    number(source, "height", Integer::parseInt).ifPresent(stream::setHeight);
    number(source, "r_frame_rate", this::framerate)
        .filter(value -> Double.isFinite(value) && value > 0)
        .ifPresent(stream::setFramerate);
    if ("audio".equals(type)) {
      number(source, "channels", Integer::parseInt).ifPresent(stream::setChannels);
    }

    return stream.build();
  }

  private Optional<String> text(JsonNode parent, String name) {
    return Optional.ofNullable(parent.get(name))
        .filter(value -> !value.isNull())
        .map(JsonNode::asString);
  }

  private double framerate(String value) {
    var parts = value.split("/");
    return parts.length == 2
        ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
        : Double.parseDouble(value);
  }

  private <T> Optional<T> number(JsonNode parent, String name, Function<String, T> parser) {
    try {
      return text(parent, name).map(parser);
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  private Duration duration(double seconds) {
    var millis = (long) (seconds * 1000);
    return Duration.newBuilder()
        .setSeconds(millis / 1000)
        .setNanos((int) (millis % 1000) * 1_000_000)
        .build();
  }

  private record Attempt(Path source, ProbeRequest request, ProbeAttemptResult.Builder result) {}

  @FunctionalInterface
  private interface StdoutReader<T> {
    T read(InputStream stdout) throws IOException;
  }

  private record Finished<T>(T output, int exitCode) {}
}
