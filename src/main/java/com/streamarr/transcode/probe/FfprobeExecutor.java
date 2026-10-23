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
  public static final int PROBE_VERSION = 4;

  /**
   * The read limits let ffprobe reach streams whose first packet comes late, such as PGS subtitles.
   * Without -scan_all_pmts 0, ffprobe reads an MPEG-TS source to those limits.
   */
  static final List<String> READ_LIMITS =
      List.of("-analyzeduration", "200M", "-probesize", "1G", "-scan_all_pmts", "0");

  private static final List<String> LOWEST_CPU_PRIORITY = List.of("nice", "-n", "19");

  // 30 s of fields at the 1000 fps limit of a usable rate, twice over.
  private static final int MAXIMUM_SCANNED_PACKETS = 120_000;

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

  // A process needs no privilege to lower its own priority, so nice works in every container.
  private static Process start(Path binary, ProbeCommand probeCommand) {
    var command = new ArrayList<String>();
    if (probeCommand.cpuPriority() == ProbeCommand.CpuPriority.LOWEST) {
      command.addAll(LOWEST_CPU_PRIORITY);
    }

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
      return atCpuPriorityOf(request).execute(attempt);
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

  // Every process of a probe that no playback request waits for starts at the lowest priority.
  private FfprobeExecutor atCpuPriorityOf(ProbeRequest request) {
    if (ProbeKind.of(request) == ProbeKind.PLAYBACK) {
      return this;
    }

    return new FfprobeExecutor(
        objectMapper, command -> processFactory.apply(command.atLowestCpuPriority()));
  }

  private ProbeAttemptReport execute(Attempt attempt)
      throws ExecutionException, InterruptedException, IOException {
    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(READ_LIMITS);
    arguments.addAll(
        List.of(
            "-show_streams",
            "-show_format",
            "-show_chapters",
            "-show_error",
            attempt.source().toString()));
    var output = run(arguments);
    JsonNode json;
    try {
      json = objectMapper.readTree(output.stdout());
    } catch (RuntimeException failure) {
      return ProbeAttemptReport.resultOnly(unusableOutput(output, attempt, failure));
    }

    if (output.exitCode() == 0) {
      new StreamExtradataProbe(objectMapper, this::run).addTo(json, attempt.source());
    }

    var result = readResult(json, output, attempt);
    if (!result.hasMedia()) {
      return ProbeAttemptReport.resultOnly(result);
    }

    var chosen = ChosenVideoStream.find(json.path("streams"));
    if (chosen.isPresent()) {
      var frameRate =
          new FrameRateRule(streamIndex -> packetTimings(attempt.source(), streamIndex))
              .choose(chosen.get());
      result =
          result.toBuilder()
              .setMedia(result.getMedia().toBuilder().setFrameRateRule(frameRate))
              .build();
    }

    var probed =
        ProbedSource.builder().source(attempt.source()).ffprobeOutput(json).result(result).build();
    if (attempt.request().getScanKeyframePackets()) {
      return new KeyframePacketScan(this::runPacketScan).addKeyframes(probed);
    }

    return new ContainerIndexProbe(objectMapper, this::run).addKeyframes(probed);
  }

  /**
   * ffprobe's packet lines, pts,dts,size,flags, for the first 30 s of one stream, and at most
   * 120,000 of them. The read interval ends only when the timestamps pass 30 s, so a stream whose
   * timestamps stop advancing would otherwise print every packet in the file.
   */
  private List<String> packetTimings(Path source, int streamIndex)
      throws ExecutionException, InterruptedException {
    var arguments =
        List.of(
            "-v",
            "error",
            "-select_streams",
            String.valueOf(streamIndex),
            "-read_intervals",
            "%+30",
            "-show_entries",
            "packet=pts,dts,size,flags",
            "-of",
            "csv=p=0",
            source.toString());
    var process = processFactory.apply(new ProbeCommand(ProbeTool.FFPROBE, arguments));
    var lines = new ArrayList<String>();
    var stopped = new AtomicBoolean();
    OutputReader<Void> reader =
        stdout -> {
          KeyframePacketScan.PacketLines take =
              line -> {
                lines.add(line);
                return lines.size() < MAXIMUM_SCANNED_PACKETS;
              };
          if (!readLinesWhileNeeded(stdout, take)) {
            stopped.set(true);
            process.destroyForcibly();
          }

          return null;
        };
    var exitCode = run(process, reader, stderr -> readLines(stderr, _ -> {})).exitCode();
    if (!stopped.get() && exitCode != 0) {
      throw new PacketScanException("ffprobe's frame-rate scan exited with code " + exitCode);
    }

    return lines;
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
      var diagnostics =
          readers.submit(() -> readOutput(process, process.getErrorStream(), diagnosticsReader));
      var output = readers.submit(() -> readOutput(process, process.getInputStream(), reader));
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

  private <T> T readOutput(Process process, InputStream output, OutputReader<T> reader)
      throws IOException {
    var completed = false;
    try {
      T result;
      try (output) {
        result = reader.read(output);
      }

      completed = true;
      return result;
    } finally {
      if (!completed && process.isAlive()) {
        terminate(process);
      }
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
    if (!media.hasChosenVideoStreamIndex()) {
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
