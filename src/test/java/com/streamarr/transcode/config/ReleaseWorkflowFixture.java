package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;

@RequiredArgsConstructor
class ReleaseWorkflowFixture {

  private final Path directory;
  private String releasePullRequest = "{}";

  static Map<?, ?> workflow() throws Exception {
    return new Yaml().load(Files.readString(Path.of(".github/workflows/release-please.yml")));
  }

  static List<Map<?, ?>> steps() throws Exception {
    var jobs = (Map<?, ?>) workflow().get("jobs");
    var release = (Map<?, ?>) jobs.get("release");
    return ((List<?>) release.get("steps"))
        .stream().<Map<?, ?>>map(step -> (Map<?, ?>) step).toList();
  }

  static Map<?, ?> step(String name) throws Exception {
    return steps().stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
  }

  void repository(String json) throws Exception {
    Files.writeString(directory.resolve("repository.json"), json);
    var gh = directory.resolve("gh");
    Files.writeString(
        gh,
        """
        #!/bin/bash
        set -euo pipefail
        state="$FIXTURE_REPOSITORY"
        if jq -e '.unavailable == true' "$state" >/dev/null; then
          echo 'GitHub repository unavailable' >&2
          exit 1
        fi
        command="$1 $2"
        shift 2
        number=""
        if [[ "$command" == 'pr merge' ]]; then number="$1"; shift; fi
        head=""; subject=""; body="missing"; auto=false; squash=false
        base=""; status=""; label=""; query=""
        while (( $# )); do
          case "$1" in
            --auto) auto=true; shift; continue ;;
            --squash) squash=true; shift; continue ;;
            --match-head-commit) head="$2" ;;
            --subject) subject="$2" ;;
            --body) body="$2" ;;
            --base) base="$2" ;;
            --state) status="$2" ;;
            --label) label="$2" ;;
            --jq) query="$2" ;;
            --repo|--json|--limit) ;;
            *) echo "Unsupported option: $1" >&2; exit 2 ;;
          esac
          shift 2
        done
        case "$command" in
          api*) jq -r "$query" "$state" ;;
          'pr merge')
            [[ "$auto" == true && "$squash" == true ]]
            jq -e --arg head "$head" '.head.sha == $head' "$state" >/dev/null
            jq --argjson number "$number" --arg head "$head" --arg subject "$subject" --arg body "$body" \\
              '.autoMerge = {number:$number,head:$head,subject:$subject,body:$body,method:"squash"}' \\
              "$state" > "$state.next"
            mv "$state.next" "$state"
            ;;
          'pr list')
            jq -r --arg base "$base" --arg status "$status" \\
              --arg label "$label" \\
              '[.pulls[] | select(.base == $base and .state == $status and (.labels | index($label)))]' \\
              "$state" | jq -r "$query"
            ;;
          *) echo "Unsupported fixture command: $command" >&2; exit 2 ;;
        esac
        """);
    assertThat(gh.toFile().setExecutable(true)).isTrue();
  }

  void releasePullRequest(String json) {
    releasePullRequest = json;
  }

  Map<?, ?> repository() throws Exception {
    return new Yaml().load(Files.readString(directory.resolve("repository.json")));
  }

  Result run(String name) throws Exception {
    var source = step(name);
    var output = directory.resolve("output.txt");
    var command =
        new ProcessBuilder(
                "bash",
                "--noprofile",
                "--norc",
                "-eo",
                "pipefail",
                "-c",
                (String) source.get("run"))
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    command
        .environment()
        .putAll(
            Map.of(
                "PATH",
                directory + ":" + System.getenv("PATH"),
                "GITHUB_REPOSITORY",
                "streamarr/streamarr-transcode-worker",
                "GH_TOKEN",
                "fixture-token",
                "RELEASE_PR",
                releasePullRequest,
                "FIXTURE_REPOSITORY",
                directory.resolve("repository.json").toString()));
    var process = command.start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS))
          .as("Release shell step must terminate")
          .isTrue();
      return new Result(process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  record Result(int exitCode, String output) {}
}
