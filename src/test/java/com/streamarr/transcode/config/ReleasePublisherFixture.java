package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    return ((Map<?, ?>) workflow().get("jobs"))
        .values().stream()
            .flatMap(job -> ((List<?>) ((Map<?, ?>) job).get("steps")).stream())
            .map(item -> (Map<?, ?>) item)
            .filter(item -> name.equals(item.get("name")))
            .findFirst()
            .orElseThrow();
  }

  void publishedRelease() throws Exception {
    git("init", "--quiet", "--initial-branch=main");
    git("commit", "--quiet", "--allow-empty", "-m", "fixture");
    git("tag", "v1.2.3");
    git("update-ref", "refs/remotes/origin/main", "HEAD");
    mavenVersion("1.2.3");
    releaseState("{\"releases\":{\"v1.2.3\":{\"draft\":false}},\"latest\":\"v1.2.3\"}");
    executable(
        "mvnw",
        """
        [[ "$*" == *'-Dexpression=project.version'* ]]
        cat "$FIXTURE_DIRECTORY/maven-version"
        """);
    executable(
        "gh",
        """
        state="$FIXTURE_DIRECTORY/releases.json"
        [[ "$1" == api ]]
        if jq -e '.unavailable == true' "$state" >/dev/null; then
          echo 'GitHub releases unavailable' >&2
          exit 23
        fi
        case "$2" in
          repos/streamarr/streamarr-transcode-worker/releases/tags/*)
            tag="${2##*/}"
            jq -e --arg tag "$tag" '.releases | has($tag)' "$state" >/dev/null
            jq --arg tag "$tag" '.releases[$tag]' "$state" | jq -r "$4"
            ;;
          repos/streamarr/streamarr-transcode-worker/releases/latest)
            jq -r '{tag_name: .latest}' "$state" | jq -r "$4"
            ;;
          *) exit 81 ;;
        esac
        """);
  }

  void mavenVersion(String version) throws Exception {
    Files.writeString(directory.resolve("maven-version"), version);
  }

  void releaseState(String json) throws Exception {
    Files.writeString(directory.resolve("releases.json"), json);
  }

  void executable(String name, String script) throws Exception {
    var path = directory.resolve(name);
    Files.writeString(path, "#!/bin/bash\nset -euo pipefail\n" + script);
    assertThat(path.toFile().setExecutable(true)).isTrue();
  }

  Result runStep(String name) throws Exception {
    return run(stepCommand(name));
  }

  ProcessBuilder stepCommand(String name) throws Exception {
    var step = step(name);
    var command = command((String) step.get("run"));
    if (!"bash".equals(step.get("shell"))) {
      command.command("bash", "--noprofile", "--norc", "-e", "-c", (String) step.get("run"));
    }

    return command;
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
          buildx)
            [[ "$2" == imagetools ]]
            case "$3" in
              create)
                [[ "$4" == --tag ]]
                jq -e --arg first "$6" --arg second "$7" \\
                  '.registry[$first] != null and .registry[$second] != null' "$state" >/dev/null
                jq --arg tag "$5" --arg first "$6" --arg second "$7" \\
                  '.indexes[$tag] = [.registry[$first], .registry[$second]]' "$state" > "$state.next"
                ;;
              inspect)
                [[ "$4" == --raw ]]
                if [[ -f "$FIXTURE_DIRECTORY/inspection.json" ]]; then
                  cat "$FIXTURE_DIRECTORY/inspection.json"
                  exit "${INSPECTION_STATUS:-0}"
                fi
                jq --arg tag "$5" '{manifests: [.indexes[$tag][] |
                  {platform: {os: "linux", architecture: .architecture}}]}' "$state"
                exit "${INSPECTION_STATUS:-0}"
                ;;
              *) exit 81 ;;
            esac
            ;;
          *) exit 81 ;;
        esac
        mv "$state.next" "$state"
        """);
  }

  Map<?, ?> registry() throws Exception {
    return new Yaml().load(Files.readString(directory.resolve("registry.json")));
  }

  void registryInspection(String json) throws Exception {
    Files.writeString(directory.resolve("inspection.json"), json);
  }

  void publishedNativeImages() throws Exception {
    registryState(
        """
        {"authenticated":false,"local":{},"registry":{
          "index.docker.io/streamarr/streamarr-transcode-worker:1.2.3-amd64":{"version":"1.2.3","architecture":"amd64"},
          "index.docker.io/streamarr/streamarr-transcode-worker:1.2.3-arm64":{"version":"1.2.3","architecture":"arm64"}
        },"indexes":{
          "index.docker.io/streamarr/streamarr-transcode-worker:latest":[{"version":"1.2.2"}]
        }}
        """);
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
    for (var step : steps.subList(steps.indexOf(build) + 1, steps.size())) {
      assertThat(step).asInstanceOf(MAP).doesNotContainKeys("if", "continue-on-error");
      var command = publicationCommand(step);
      command
          .environment()
          .putAll(
              Map.of(
                  "WORKER_IMAGE", "streamarr-worker:release-" + scenario.architecture(),
                  "IMAGE_ARCHITECTURE", scenario.architecture(),
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
                directory.toString(),
                "GITHUB_REPOSITORY",
                "streamarr/streamarr-transcode-worker",
                "GITHUB_OUTPUT",
                directory.resolve("outputs").toString(),
                "RELEASE_TAG",
                "v1.2.3",
                "IMAGE_VERSION",
                "1.2.3"));
    return command;
  }

  String outputs() throws Exception {
    var path = directory.resolve("outputs");
    return Files.exists(path) ? Files.readString(path) : "";
  }

  String git(String... arguments) throws Exception {
    var args = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
    args.addAll(List.of(arguments));
    var command = new ProcessBuilder(args).directory(directory.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_AUTHOR_NAME", "Release fixture",
                "GIT_AUTHOR_EMAIL", "fixture@example.test",
                "GIT_COMMITTER_NAME", "Release fixture",
                "GIT_COMMITTER_EMAIL", "fixture@example.test"));
    var result = run(command);
    assertThat(result.exitCode()).as(result.output()).isZero();
    return result.output().trim();
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
  record ImageTestScenario(String architecture, int status) {}
}
