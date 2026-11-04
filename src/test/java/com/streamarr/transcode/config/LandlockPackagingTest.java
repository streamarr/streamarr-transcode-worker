package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Landlock Packaging Tests")
class LandlockPackagingTest {

  private static final Path IMAGE_BUILD =
      Path.of(".github/actions/pack-build/build-worker-image.sh");
  private static final Path IMAGE_VERIFIER =
      Path.of(".github/actions/pack-build/verify-ffmpeg-image.sh");
  private static final Path CI = Path.of(".github/workflows/ci.yml");

  @ParameterizedTest
  @ValueSource(strings = {"bin/detect", "bin/build", "bin/compile"})
  @DisplayName("Should ship an executable buildpack script when packaging the Landlock helper")
  void shouldShipAnExecutableBuildpackScriptWhenPackagingTheLandlockHelper(String script) {
    assertThat(Path.of("buildpacks/landlock").resolve(script)).isExecutable();
  }

  @Test
  @DisplayName("Should compile the helper from its one source when the buildpack builds")
  void shouldCompileTheHelperFromItsOneSourceWhenTheBuildpackBuilds() throws IOException {
    assertThat(Files.readString(Path.of("buildpacks/landlock/bin/build")))
        .contains("bin/compile\" \"${layer}/bin/landlock-exec\"", "launch = true");
    assertThat(Files.readString(Path.of("buildpacks/landlock/bin/compile")))
        .contains("-static", "src/landlock-exec.c");
    assertThat(Files.readString(Path.of("buildpacks/landlock/buildpack.toml")))
        .contains("id = \"com.streamarr.landlock\"", "arch = \"amd64\"", "arch = \"arm64\"");
  }

  @Test
  @DisplayName("Should add the Landlock buildpack after the FFmpeg one when building the image")
  void shouldAddTheLandlockBuildpackAfterTheFfmpegOneWhenBuildingTheImage() throws IOException {
    var script = Files.readString(IMAGE_BUILD);

    assertThat(script.indexOf("--buildpack ./buildpacks/landlock"))
        .isGreaterThan(script.indexOf("--buildpack ./buildpacks/ffmpeg"));
  }

  @Test
  @DisplayName("Should prove the image confines ffprobe when verifying the media runtime")
  void shouldProveTheImageConfinesFfprobeWhenVerifyingTheMediaRuntime() throws IOException {
    assertThat(Files.readString(IMAGE_VERIFIER))
        .contains(
            "landlock_exec=\"$(command -v landlock-exec)\"",
            "\"${landlock_exec}\" --abi",
            "let ffprobe read a file outside the listed paths");
  }

  @Test
  @DisplayName("Should test the helper's rules when CI verifies the build tooling")
  void shouldTestTheHelpersRulesWhenCiVerifiesTheBuildTooling() throws IOException {
    assertThat(stepNamed(steps("tooling"), "Verify the Landlock helper").get("run").toString())
        .isEqualTo("node --test buildpacks/landlock/test/*.test.mjs");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(String job) throws IOException {
    try (var input = Files.newInputStream(CI)) {
      Map<String, Object> workflow = new Yaml().load(input);
      var jobs = (Map<String, Object>) workflow.get("jobs");
      return (List<Map<String, Object>>) ((Map<String, Object>) jobs.get(job)).get("steps");
    }
  }

  private static Map<String, Object> stepNamed(List<Map<String, Object>> steps, String name) {
    return steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
  }
}
