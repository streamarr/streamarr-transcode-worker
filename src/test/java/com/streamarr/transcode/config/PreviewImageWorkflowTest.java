package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Preview Image Workflow Tests")
class PreviewImageWorkflowTest {

  private static final String PREVIEW_WORKFLOW = ".github/workflows/preview.yml";
  private static final String CLEANUP_WORKFLOW = ".github/workflows/preview-cleanup.yml";
  private static final String BUILD_JOB = "build_preview";
  private static final String PUBLISH_JOB = "publish_preview";
  private static final String PULL_REQUEST = "42";
  private static final String BUILT_IMAGE = "streamarr-worker:preview-amd64";
  private static final String PREVIEW_TAG =
      "index.docker.io/streamarr/streamarr-transcode-worker:pr-" + PULL_REQUEST;
  private static final String HEAD_REVISION = "a".repeat(40);
  private static final String HUB_TAG_URL =
      "https://hub.docker.com/v2/repositories/streamarr/streamarr-transcode-worker/tags/pr-"
          + PULL_REQUEST;
  private static final Pattern PINNED_ACTION =
      Pattern.compile("[\\w.-]+/[\\w.-]+(/[\\w.-]+)*@[0-9a-f]{40}");
  private static final Pattern EXPRESSION = Pattern.compile("\\$\\{\\{ ([^}]+) }}");

  @TempDir Path directory;

  @Test
  @DisplayName("Should build a preview only when a trusted collaborator comments the exact command")
  void shouldBuildPreviewOnlyWhenTrustedCollaboratorCommentsTheExactCommand() throws IOException {
    var workflow = yaml(PREVIEW_WORKFLOW);

    assertThat(map(workflow.get(true)))
        .containsOnly(entry("issue_comment", Map.of("types", List.of("created"))));
    assertThat(workflow).containsEntry("permissions", Map.of());
    assertThat(job(workflow, BUILD_JOB).get("if").toString().strip())
        .isEqualTo(
            "github.event.issue.pull_request"
                + " && github.event.comment.body == '/deploy-preview'"
                + " && contains(fromJSON('[\"OWNER\",\"MEMBER\",\"COLLABORATOR\"]'),"
                + " github.event.comment.author_association)");
  }

  @Test
  @DisplayName("Should check out the pull request head without credentials when building a preview")
  void shouldCheckOutThePullRequestHeadWithoutCredentialsWhenBuildingAPreview() throws IOException {
    var build = job(yaml(PREVIEW_WORKFLOW), BUILD_JOB);
    var steps = steps(build);
    var checkout = steps.getFirst();
    var revision = stepNamed(steps, "Resolve PR head SHA");

    assertThat(build)
        .containsEntry("permissions", Map.of("contents", "read"))
        .containsEntry("runs-on", "ubuntu-24.04");
    assertThat(checkout.get("uses").toString()).startsWith("actions/checkout@");
    assertThat(map(checkout.get("with")))
        .containsOnly(
            entry("ref", "refs/pull/${{ github.event.issue.number }}/head"),
            entry("persist-credentials", false));
    assertThat(steps.indexOf(revision))
        .as("The head is resolved before any pull request code runs")
        .isEqualTo(1);
    assertThat(map(build.get("outputs")))
        .containsOnly(entry("revision", "${{ steps.pr_head.outputs.sha }}"));
    assertThat(build.toString()).doesNotContain("secrets.");
  }

  @Test
  @DisplayName("Should leave the default branch cache untouched when building pull request code")
  void shouldLeaveTheDefaultBranchCacheUntouchedWhenBuildingPullRequestCode() throws IOException {
    var steps = steps(job(yaml(PREVIEW_WORKFLOW), BUILD_JOB));

    assertThat(steps)
        .allSatisfy(step -> assertThat(map(step.get("with"))).doesNotContainKey("cache"));
    assertThat(steps.toString()).doesNotContain("actions/cache");
  }

