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
 * so that its preflight, time limit and cleanup are checked without Docker or a GPU.
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
      "Should keep the previous results when the recorder is stopped while it moves the new"
          + " results into place")
  void shouldKeepThePreviousResultsWhenTheRecorderIsStoppedWhileItMovesTheNewResultsIntoPlace()
      throws Exception {
    var results =
        stopWhilePublishing(
            """
            case "$1" in
              */.nvenc-*/results/*) kill -TERM "$PPID"; exit 1 ;;
            esac
            exec /bin/mv "$@"
            """);

    assertPreviousResultsKept(results);
  }

  @Test
  @DisplayName(
      "Should keep the previous results when the recorder is stopped while it moves them aside")
  void shouldKeepThePreviousResultsWhenTheRecorderIsStoppedWhileItMovesThemAside()
      throws Exception {
    // The second move aside stops the recorder, the parent of find, and moves nothing.
    var results =
        stopWhilePublishing(
            """
            case "$2" in
              */.nvenc-*/previous/)
                count=$(($(cat "${2%%/previous/}/moved-aside" 2>/dev/null || echo 0) + 1))
                echo "$count" > "${2%%/previous/}/moved-aside"
                if [ "$count" = 2 ]; then kill -TERM "$(ps -o ppid= -p "$PPID")"; exit 1; fi ;;
            esac
            exec /bin/mv "$@"
            """);

    assertPreviousResultsKept(results);
  }

  @Test
  @DisplayName(
      "Should publish the analysis of the pinned Node image when the host's Node is another"
          + " version")
  void shouldPublishTheAnalysisOfThePinnedNodeImageWhenTheHostsNodeIsAnotherVersion()
      throws Exception {
    var bin = recordingBin(nodeVersionInTooling());
    executable(
        bin.resolve("node"),
        """
        case "$2" in
          analyze) echo host > "$4/recordings.json" ;;
          *) echo v26.11.1 ;;
        esac
        """);
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));

    var status = runToTheEnd(bin, "--results-dir", results.toString());

    assertThat(status).as("the recorder's exit status").isZero();
    assertThat(Files.readString(results.resolve("recordings.json"))).isEqualTo("analysis\n");
    var pinnedNodeImage = " node:" + nodeVersionInTooling() + "@sha256:";
    assertThat(Files.readAllLines(temporaryDirectory.resolve("docker.log")))
        .as("the Docker calls")
        .anyMatch(call -> call.contains(pinnedNodeImage) && call.contains(" analyze "));
  }

  @Test
  @DisplayName(
      "Should skip with status 3 and record nothing when the pinned Node image holds another Node"
          + " version than the tooling pin")
  void
      shouldSkipWithStatus3AndRecordNothingWhenThePinnedNodeImageHoldsAnotherNodeVersionThanTheToolingPin()
          throws Exception {
    var bin = recordingBin("24.0.0");
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));

    var status = runToTheEnd(bin, "--results-dir", results.toString());

    assertThat(status).as("the preflight's exit status").isEqualTo(3);
    assertThat(Files.readAllLines(temporaryDirectory.resolve("docker.log")))
        .as("the Docker calls")
        .noneMatch(call -> call.contains("/work/record.sh") || call.contains(" analyze "));
    try (var left = Files.list(results)) {
      assertThat(left).as("what the recorder left in its results directory").isEmpty();
    }
  }

  /**
   * Runs the recorder to the point where it publishes, over previous results, with every other step
   * succeeding and the given mv, and returns the results directory. The recorder gets a symbolic
   * link to that directory, as --results-dir may name one.
   */
  private Path stopWhilePublishing(String mv) throws Exception {
    var bin = recordingBin(nodeVersionInTooling());
    var results = Files.createDirectory(temporaryDirectory.resolve("results"));
    Files.writeString(results.resolve("recordings.json"), "previous");
    Files.writeString(results.resolve("previous.init.mp4"), "previous");
    executable(bin.resolve("mv"), mv);

    var link = Files.createSymbolicLink(temporaryDirectory.resolve("results-link"), results);

    var status = runToTheEnd(bin, "--results-dir", link.toString());

    assertThat(status).as("the exit status of a stopped recorder").isEqualTo(124);
    return results;
  }

  /**
   * A bin directory whose uname reports Linux and whose docker logs every call and succeeds: the
   * pinned Node image reports that Node version, the recording run writes the FFmpeg version into
   * its work mount, and the analysis writes its results file there.
   */
  private Path recordingBin(String nodeVersion) throws IOException {
    var bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
    var dockerLog = temporaryDirectory.resolve("docker.log");
    executable(bin.resolve("uname"), "echo Linux");
    executable(
        bin.resolve("docker"),
        """
        echo "$*" >> '%s'
        [ "$1" = run ] || exit 0
        work=""
        for argument in "$@"; do
          case "$argument" in
            *:/work) work=${argument%%:/work} ;;
          esac
        done
        case " $* " in
          *" node --version "*) echo v%s ;;
          *" analyze "*) echo analysis > "$work/results/recordings.json" ;;
          *) [ -z "$work" ] || echo "ffmpeg version 0" > "$work/ffmpeg-version" ;;
        esac
        """
            .formatted(dockerLog, nodeVersion));
    return bin;
  }

  private static String nodeVersionInTooling() throws IOException {
    return Files.readString(Path.of("buildpacks", "ffmpeg", ".nvmrc")).strip();
  }

  private static void assertPreviousResultsKept(Path results) throws IOException {
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
    executable(
        bin.resolve("docker"), String.join("\n", "echo \"$*\" >> '" + dockerLog + "'", docker));

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
