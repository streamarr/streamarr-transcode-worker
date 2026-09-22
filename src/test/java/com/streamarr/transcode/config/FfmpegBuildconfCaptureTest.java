package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Buildconf Capture Tests")
class FfmpegBuildconfCaptureTest {

  private static final Path BUILDPACK = Path.of("buildpacks/ffmpeg");
  private static final Path CAPTURE = BUILDPACK.resolve("bin/capture-buildconf").toAbsolutePath();
  private static final String BUILDCONF =
      """
      ffmpeg version 9.0.0-Jellyfin Copyright (c) 2000-2026 the FFmpeg developers
        configuration: --enable-gpl --enable-libnew
      """;

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @CsvSource({"amd64,linux64", "x86_64,linux64", "arm64,linuxarm64", "aarch64,linuxarm64"})
  @DisplayName("Should capture the build configuration from the banner when the archive verifies")
  void shouldCaptureTheBuildConfigurationFromTheBannerWhenTheArchiveVerifies(
      String architecture, String asset) throws Exception {
    var capture = capture(architecture);

    var result = capture.command().environment("FAKE_LOADER_DIAGNOSTIC", "qemu: notice").execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(capture.output()).hasContent(BUILDCONF);
    assertThat(Files.readString(capture.requests()))
        .contains("portable_" + asset + "-gpl.tar.xz")
        .doesNotContain(asset.equals("linux64") ? "linuxarm64" : "linux64-");
  }

  @ParameterizedTest
  @CsvSource({"amd64,amd64", "x86_64,amd64", "arm64,arm64", "aarch64,arm64"})
  @DisplayName("Should verify the archive against the locked digest of the requested architecture")
  void shouldVerifyTheArchiveAgainstTheLockedDigestOfTheRequestedArchitecture(
      String architecture, String locked) throws Exception {
    var capture = capture(architecture);

    var result = capture.command().execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(capture.verified()))
        .startsWith(lockValue(locked + "_sha256") + "  ")
        .endsWith("/" + lockValue(locked + "_asset") + "\n");
  }

  @Test
  @DisplayName("Should write nothing when the locked archive checksum is incorrect")
  void shouldWriteNothingWhenTheLockedArchiveChecksumIsIncorrect() throws Exception {
    var capture = capture("amd64");

    var result = capture.command().environment("FAKE_SHA256_EXIT", "1").execute();

    assertThat(result.exitCode()).isNotZero();
    assertThat(capture.output()).doesNotExist();
  }

  @Test
  @DisplayName("Should write nothing when the binary prints no build configuration banner")
  void shouldWriteNothingWhenTheBinaryPrintsNoBuildConfigurationBanner() throws Exception {
    var capture = capture("amd64");
    Files.writeString(capture.buildconf(), "Illegal instruction\n");

    var result = capture.command().execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("did not print a build configuration");
    assertThat(capture.output()).doesNotExist();
  }

  @Test
  @DisplayName("Should reject an unsupported architecture before downloading anything")
  void shouldRejectAnUnsupportedArchitectureBeforeDownloadingAnything() throws Exception {
    var capture = capture("riscv64");

    var result = capture.command().execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Unsupported FFmpeg target architecture: riscv64");
    assertThat(capture.requests()).doesNotExist();
  }

  private CaptureFixture capture(String architecture) throws IOException {
    var repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
    var buildpack = Files.createDirectories(repository.resolve(BUILDPACK));
    Files.copy(BUILDPACK.resolve("ffmpeg.lock"), buildpack.resolve("ffmpeg.lock"));
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var requests = temporaryDirectory.resolve("requests");
    var buildconf = temporaryDirectory.resolve("buildconf");
    var verified = temporaryDirectory.resolve("verified");
    var output = temporaryDirectory.resolve("captured.txt");
    Files.writeString(buildconf, BUILDCONF);
    ScriptCommand.writeFake(
        commands,
        "curl",
        """
        while (( $# > 0 )); do
          case "$1" in
            --output)
              : >"$2"
              shift 2
              ;;
            https://*)
              printf '%s\\n' "$1" >>"${FAKE_REQUESTS}"
              shift
              ;;
            *)
              shift
              ;;
          esac
        done
        """);
    ScriptCommand.writeFake(
        commands,
        "sha256sum",
        """
        if [[ " $* " != *" --check "* ]]; then
          echo 'Reading a digest is not verifying one' >&2
          exit 1
        fi
        cat >"${FAKE_VERIFIED}"
        exit "${FAKE_SHA256_EXIT:-0}"
        """);
    ScriptCommand.writeFake(
        commands,
        "tar",
        """
        while (( $# > 0 )); do
          if [[ "$1" == "--directory" ]]; then
            directory="$2"
          fi
          shift
        done
        cat >"${directory}/ffmpeg" <<'SCRIPT'
        #!/bin/bash
        if [[ -n "${FAKE_LOADER_DIAGNOSTIC:-}" ]]; then
          printf '%s\\n' "${FAKE_LOADER_DIAGNOSTIC}"
        fi
        cat "${FAKE_BUILDCONF}"
        SCRIPT
        chmod +x "${directory}/ffmpeg"
        """);
    var command =
        ScriptCommand.of(CAPTURE)
            .argument("--root")
            .argument(repository.toString())
            .argument("--architecture")
            .argument(architecture)
            .argument("--output")
            .argument(output.toString())
            .prependPath(commands)
            .environment("FAKE_REQUESTS", requests.toString())
            .environment("FAKE_VERIFIED", verified.toString())
            .environment("FAKE_BUILDCONF", buildconf.toString());
    return CaptureFixture.builder()
        .command(command)
        .output(output)
        .requests(requests)
        .verified(verified)
        .buildconf(buildconf)
        .build();
  }

  private static String lockValue(String key) throws IOException {
    var prefix = key + "=";
    return Files.readAllLines(BUILDPACK.resolve("ffmpeg.lock")).stream()
        .filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length()))
        .collect(Collectors.joining());
  }

  @Builder
  private record CaptureFixture(
      ScriptCommand command, Path output, Path requests, Path verified, Path buildconf) {}
}
