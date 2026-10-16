package com.streamarr.transcode.fixtures;

import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.ProcessLauncher;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class RemoteWorkerFixtures {

  public static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  public static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private RemoteWorkerFixtures() {}

  public static TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder
      workerConfigurationBuilder() {
    return TranscodeWorkerConfiguration.builder()
        .workerId(WORKER_ID)
        .bootId(UUID.randomUUID())
        .probeCapacity(4);
  }

  /** An engine with a compatible FFmpeg that launches FFmpeg through the launcher. */
  public static FfmpegTranscodeEngine engine(ProcessLauncher launcher) {
    return engineBuilder(launcher).build();
  }

  /** A builder for an engine with a compatible FFmpeg that launches FFmpeg through the launcher. */
  public static FfmpegTranscodeEngine.FfmpegTranscodeEngineBuilder engineBuilder(
      ProcessLauncher launcher) {
    return engineBuilder(launcher, "");
  }

  /**
   * A builder for an engine whose compatible FFmpeg lists the encoders in {@code encodersOutput},
   * in the format of {@code ffmpeg -encoders}, and passes every test encode.
   */
  public static FfmpegTranscodeEngine.FfmpegTranscodeEngineBuilder engineBuilder(
      ProcessLauncher launcher, String encodersOutput) {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg", command -> new CompatibleFfmpegProcess(stdoutOf(command, encodersOutput)));
    capabilityService.detectCapabilities();

    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(capabilityService)
        .launcher(launcher)
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT);
  }

  private static String stdoutOf(ProcessBuilder command, String encodersOutput) {
    var arguments = command.command();
    if (arguments.contains("muxer=mp4")) {
      return FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
    }

    if (arguments.contains("-encoders")) {
      return encodersOutput;
    }

    return "";
  }

  private static class CompatibleFfmpegProcess extends Process {

    private final InputStream inputStream;

    CompatibleFfmpegProcess(String stdout) {
      inputStream = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      // no-op for test fake
    }
  }
}
