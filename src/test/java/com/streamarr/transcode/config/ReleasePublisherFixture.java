package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;

@RequiredArgsConstructor
class ReleasePublisherFixture {

  private final Path directory;

  static Map<?, ?> workflow() throws Exception {
    return new Yaml().load(Files.readString(Path.of(".github/workflows/publish-release.yml")));
  }

  static Map<?, ?> job(String name) throws Exception {
    return (Map<?, ?>) ((Map<?, ?>) workflow().get("jobs")).get(name);
  }

  static List<Map<?, ?>> steps(String job) throws Exception {
    return ((List<?>) job(job).get("steps"))
        .stream().<Map<?, ?>>map(step -> (Map<?, ?>) step).toList();
  }

  static Map<?, ?> step(String name) throws Exception {
    return steps("build_release_images").stream()
        .filter(item -> name.equals(item.get("name")))
        .findFirst()
        .orElseThrow();
  }

  void executable(String name, String script) throws Exception {
    var path = directory.resolve(name);
    Files.writeString(path, "#!/bin/bash\nset -euo pipefail\n" + script);
    assertThat(path.toFile().setExecutable(true)).isTrue();
  }

  void registryState(String json) throws Exception {
    Files.writeString(directory.resolve("registry.json"), json);
    executable(
        "docker",
        """
        state="$FIXTURE_DIRECTORY/registry.json"
        case "$1" in
          login) jq '.authenticated = true' "$state" > "$state.next" ;;
          tag)
            jq -e --arg image "$2" '.local[$image] != null' "$state" >/dev/null
            jq --arg from "$2" --arg to "$3" '.local[$to] = .local[$from]' "$state" > "$state.next"
            ;;
          push)
            jq -e '.authenticated == true' "$state" >/dev/null
            jq --arg image "$2" '.registry[$image] = .local[$image]' "$state" > "$state.next"
            ;;
          inspect)
            printf 'streamarr/streamarr-transcode-worker@sha256:%s\\n' \\
              dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
            exit 0
            ;;
          *) exit 81 ;;
        esac
        mv "$state.next" "$state"
        """);
  }

  Map<?, ?> registry() throws Exception {
    return new Yaml().load(Files.readString(directory.resolve("registry.json")));
  }

  Result publishNativeImage(ImageTestScenario scenario) throws Exception {
    executable(
        "mvnw",
        """
        [[ "$*" == *'-Dit.test=WorkerImageIT'* ]]
        [[ "$*" == *"-Dworker.image=$WORKER_IMAGE"* ]]
        jq -e --arg image "$WORKER_IMAGE" '.local[$image] != null' \\
          "$FIXTURE_DIRECTORY/registry.json" >/dev/null
        exit "$IMAGE_TEST_STATUS"
        """);
    var steps = steps("build_release_images");
    var build = step("Build the unpublished image and verify its media runtime");
    var publication = step("Publish the verified native image");
    for (var step : steps.subList(steps.indexOf(build) + 1, steps.indexOf(publication) + 1)) {
      assertThat(step).asInstanceOf(MAP).doesNotContainKeys("if", "continue-on-error");
      var command = publicationCommand(step);
      command
          .environment()
          .putAll(
              Map.of(
                  "WORKER_IMAGE", "streamarr-worker:release-" + scenario.architecture(),
                  "IMAGE_ARCHITECTURE", scenario.architecture(),
                  "SOURCE_REVISION", scenario.sourceRevision(),
                  "IMAGE_TEST_STATUS", String.valueOf(scenario.status())));
      var result = run(command);
      if (result.exitCode() != 0) {
        return result;
      }
    }

    return new Result(0, "");
  }

  private ProcessBuilder publicationCommand(Map<?, ?> step) {
    if (step.get("run") instanceof String script) {
      return command(script);
    }

    assertThat(step.get("uses")).asString().matches("docker/login-action@[a-f0-9]{40}");
    return command("docker login");
  }

  ProcessBuilder command(String script) {
    var command =
        new ProcessBuilder("bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script)
            .directory(directory.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "PATH",
                directory + ":" + System.getenv("PATH"),
                "FIXTURE_DIRECTORY",
                directory.toString()));
    return command;
  }

  Map<?, ?> nativeReceipt(String architecture) throws Exception {
    var path = directory.resolve(architecture + "-image.json");
    return Files.exists(path) ? new Yaml().load(Files.readString(path)) : Map.of();
  }

  Result run(ProcessBuilder command) throws Exception {
    var output = directory.resolve("command-output");
    var process = command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Release step must terminate").isTrue();
      return new Result(process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  record Result(int exitCode, String output) {}

  @Builder
  record ImageTestScenario(String architecture, String sourceRevision, int status) {}
}
