package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs src/test/fmp4-recorder/record-nvenc.sh against scripted uname, node and docker executables,
 * so that its time limit and cleanup are checked without Docker or a GPU.
 */
@Tag("UnitTest")
@DisplayName("NVENC Recorder Script Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class NvencRecorderScriptTest {

  private static final Path RECORDER = Path.of("src", "test", "fmp4-recorder", "record-nvenc.sh");

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName(
      "Should stop at the time limit, remove its container and leave nothing in the results"
          + " directory when a GPU preflight container never exits")
  void
      shouldStopAtTheTimeLimitRemoveItsContainerAndLeaveNothingInTheResultsDirectoryWhenAGpuPreflightContainerNeverExits()
          throws Exception {
    var bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
    var dockerLog = temporaryDirectory.resolve("docker.log");
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));
    executable(bin.resolve("uname"), "echo Linux");
    executable(bin.resolve("node"), "echo v24.0.0");
    // Every container hangs, as one would while a driver call never returns.
    executable(
        bin.resolve("docker"),
        """
        echo "$*" >> '%s'
        case "$1" in
          run) exec sleep 600 ;;
          ps) echo stuck-container ;;
        esac
        """
            .formatted(dockerLog));

    var recorder =
        new ProcessBuilder(
                "bash",
                RECORDER.toString(),
                "--results-dir",
                results.toString(),
                "--time-limit",
                "2")
            .redirectErrorStream(true)
            .redirectOutput(temporaryDirectory.resolve("recorder.log").toFile());
    recorder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    var process = recorder.start();
    try {
      assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the recorder stops").isTrue();
    } finally {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }

    assertThat(process.exitValue()).as("the time limit's exit status").isEqualTo(124);
    assertThat(Files.readAllLines(dockerLog)).contains("rm -f stuck-container");
    try (var left = Files.list(results)) {
      assertThat(left).as("what the recorder left in its results directory").isEmpty();
    }
  }

  private static void executable(Path path, String body) throws IOException {
    Files.writeString(path, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
  }
}
