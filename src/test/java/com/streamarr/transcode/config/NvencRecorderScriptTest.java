package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
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
          + " directory when a GPU preflight container never leaves the created state")
  void
      shouldStopAtTheTimeLimitRemoveItsContainerAndLeaveNothingInTheResultsDirectoryWhenAGpuPreflightContainerNeverLeavesTheCreatedState()
          throws Exception {
    // A container still being created, as one is while the GPU runtime hangs, is listed only
    // with --all.
    var run =
        runRecorder(
            """
            case "$1" in
              run) exec sleep 600 ;;
              ps) case " $* " in *" --all "*|*" -a "*) echo stuck-container ;; esac ;;
            esac
            """);

    assertThat(run.status()).as("the time limit's exit status").isEqualTo(124);
    assertThat(run.dockerCalls()).contains("rm -f stuck-container");
    assertThat(run.resultsLeft()).as("what the recorder left in its results directory").isEmpty();
  }

  @Test
  @DisplayName(
      "Should exit and leave nothing in the results directory when a cleanup Docker call ignores"
          + " the signal to stop")
  void shouldExitAndLeaveNothingInTheResultsDirectoryWhenACleanupDockerCallIgnoresTheSignalToStop()
      throws Exception {
    var run =
        runRecorder(
            """
            case "$1" in
              run) exec sleep 600 ;;
              ps) echo stuck-container ;;
              rm) trap '' TERM INT HUP; exec sleep 600 ;;
            esac
            """);

    assertThat(run.status()).as("the time limit's exit status").isEqualTo(124);
    assertThat(run.resultsLeft()).as("what the recorder left in its results directory").isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the previous results when the recorder is stopped while it replaces them")
  void shouldKeepThePreviousResultsWhenTheRecorderIsStoppedWhileItReplacesThem() throws Exception {
    var bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));
    Files.writeString(results.resolve("recordings.json"), "previous");
    Files.writeString(results.resolve("previous.init.mp4"), "previous");
    executable(bin.resolve("uname"), "echo Linux");
    executable(
        bin.resolve("node"),
        """
        case "$2" in
          analyze) echo replacement > "$4/recordings.json" ;;
          *) echo v24.0.0 ;;
        esac
        """);
    // Every Docker call succeeds; the recording run writes the FFmpeg version into its work mount.
    executable(
        bin.resolve("docker"),
        """
        [ "$1" = run ] || exit 0
        for argument in "$@"; do
          case "$argument" in
            *:/work) echo "ffmpeg version 0" > "${argument%:/work}/ffmpeg-version" ;;
          esac
        done
        """);
    // The recorder is stopped as it starts to move the new results into place.
    executable(
        bin.resolve("mv"),
        """
        case "$1" in
          */.nvenc-*/results/*) kill -TERM "$PPID"; exit 1 ;;
        esac
        exec /bin/mv "$@"
        """);

    var status = runToTheEnd(bin, "--results-dir", results.toString());

    assertThat(status).as("the exit status of a stopped recorder").isEqualTo(124);
    try (var left = Files.list(results)) {
      assertThat(left.map(path -> path.getFileName().toString()))
          .as("what the recorder left in its results directory")
          .containsExactlyInAnyOrder("recordings.json", "previous.init.mp4");
    }
    assertThat(Files.readString(results.resolve("recordings.json"))).isEqualTo("previous");
  }

  private record RecorderRun(int status, List<String> dockerCalls, List<Path> resultsLeft) {}

  /** Runs the recorder with a two-second time limit against a docker that behaves as given. */
  private RecorderRun runRecorder(String docker) throws Exception {
    var bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
    var dockerLog = temporaryDirectory.resolve("docker.log");
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));
    executable(bin.resolve("uname"), "echo Linux");
    executable(bin.resolve("node"), "echo v24.0.0");
    executable(bin.resolve("docker"), "echo \"$*\" >> '%s'\n%s".formatted(dockerLog, docker));

    var status = runToTheEnd(bin, "--results-dir", results.toString(), "--time-limit", "2");

    try (var left = Files.list(results)) {
      return new RecorderRun(status, Files.readAllLines(dockerLog), left.toList());
    }
  }

  /** Runs the recorder with the executables in bin first on PATH and returns its exit status. */
  private int runToTheEnd(Path bin, String... arguments) throws Exception {
    var command = new ArrayList<>(List.of("bash", RECORDER.toString()));
    command.addAll(List.of(arguments));
    var recorder =
        new ProcessBuilder(command)
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

    return process.exitValue();
  }

  private static void executable(Path path, String body) throws IOException {
    Files.writeString(path, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
  }
}
