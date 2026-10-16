package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.probe.ProbeCommand.ProbeTool;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** The only probe version that this worker answers. */
  public static final int PROBE_VERSION = 3;

  private static final int AVERROR_INVALIDDATA = -1094995529;
  private static final int AVERROR_EOF = -541478725;

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final Function<ProbeCommand, Process> processFactory;

  public static FfprobeExecutor forBinaries(@NonNull Path ffprobe, @NonNull Path ffmpeg) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command ->
            start(
                switch (command.tool()) {
                  case FFPROBE -> ffprobe;
                  case FFMPEG -> ffmpeg;
                },
                command));
  }

  private static Process start(Path binary, ProbeCommand probeCommand) {
    var command = new ArrayList<String>();
    command.add(binary.toString());
    command.addAll(probeCommand.arguments());
    // The packet scan reads FFmpeg's diagnostics to find a source that stopped being readable.
    var diagnostics =
        switch (probeCommand.tool()) {
          case FFPROBE -> ProcessBuilder.Redirect.DISCARD;
          case FFMPEG -> ProcessBuilder.Redirect.PIPE;
        };
    try {
      return new ProcessBuilder(command).redirectError(diagnostics).start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start " + binary, e);
    }
  }

  public ProbeAttemptReport probe(Path source, ProbeRequest request) {
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion());
    if (request.getProbeVersion() != PROBE_VERSION) {
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

  private ProbeAttemptReport execute(Attempt attempt)
      throws ExecutionException, InterruptedException, IOException {
    // The read limits let ffprobe reach streams whose first packet comes late, such as PGS
    // subtitles. Without -scan_all_pmts 0, ffprobe reads an MPEG-TS source to those limits.
    var output =
        run(
            List.of(
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-analyzeduration",
                "200M",
                "-probesize",
                "1G",
                "-scan_all_pmts",
                "0",
                "-show_streams",
                "-show_format",
                "-show_chapters",
                "-show_error",
                attempt.source().toString()));
    JsonNode json;
    try {
      json = objectMapper.readTree(output.stdout());
    } catch (RuntimeException failure) {
      return ProbeAttemptReport.resultOnly(unusableOutput(output, attempt, failure));
    }

    var result = readResult(json, output, attempt);
    if (!result.hasMedia()) {
      return ProbeAttemptReport.resultOnly(result);
    }

    var probed =
        ProbedSource.builder().source(attempt.source()).ffprobeOutput(json).result(result).build();
    if (attempt.request().getScanKeyframePackets()) {
      return new KeyframePacketScan(this::runPacketScan).addKeyframes(probed);
    }

    return new ContainerIndexProbe(objectMapper, this::run).addKeyframes(probed);
  }

  private ProcessOutput run(List<String> arguments)
      throws ExecutionException, InterruptedException {
    var finished = run(arguments, InputStream::readAllBytes);
    return new ProcessOutput(finished.output(), finished.exitCode());
  }

  private int runPacketScan(
      List<String> arguments,
      KeyframePacketScan.PacketLines packetLines,
      Consumer<String> diagnosticLines)
      throws ExecutionException, InterruptedException {
    var process = processFactory.apply(new ProbeCommand(ProbeTool.FFMPEG, arguments));
    var stopped = new AtomicBoolean();
    OutputReader<Void> reader =
        stdout -> {
          if (!readLinesWhileNeeded(stdout, packetLines)) {
            // The scan needs no more packets, so FFmpeg stops before it reads the rest of the
            // source.
            stopped.set(true);
            process.destroyForcibly();
          }

          return null;
        };
    OutputReader<Void> diagnosticsReader =
        stderr -> {
          try {
            return readLines(stderr, diagnosticLines);
          } catch (IOException | UncheckedIOException failure) {
            // Destroying a process closes its standard error, so the read fails once the scan
            // has stopped FFmpeg.
            if (stopped.get()) {
              return null;
            }

            throw failure;
          }
        };
    return run(process, reader, diagnosticsReader).exitCode();
  }

  private <T> Finished<T> run(List<String> arguments, OutputReader<T> reader)
      throws ExecutionException, InterruptedException {
    var process = processFactory.apply(new ProbeCommand(ProbeTool.FFPROBE, arguments));
    return run(process, reader, stderr -> readLines(stderr, _ -> {}));
  }

  private <T> Finished<T> run(
      Process process, OutputReader<T> reader, OutputReader<Void> diagnosticsReader)
      throws ExecutionException, InterruptedException {
    try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var diagnostics = readers.submit(() -> diagnosticsReader.read(process.getErrorStream()));
      var output = readers.submit(() -> readOutput(process, reader));
      try {
        var exitCode = process.waitFor();
        var finished = new Finished<>(output.get(), exitCode);
        diagnostics.get();
        return finished;
      } finally {
        if (process.isAlive()) {
          terminate(process);
        }
      }
    }
  }

  private static boolean readLinesWhileNeeded(
      InputStream stream, KeyframePacketScan.PacketLines lines) throws IOException {
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      for (var line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!lines.take(line)) {
          return false;
        }
      }
    }

    return true;
  }

  private static Void readLines(InputStream stream, Consumer<String> lines) throws IOException {
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      reader.lines().forEach(lines);
    }

    return null;
  }

  private <T> T readOutput(Process process, OutputReader<T> reader) throws IOException {
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

    var media = ProbeMediaReader.read(json);
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

  private record Attempt(Path source, ProbeRequest request, ProbeAttemptResult.Builder result) {}

  @FunctionalInterface
  private interface OutputReader<T> {
    T read(InputStream output) throws IOException;
  }

  private record Finished<T>(T output, int exitCode) {}
}
