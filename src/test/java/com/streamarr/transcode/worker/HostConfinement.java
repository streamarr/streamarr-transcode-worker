package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.worker.LandlockAvailability.Available;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

  private static final Path TEMPORARY_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));
  private static ToolScratchDirectory scratch;

  private HostConfinement() {}

  /**
   * The host's confinement for tools reading the given media roots. The JVM's temporary directory
   * is listed as a media root too, since the fixtures live there, and the tools write only in a
   * scratch directory of this test JVM, as they write only in the worker's.
   */
  static UnaryOperator<ProcessBuilder> forMedia(Path... mediaRoots) {
    if (!isLinux()) {
      return UnaryOperator.identity();
    }

    var roots = new ArrayList<Path>();
    roots.add(TEMPORARY_DIRECTORY);
    roots.addAll(List.of(mediaRoots));
    return landlock(policy(roots, scratchDirectory()));
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

  // One scratch directory per test JVM, removed when the JVM exits.
  private static synchronized Path scratchDirectory() {
    if (scratch == null) {
      try {
        scratch = ToolScratchDirectory.create(TEMPORARY_DIRECTORY);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      Runtime.getRuntime().addShutdownHook(new Thread(HostConfinement::removeScratchDirectory));
    }

    return scratch.getPath();
  }

  private static void removeScratchDirectory() {
    try {
      scratch.close();
    } catch (IOException _) {
      // The JVM is exiting; a directory left under the temporary directory harms nothing.
    }
  }
}
