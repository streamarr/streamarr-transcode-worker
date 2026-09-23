package com.streamarr.transcode.fixtures;

import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegProcessManager;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

public final class RemoteWorkerFixtures {

  public static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  public static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private RemoteWorkerFixtures() {}

  public static TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder
      workerConfigurationBuilder() {
    return TranscodeWorkerConfiguration.builder().workerId(WORKER_ID).bootId(UUID.randomUUID());
  }

  public static FfmpegTranscodeEngine remuxEngine(FfmpegProcessManager processManager) {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command ->
                new CompatibleFfmpegProcess(
                    Arrays.asList(command).contains("muxer=hls") ? "hls_segment_options" : ""));
    capabilityService.detectCapabilities();

    return new FfmpegTranscodeEngine(
        new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)),
        processManager,
        capabilityService);
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