  @Test
  @DisplayName("Should verify the media runtime of the pull request head when building a preview")
  void shouldVerifyTheMediaRuntimeOfThePullRequestHeadWhenBuildingAPreview() throws IOException {
    var workflow = yaml(PREVIEW_WORKFLOW);
    var steps = steps(job(workflow, BUILD_JOB));
    var build =
        stepNamed(steps, "Build the unpublished preview image and verify its media runtime");

    assertThat(map(workflow.get("env"))).containsEntry("WORKER_IMAGE", BUILT_IMAGE);
    assertThat(steps)
        .anySatisfy(
            step -> assertThat(step).containsEntry("uses", "./.github/actions/prepare-ffmpeg"));
    assertThat(build)
        .containsEntry(
            "run",
            ".github/actions/pack-build/build-worker-image.sh \"$WORKER_IMAGE\" \"$PREVIEW_REVISION\"");
    assertThat(map(build.get("env")))
        .containsOnly(entry("PREVIEW_REVISION", "${{ steps.pr_head.outputs.sha }}"));
  }

  @Test
  @DisplayName("Should run only pinned actions and inline steps when publishing with credentials")
  void shouldRunOnlyPinnedActionsAndInlineStepsWhenPublishingWithCredentials() throws IOException {
    var publish = job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB);
    var steps = steps(publish);

