package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.LocalFfmpegProcessManager;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.probe.FfprobeExecutor;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;

@SpringBootApplication(proxyBeanMethods = false)
public class TranscodeWorkerApplication {

  public static void main(String[] args) throws InterruptedException {
    // Settings parse path-bearing variables, which fail under a non-UTF-8 locale.
    new NativeFilenameEncodingCheck(System.getProperty("sun.jnu.encoding", ""), System.getenv())
        .warnUnlessUtf8();
    try (var application = SpringApplication.run(TranscodeWorkerApplication.class, args)) {
      application.getBean(TranscodeWorker.class).awaitDisconnection();
    } catch (RuntimeException failure) {
      if (NestedExceptionUtils.getMostSpecificCause(failure)
          instanceof InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw interruption;
      }

      throw failure;
    }
  }

  @Bean
  TranscodeWorkerSettings workerSettings() {
    return TranscodeWorkerSettings.fromEnvironment(System.getenv());
  }

  @Bean(destroyMethod = "close")
  TranscodeWorker transcodeWorker(TranscodeWorkerSettings settings)
      throws IOException, InterruptedException {
    var capabilities =
        new TranscodeCapabilityService(
            settings.ffmpegPath(), command -> new ProcessBuilder(command).start());
    capabilities.detectCapabilities();
    if (!capabilities.isFfmpegAvailable()) {
      throw new IllegalStateException(
          "FFmpeg is not available to the transcode worker: "
              + capabilities.getUnavailableReason());
    }

    requireFfprobe(settings.ffprobePath());
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder(settings.ffmpegPath(), settings.fragmentationTarget()),
            new LocalFfmpegProcessManager(),
            capabilities);
    var ffprobe = FfprobeExecutor.forBinary(Path.of(settings.ffprobePath()));
    return new TranscodeWorker(settings.workerConfiguration(), engine, ffprobe);
  }

  @Bean
  ApplicationRunner workerConnection(TranscodeWorker worker, TranscodeWorkerSettings settings) {
    return _ -> worker.start(settings.controlPlaneHost(), settings.controlPlanePort());
  }

  private static void requireFfprobe(String ffprobePath) throws IOException, InterruptedException {
    Process process;
    try {
      process =
          new ProcessBuilder(ffprobePath, "-version")
              .redirectOutput(Redirect.DISCARD)
              .redirectError(Redirect.DISCARD)
              .start();
    } catch (IOException exception) {
      throw new IOException(
          "ffprobe is not available to the transcode worker: " + ffprobePath, exception);
    }

    try {
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(
            "ffprobe is not available to the transcode worker: "
                + ffprobePath
                + " exited with code "
                + exitCode);
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      process.onExit().join();
      Thread.currentThread().interrupt();
      throw exception;
    }
  }
}
