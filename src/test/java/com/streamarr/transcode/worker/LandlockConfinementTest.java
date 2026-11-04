package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Landlock Confinement Tests")
class LandlockConfinementTest {

  private static final LandlockPolicy POLICY =
      new LandlockPolicy(
          List.of(Path.of("/media"), Path.of("/etc")),
          List.of(Path.of("/usr"), Path.of("/layers/ffmpeg/bin")),
          Path.of("/tmp"),
          List.of(Path.of("/dev/dri"), Path.of("/dev/nvidiactl")));

  private final LandlockConfinement confinement = new LandlockConfinement("landlock-exec", POLICY);

  @Test
  @DisplayName("Should run the tool through landlock-exec with one rule per path when applied")
  void shouldRunTheToolThroughLandlockExecWithOneRulePerPathWhenApplied() {
    var builder = new ProcessBuilder("ffprobe", "-v", "quiet", "/media/movie.mkv");

    var confined = confinement.apply(builder);

    assertThat(confined.command())
        .containsExactly(
            "landlock-exec",
            "--read",
            "/media",
            "--read",
            "/etc",
            "--read-execute",
            "/usr",
            "--read-execute",
            "/layers/ffmpeg/bin",
            "--read-write",
            "/tmp",
            "--device",
            "/dev/dri",
            "--device",
            "/dev/nvidiactl",
            "--",
            "ffprobe",
            "-v",
            "quiet",
            "/media/movie.mkv");
  }

  @Test
  @DisplayName("Should point the tool's caches at the temporary directory when applied")
  void shouldPointTheToolsCachesAtTheTemporaryDirectoryWhenApplied() {
    var confined = confinement.apply(new ProcessBuilder("ffmpeg"));

    assertThat(confined.environment())
        .containsEntry("TMPDIR", "/tmp")
        .containsEntry("XDG_CACHE_HOME", "/tmp")
        .containsEntry("CUDA_CACHE_PATH", "/tmp");
  }

  @Test
  @DisplayName("Should keep the builder's own settings when applied")
  void shouldKeepTheBuildersOwnSettingsWhenApplied() {
    var builder = new ProcessBuilder("ffmpeg").redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("STREAMARR_JOB_ATTEMPT_ID", "attempt");

    var confined = confinement.apply(builder);

    assertThat(confined).isSameAs(builder);
    assertThat(confined.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
    assertThat(confined.environment()).containsEntry("STREAMARR_JOB_ATTEMPT_ID", "attempt");
  }

  @Test
  @DisplayName(
      "Should name the configured helper path when landlock-exec is not on the search path")
  void shouldNameTheConfiguredHelperPathWhenLandlockExecIsNotOnTheSearchPath() {
    var confined =
        new LandlockConfinement("/opt/landlock/landlock-exec", POLICY)
            .apply(new ProcessBuilder("ffmpeg"));

    assertThat(confined.command()).startsWith("/opt/landlock/landlock-exec", "--read", "/media");
  }
}
