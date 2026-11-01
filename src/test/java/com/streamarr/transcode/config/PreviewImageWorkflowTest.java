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
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Preview Image Workflow Tests")
class PreviewImageWorkflowTest {

  private static final String PREVIEW_WORKFLOW = ".github/workflows/preview.yml";
  private static final String CLEANUP_WORKFLOW = ".github/workflows/preview-cleanup.yml";
  private static final String AUTHORIZE_JOB = "authorize_preview";
  private static final String BUILD_JOB = "build_preview";
  private static final String PUBLISH_JOB = "publish_preview";
  private static final String CLEANUP_JOB = "delete_preview_tag";
  private static final String CURRENT_HEAD_CHECK =
      "Require the authorized revision of an open pull request";
  private static final String PULL_REQUEST = "42";
  private static final String REPOSITORY = "streamarr/streamarr-transcode-worker";
  private static final String BUILT_IMAGE = "streamarr-worker:preview-amd64";
  private static final String PREVIEW_TAG =
      "index.docker.io/streamarr/streamarr-transcode-worker:pr-" + PULL_REQUEST;
  private static final String HEAD_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
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
    assertThat(job(workflow, AUTHORIZE_JOB).get("if").toString().strip())
        .isEqualTo(
            "github.event.issue.pull_request"
                + " && github.event.comment.body == '/deploy-preview'"
                + " && contains(fromJSON('[\"OWNER\",\"MEMBER\",\"COLLABORATOR\"]'),"
                + " github.event.comment.author_association)");
  }

  @Test
  @DisplayName("Should authorize with pinned actions and no repository code when a command arrives")
  void shouldAuthorizeWithPinnedActionsAndNoRepositoryCodeWhenACommandArrives() throws IOException {
    var authorize = job(yaml(PREVIEW_WORKFLOW), AUTHORIZE_JOB);

    assertThat(authorize).containsEntry("permissions", Map.of("pull-requests", "read"));
    assertThat(map(authorize.get("outputs")))
        .containsOnly(entry("revision", "${{ steps.authorize.outputs.revision }}"));
    assertThat(steps(authorize))
        .allSatisfy(step -> assertThat(step.get("uses").toString()).matches(PINNED_ACTION));
    assertThat(authorize.toString()).doesNotContain("actions/checkout", "secrets.");
  }

  @ParameterizedTest(name = "{0} access, {1} pull request from {2}")
  @CsvSource({
    "admin, open, " + REPOSITORY + ", 0, output revision=" + HEAD_REVISION,
    "write, open, " + REPOSITORY + ", 0, output revision=" + HEAD_REVISION,
    "read, open, " + REPOSITORY + ", 1, needs write access",
    "none, open, " + REPOSITORY + ", 1, needs write access",
    "write, closed, " + REPOSITORY + ", 1, only for open pull requests",
    "write, open, someone/streamarr-transcode-worker, 1, only for branches of this repository",
    "write, open, '', 1, only for branches of this repository"
  })
  @DisplayName("Should build only an open branch of this repository when a writer comments")
  void shouldBuildOnlyAnOpenBranchOfThisRepositoryWhenAWriterComments(
      String permission, String state, String headRepository, int exitCode, String outcome)
      throws Exception {
    var authorize = job(yaml(PREVIEW_WORKFLOW), AUTHORIZE_JOB);

    var result =
        runScript(
            stepNamed(
                steps(authorize),
                "Require write access and an open pull request from this repository"),
            GitHubState.openBranchByWriter()
                .permission(permission)
                .state(state)
                .headRepository(headRepository)
                .build(),
            Map.of());

    assertThat(result.exitCode()).as(result.output()).isEqualTo(exitCode);
    assertThat(result.output()).contains(outcome);
  }

  @Test
  @DisplayName(
      "Should check out the authorized revision without credentials when building a preview")
  void shouldCheckOutTheAuthorizedRevisionWithoutCredentialsWhenBuildingAPreview()
      throws IOException {
    var build = job(yaml(PREVIEW_WORKFLOW), BUILD_JOB);
    var checkout = steps(build).getFirst();

    assertThat(build)
        .containsEntry("needs", AUTHORIZE_JOB)
        .containsEntry("permissions", Map.of("contents", "read"))
        .containsEntry("runs-on", "ubuntu-24.04");
    assertThat(checkout.get("uses").toString()).startsWith("actions/checkout@");
    assertThat(map(checkout.get("with")))
        .containsOnly(
            entry("ref", "${{ needs.authorize_preview.outputs.revision }}"),
            entry("persist-credentials", false));
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
        .containsOnly(entry("PREVIEW_REVISION", "${{ needs.authorize_preview.outputs.revision }}"));
  }

  @Test
  @DisplayName("Should run only pinned actions and inline steps when publishing with credentials")
  void shouldRunOnlyPinnedActionsAndInlineStepsWhenPublishingWithCredentials() throws IOException {
    var publish = job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB);
    var steps = steps(publish);

    assertThat(publish)
        .containsEntry("needs", List.of(AUTHORIZE_JOB, BUILD_JOB))
        .containsEntry("permissions", Map.of("pull-requests", "write"));
    assertThat(steps)
        .allSatisfy(
            step -> {
              if (step.get("uses") instanceof String action) {
                assertThat(action).matches(PINNED_ACTION).doesNotStartWith("actions/checkout@");
              }
            })
        .allSatisfy(
            step -> assertThat(String.valueOf(step.get("run"))).doesNotContain("./", ".github/"));
    assertThat(steps.indexOf(stepNamed(steps, "Load preview image")))
        .isLessThan(steps.indexOf(stepNamed(steps, CURRENT_HEAD_CHECK)));
    assertThat(steps.indexOf(stepNamed(steps, CURRENT_HEAD_CHECK)))
        .isLessThan(steps.indexOf(stepNamed(steps, "Login to Docker Hub")));
  }

  @ParameterizedTest(name = "{0} pull request at {1}")
  @CsvSource({
    "open, " + HEAD_REVISION + ", 0, ''",
    "closed, " + HEAD_REVISION + ", 1, closed before its preview was published",
    "open, bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, 1, moved to bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  })
  @DisplayName(
      "Should publish only the current head of an open pull request when the build finishes")
  void shouldPublishOnlyTheCurrentHeadOfAnOpenPullRequestWhenTheBuildFinishes(
      String state, String head, int exitCode, String message) throws Exception {
    var publish = job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB);

    var result =
        runScript(
            stepNamed(steps(publish), CURRENT_HEAD_CHECK),
            GitHubState.openBranchByWriter().state(state).head(head).build(),
            Map.of("needs.authorize_preview.outputs.revision", HEAD_REVISION));

    assertThat(result.exitCode()).as(result.output()).isEqualTo(exitCode);
    assertThat(result.output()).contains(message);
  }

  @Test
  @DisplayName("Should hold cleanup until publication ends when a pull request closes mid-publish")
  void shouldHoldCleanupUntilPublicationEndsWhenAPullRequestClosesMidPublish() throws IOException {
    var publication = map(job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB).get("concurrency"));
    var cleanup = map(job(yaml(CLEANUP_WORKFLOW), CLEANUP_JOB).get("concurrency"));

    assertThat(resolve(publication, Map.of("github.event.issue.number", PULL_REQUEST)))
        .containsOnly(
            entry("group", "preview-image-42"),
            entry("cancel-in-progress", "false"),
            entry("queue", "max"));
    assertThat(resolve(cleanup, Map.of("github.event.pull_request.number", PULL_REQUEST)))
        .containsOnly(
            entry("group", "preview-image-42"),
            entry("cancel-in-progress", "false"),
            entry("queue", "max"));
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
    var report = stepNamed(steps(job(yaml(PREVIEW_WORKFLOW), PUBLISH_JOB)), "Comment on PR");

    var result =
        runScript(
            report,
            GitHubState.openBranchByWriter().build(),
            Map.of("needs.authorize_preview.outputs.revision", HEAD_REVISION));

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output())
        .contains("comment on 42:")
        .contains("docker pull streamarr/streamarr-transcode-worker:pr-42")
        .contains(HEAD_REVISION);
  }

  @Test
  @DisplayName("Should clean up with read-only access when a pull request closes")
  void shouldCleanUpWithReadOnlyAccessWhenAPullRequestCloses() throws IOException {
    var workflow = yaml(CLEANUP_WORKFLOW);

    assertThat(map(workflow.get(true)))
        .containsOnly(entry("pull_request", Map.of("types", List.of("closed"))));
    assertThat(workflow).containsEntry("permissions", Map.of("contents", "read"));
    assertThat(job(workflow, CLEANUP_JOB))
        .containsEntry("if", "github.event.pull_request.head.repo.full_name == github.repository");
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
    var cleanup = job(workflow, CLEANUP_JOB);
    var expressions =
        Map.of(
            "github.event.pull_request.number", PULL_REQUEST,
            "secrets.DOCKERHUB_USERNAME", "streamarr",
            "secrets.DOCKERHUB_TOKEN", "hub-access-token");
    return runSteps(List.of(map(cleanup.get("env"))), expressions, steps(cleanup));
  }

  private ScriptCommand.Result runScript(
      Map<String, Object> step, GitHubState state, Map<String, String> expressions)
      throws Exception {
    var harness =
        """
        const state = JSON.parse(process.env['FAKE_GITHUB']);
        const context = {
          repo: { owner: 'streamarr', repo: 'streamarr-transcode-worker' },
          issue: { number: 42 },
          payload: { comment: { id: 7, user: { login: 'commenter' } } },
        };
        const github = { rest: {
          repos: { getCollaboratorPermissionLevel: async ({ username }) =>
            ({ data: { permission: username === 'commenter' ? state.permission : 'none' } }) },
          pulls: { get: async ({ pull_number }) => ({ data: pull_number === 42 ? {
            state: state.state,
            head: { sha: state.head, repo: state.headRepository ? { full_name: state.headRepository } : null },
          } : null }) },
          issues: { createComment: async (comment) =>
            console.log(`comment on ${comment.issue_number}:\n${comment.body}`) },
        } };
        const core = { setOutput: (name, value) => console.log(`output ${name}=${value}`) };
        (async () => {
        %s
        })().catch((error) => { console.error(error.message); process.exitCode = 1; });
        """
            .formatted(map(step.get("with")).get("script"));
    var script = Files.writeString(directory.resolve("github-script.cjs"), harness);
    var command = new ProcessBuilder("node", script.toString());
    command.environment().putAll(resolve(map(step.get("env")), expressions));
    command
        .environment()
        .put(
            "FAKE_GITHUB",
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "permission", state.permission(),
                        "state", state.state(),
                        "head", state.head(),
                        "headRepository", state.headRepository())));
    return run(command);
  }

  @Builder
  private record GitHubState(String permission, String state, String head, String headRepository) {
    static GitHubStateBuilder openBranchByWriter() {
      return builder()
          .permission("write")
          .state("open")
          .head(HEAD_REVISION)
          .headRepository(REPOSITORY);
    }
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