    assertThat(publish)
        .containsEntry("needs", BUILD_JOB)
        .containsEntry("permissions", Map.of("pull-requests", "write"));
    assertThat(steps)
        .allSatisfy(
            step -> {
              if (step.get("uses") instanceof String action) {
                assertThat(action).matches(PINNED_ACTION).doesNotStartWith("actions/checkout@");
              }
            });
    assertThat(steps)
        .allSatisfy(
            step -> assertThat(String.valueOf(step.get("run"))).doesNotContain("./", ".github/"));
    assertThat(steps.indexOf(stepNamed(steps, "Load preview image")))
        .isLessThan(steps.indexOf(stepNamed(steps, "Login to Docker Hub")));
  }

  @Test
  @DisplayName("Should push only the pull request tag when publishing a built preview")
  void shouldPushOnlyThePullRequestTagWhenPublishingABuiltPreview() throws Exception {
    var registry = fakeDocker();
    var archive = Files.createDirectories(directory.resolve("runner/preview-image"));
    Files.writeString(archive.resolve("preview-image.tar"), BUILT_IMAGE + " sha256:built\n");

    var result = runPublication();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(images(registry.resolve("registry")))
        .containsOnly(entry(PREVIEW_TAG, "sha256:built"));
  }

  @Test
  @DisplayName("Should push nothing when the built preview archive is missing")
  void shouldPushNothingWhenTheBuiltPreviewArchiveIsMissing() throws Exception {
    var registry = fakeDocker();
    Files.createDirectories(directory.resolve("runner/preview-image"));

    var result = runPublication();

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("Preview image archive is missing");
    assertThat(images(registry.resolve("registry"))).isEmpty();
  }

  @Test
  @DisplayName("Should name the pulled tag and built commit when reporting a published preview")
  void shouldNameThePulledTagAndBuiltCommitWhenReportingAPublishedPreview() throws Exception {
    var result = runReport(HEAD_REVISION);

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output())
        .contains("issue 42:")
        .contains("docker pull streamarr/streamarr-transcode-worker:pr-42")
        .contains(HEAD_REVISION);
  }

  @Test
  @DisplayName("Should post no report when the build job hands over a malformed revision")
  void shouldPostNoReportWhenTheBuildJobHandsOverAMalformedRevision() throws Exception {
    var result = runReport("abc`\n@someone");

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).doesNotContain("issue 42:");
  }

  @Test
  @DisplayName("Should clean up with read-only access when a pull request closes")
  void shouldCleanUpWithReadOnlyAccessWhenAPullRequestCloses() throws IOException {
    var workflow = yaml(CLEANUP_WORKFLOW);

    assertThat(map(workflow.get(true)))
        .containsOnly(entry("pull_request", Map.of("types", List.of("closed"))));
    assertThat(workflow).containsEntry("permissions", Map.of("contents", "read"));
    assertThat(workflow.toString()).doesNotContain("actions/checkout");
  }

  @ParameterizedTest(name = "tag lookup {0}, deletion {1}")
  @CsvSource({"200, 204, 0, GET DELETE", "404, 204, 0, GET", "200, 500, 1, GET DELETE"})
  @DisplayName("Should delete only the pull request tag when a pull request closes")
  void shouldDeleteOnlyThePullRequestTagWhenAPullRequestCloses(
      int lookupStatus, int deletionStatus, int exitCode, String expectedMethods) throws Exception {
    var requests = fakeHub(lookupStatus, deletionStatus);

    var result = runCleanup();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(exitCode);
    assertThat(Files.readAllLines(requests))
        .containsExactlyElementsOf(
            Stream.of(expectedMethods.split(" "))
                .map(method -> method + " " + HUB_TAG_URL + " Bearer hub-session")
                .toList());
  }

  @Test
  @DisplayName("Should touch no tag when Docker Hub refuses the cleanup credentials")
  void shouldTouchNoTagWhenDockerHubRefusesTheCleanupCredentials() throws Exception {
    var requests = fakeHub(200, 204);
    Files.writeString(directory.resolve("login-refused"), "");

    var result = runCleanup();

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("Failed to authenticate with Docker Hub");
    assertThat(Files.readAllLines(requests)).isEmpty();
  }

  private Path fakeDocker() throws IOException {
    var state = Files.createDirectories(directory.resolve("docker-state"));
    Files.createDirectories(state.resolve("local"));
    Files.createDirectories(state.resolve("registry"));
    ScriptCommand.writeFake(
        directory,
        "docker",
        """
        state="$FIXTURE_DIRECTORY/docker-state"
        key() { printf '%s' "${1//[\\/:]/_}"; }
        case "$1 $2" in
          'image load')
            read -r name id < "$4"
            printf '%s\\n%s\\n' "$name" "$id" > "$state/local/$(key "$name")"
            ;;
          'image inspect') test -f "$state/local/$(key "$3")" ;;
          'image save') exit 81 ;;
          tag\\ *)
            id=$(sed -n 2p "$state/local/$(key "$2")")
            printf '%s\\n%s\\n' "$3" "$id" > "$state/local/$(key "$3")"
            ;;
          push\\ *) cp "$state/local/$(key "$2")" "$state/registry/$(key "$2")" ;;
          *) exit 81 ;;
        esac
        """);
    return state;
  }

  private Path fakeHub(int lookupStatus, int deletionStatus) throws IOException {
    Files.writeString(directory.resolve("GET-status"), String.valueOf(lookupStatus));
    Files.writeString(directory.resolve("DELETE-status"), String.valueOf(deletionStatus));
    var requests = Files.writeString(directory.resolve("requests"), "");
    ScriptCommand.writeFake(
        directory,
        "curl",
        """
        method=GET url= authorization=
        while (($#)); do
          case "$1" in
            -X) method=$2; shift ;;
            -H) [[ $2 == Authorization:* ]] && authorization=${2#Authorization: }; shift ;;
            -d|-o|-w) shift ;;
            https://*) url=$1 ;;
          esac
          shift
        done
        if [[ $url == https://hub.docker.com/v2/users/login ]]; then
          [[ ! -e "$FIXTURE_DIRECTORY/login-refused" ]] || exit 22
          printf '{"token":"hub-session"}'
          exit 0
        fi
        printf '%s %s %s\\n' "$method" "$url" "$authorization" >> "$FIXTURE_DIRECTORY/requests"
        cat "$FIXTURE_DIRECTORY/$method-status"
        """);
    return requests;
  }

  private ScriptCommand.Result runPublication() throws Exception {
    var workflow = yaml(PREVIEW_WORKFLOW);
    var publish = job(workflow, PUBLISH_JOB);
    var expressions = Map.of("github.event.issue.number", PULL_REQUEST);
    return runSteps(
        List.of(map(workflow.get("env")), map(publish.get("env"))),
        expressions,
        List.of(
            stepNamed(steps(publish), "Load preview image"),
            stepNamed(steps(publish), "Publish preview image")));
  }

  private ScriptCommand.Result runCleanup() throws Exception {
    var workflow = yaml(CLEANUP_WORKFLOW);
    var cleanup = job(workflow, "delete_preview_tag");
    var expressions =
        Map.of(
            "github.event.pull_request.number", PULL_REQUEST,
            "secrets.DOCKERHUB_USERNAME", "streamarr",
            "secrets.DOCKERHUB_TOKEN", "hub-access-token");
    return runSteps(List.of(map(cleanup.get("env"))), expressions, steps(cleanup));
  }

  private ScriptCommand.Result runReport(String revision) throws Exception {
    var publish = job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB);
    var report = stepNamed(steps(publish), "Comment on PR");
    var harness =
        """
        const context = {
          repo: { owner: 'streamarr', repo: 'streamarr-transcode-worker' },
          issue: { number: 42 },
        };
        const github = { rest: { issues: { createComment: async (comment) =>
          console.log(`issue ${comment.issue_number}:\\n${comment.body}`) } } };
        (async () => {
        %s
        })().catch((error) => { console.error(error.message); process.exitCode = 1; });
        """
            .formatted(map(report.get("with")).get("script"));
    var script = Files.writeString(directory.resolve("report.cjs"), harness);
    var command = new ProcessBuilder("node", script.toString());
    command
        .environment()
        .putAll(
            resolve(
                map(report.get("env")), Map.of("needs.build_preview.outputs.revision", revision)));
    return run(command);
  }

  private ScriptCommand.Result runSteps(
      List<Map<String, Object>> environments,
      Map<String, String> expressions,
      List<Map<String, Object>> steps)
      throws Exception {
    var runner = Files.createDirectories(directory.resolve("runner"));
    var outputs = new HashMap<>(expressions);
    for (var step : steps) {
      var output = Files.writeString(directory.resolve("step-output"), "");
      var command =
          new ProcessBuilder(
              "bash", "--noprofile", "--norc", "-e", "-c", step.get("run").toString());
      var environment = command.environment();
      for (var variables : environments) {
        environment.putAll(resolve(variables, outputs));
      }
      environment.putAll(resolve(map(step.get("env")), outputs));
      environment.put("PATH", directory + ":" + System.getenv("PATH"));
      environment.put("FIXTURE_DIRECTORY", directory.toString());
      environment.put("RUNNER_TEMP", runner.toString());
      environment.put("GITHUB_OUTPUT", output.toString());
      var result = run(command);
      if (result.exitCode() != 0) {
        return result;
      }

      collectOutputs(step, output, outputs);
    }

    return new ScriptCommand.Result(0, "");
  }

  private static void collectOutputs(
      Map<String, Object> step, Path output, Map<String, String> outputs) throws IOException {
    for (var line : Files.readAllLines(output)) {
      var separator = line.indexOf('=');
      outputs.put(
          "steps." + step.get("id") + ".outputs." + line.substring(0, separator),
          line.substring(separator + 1));
    }
  }

  private static Map<String, String> resolve(
      Map<String, Object> variables, Map<String, String> expressions) {
    var resolved = new HashMap<String, String>();
    variables.forEach(
        (name, value) ->
            resolved.put(
                name,
                EXPRESSION
                    .matcher(value.toString())
                    .replaceAll(
                        match -> {
                          assertThat(expressions).containsKey(match.group(1));
                          return expressions.get(match.group(1));
                        })));
    return resolved;
  }

  private ScriptCommand.Result run(ProcessBuilder command) throws Exception {
    var output = directory.resolve("command-output");
    var process =
        command
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Workflow step must finish").isTrue();
      return new ScriptCommand.Result(process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  private static Map<String, String> images(Path images) throws IOException {
    try (var files = Files.list(images)) {
      var result = new HashMap<String, String>();
      for (var file : files.toList()) {
        var lines = Files.readAllLines(file);
        result.put(lines.get(0), lines.get(1));
      }

      return result;
    }
  }

  private static Map<String, Object> yaml(String path) throws IOException {
    return new Yaml().load(Files.readString(Path.of(path)));
  }

  private static Map<String, Object> job(Map<String, Object> workflow, String name) {
    return map(map(workflow.get("jobs")).get(name));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(Map<String, Object> job) {
    return (List<Map<String, Object>>) job.get("steps");
  }

  private static Map<String, Object> stepNamed(List<Map<String, Object>> steps, String name) {
    return steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value == null ? Map.of() : (Map<String, Object>) value;
  }
}
