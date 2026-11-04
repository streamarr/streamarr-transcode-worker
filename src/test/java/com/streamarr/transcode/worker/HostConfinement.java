package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.worker.LandlockAvailability.Available;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The confinement the smoke tests run real FFmpeg tools under: Landlock on Linux, as the worker
 * does, so every smoke test proves that the worker's policy lets normal probes and jobs run. The
 * tools stay unconfined on another operating system.
 */
final class HostConfinement {

  static final String LANDLOCK_EXEC = "landlock-exec";

  private HostConfinement() {}

  /**
   * The host's confinement for tools reading the given media roots. Fixtures under the JVM's
   * temporary directory need no root of their own, since the policy lets the tools write there.
   */
  static UnaryOperator<ProcessBuilder> forMedia(Path... mediaRoots) {
    if (!isLinux()) {
      return UnaryOperator.identity();
    }

    return landlock(policy(List.of(mediaRoots), Path.of(System.getProperty("java.io.tmpdir"))));
  }

  static boolean isLinux() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
  }

  static LandlockConfinement landlock(LandlockPolicy policy) {
    try {
      assertThat(LandlockAvailability.check(LANDLOCK_EXEC))
          .as(
              "the smoke tests need landlock-exec on PATH and Landlock in the kernel; compile the"
                  + " helper with buildpacks/landlock/bin/compile")
          .isInstanceOf(Available.class);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }

    return new LandlockConfinement(LANDLOCK_EXEC, policy);
  }

  static LandlockPolicy policy(List<Path> mediaRoots, Path temporaryDirectory) {
    return LandlockPolicy.forHost(
        LandlockPolicy.Host.builder()
            .mediaRoots(mediaRoots)
            .tools(List.of("ffmpeg", "ffprobe"))
            .searchPath(System.getenv().getOrDefault("PATH", ""))
            .temporaryDirectory(temporaryDirectory)
            .deviceDirectory(Path.of("/dev"))
            .build());
  }
}
