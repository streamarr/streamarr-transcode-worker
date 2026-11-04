package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.probe.FfprobeExecutor;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;

@Slf4j
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
    var settings = TranscodeWorkerSettings.fromEnvironment(System.getenv());
    var worker = settings.workerConfiguration();
    log.info(
        "The worker runs {} job attempts, {} playback probes, {} and one keyframe packet scan"
            + " at once, and no keyframe packet scan while a job attempt runs",
        worker.availableSlots(),
        ProbeCapacities.PLAYBACK_PROBE_CAPACITY,
        switch (worker.backgroundProbeCapacity()) {
          case BackgroundProbeCapacity.Adaptive(var maximum) ->
              "an adaptive number of background probes, starting at "
                  + Math.min(ProbeCapacities.STARTING_BACKGROUND_PROBE_CAPACITY, maximum)
                  + " and kept from 1 to "
                  + maximum;
          case BackgroundProbeCapacity.Fixed(var capacity) ->
              capacity + " background probes, as TRANSCODE_WORKER_PROBE_CAPACITY fixes";
        });
    return settings;
  }

  @Bean(destroyMethod = "close")
  ToolScratchDirectory toolScratchDirectory() throws IOException {
    return ToolScratchDirectory.create(Path.of(System.getProperty("java.io.tmpdir")));
  }

  // Every FFmpeg and ffprobe, the startup checks included, runs confined, so the worker never
  // advertises an encoder that cannot run that way.
  @Bean(destroyMethod = "close")
  TranscodeWorker transcodeWorker(TranscodeWorkerSettings settings, ToolScratchDirectory scratch)
      throws IOException, InterruptedException {
    var confinement = requireLandlock(settings, scratch.getPath());
    var capabilities =
        new TranscodeCapabilityService(
            settings.ffmpegPath(), command -> confinement.apply(command).start());
    capabilities.detectCapabilities();
    if (!capabilities.isFfmpegAvailable()) {
      throw new IllegalStateException(
          "FFmpeg is not available to the transcode worker: "
              + capabilities.getUnavailableReason());
    }

    requireFfprobe(settings.ffprobePath(), confinement);
    var engine =
        FfmpegTranscodeEngine.builder()
            .commandBuilder(
                new FfmpegCommandBuilder(settings.ffmpegPath(), settings.fragmentationTarget()))
            .capabilityService(capabilities)
            .launcher(new ProcessBuilderLauncher(confinement))
            .encoderStallTimeout(settings.encoderStallTimeout())
            .build();
    return TranscodeWorker.builder()
        .configuration(settings.workerConfiguration())
        .engine(engine)
        .ffprobe(
            FfprobeExecutor.forBinaries(
                Path.of(settings.ffprobePath()), Path.of(settings.ffmpegPath()), confinement))
        .clock(MonotonicClock.system())
        .build();
  }

  private static LandlockConfinement requireLandlock(
      TranscodeWorkerSettings settings, Path scratchDirectory) throws InterruptedException {
    var abiVersion =
        switch (LandlockAvailability.check(settings.landlockExecPath())) {
          case LandlockAvailability.Available(var version) -> version;
          case LandlockAvailability.Unavailable(var reason, var action) ->
              throw new LandlockUnavailableException(reason, action);
        };
    var policy =
        LandlockPolicy.forHost(
            LandlockPolicy.Host.builder()
                .mediaRoots(settings.workerConfiguration().sourceNamespaces().values())
                .tools(List.of(settings.ffmpegPath(), settings.ffprobePath()))
                .searchPath(System.getenv().getOrDefault("PATH", ""))
                .temporaryDirectory(scratchDirectory)
                .deviceDirectory(Path.of("/dev"))
                .build());
    log.info(
        "FFmpeg and ffprobe run under Landlock ABI {} and reach only the media roots {}, {}"
            + " read-only, {} read and execute, {} read and write, and the devices {}",
        abiVersion,
        policy.mediaRoots().stream().map(LandlockPolicy.PinnedDirectory::path).toList(),
        policy.readOnly(),
        policy.readExecute(),
        policy.temporaryDirectory(),
        policy.devices());
    return new LandlockConfinement(settings.landlockExecPath(), policy);
  }

  @Bean
  ApplicationRunner workerConnection(TranscodeWorker worker, TranscodeWorkerSettings settings) {
    return _ -> worker.start(settings.controlPlaneHost(), settings.controlPlanePort());
  }

  private static void requireFfprobe(String ffprobePath, UnaryOperator<ProcessBuilder> confinement)
      throws IOException, InterruptedException {
    Process process;
    try {
      process =
          confinement
              .apply(
                  new ProcessBuilder(ffprobePath, "-version")
                      .redirectOutput(Redirect.DISCARD)
                      .redirectError(Redirect.DISCARD))
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
