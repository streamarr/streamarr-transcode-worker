package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("FFmpeg Automation Workflow Tests")
class FfmpegAutomationWorkflowTest {

  private static final String DEPENDENCY = "jellyfin/jellyfin-ffmpeg";
  private static final String LOCK_BOT_EMAIL =
      "327604351+streamarr-release[bot]@users.noreply.github.com";

  @Test
  @DisplayName("Should isolate exact FFmpeg release updates when synchronizing the lock")
  void shouldIsolateExactFfmpegReleaseUpdatesWhenSynchronizingTheLock() throws IOException {
    var releasePath = "buildpacks/ffmpeg/release";
    var releaseInput = Files.readString(Path.of(releasePath));
    var release = releaseInput.strip();
    var renovate = new ObjectMapper().readTree(Files.readString(Path.of("renovate.json")));
    var manager =
        nodes(renovate.path("customManagers"))
            .filter(candidate -> managesFile(candidate, releasePath))
            .findFirst()
            .orElseThrow();
    var matchStrings = strings(manager.path("matchStrings")).toList();
    var configuredPattern = Pattern.compile(matchStrings.getFirst());
    var ffmpegRule =
        nodes(renovate.path("packageRules"))
            .filter(rule -> strings(rule.path("matchDepNames")).anyMatch(DEPENDENCY::equals))
            .findFirst()
            .orElseThrow();

    assertThat(matchStrings).hasSize(1);
    assertThat(configuredPattern.matcher(releaseInput).matches()).isTrue();
    assertThat(configuredPattern.matcher(release).matches()).isTrue();
    assertThat(List.of("prefix" + release, release + "-rc1", release + "\nextra"))
        .allSatisfy(input -> assertThat(configuredPattern.matcher(input).matches()).isFalse());
    assertThat(manager.path("datasourceTemplate").asString()).isEqualTo("github-releases");
    assertThat(manager.path("depNameTemplate").asString()).isEqualTo(DEPENDENCY);
    var versionPattern =
        Pattern.compile(manager.path("versioningTemplate").asString().substring(6));
    var futureBuild = versionPattern.matcher("v8.1.2-10");
    assertThat(futureBuild.matches()).isTrue();
    assertThat(futureBuild.group("major")).isEqualTo("8");
    assertThat(futureBuild.group("minor")).isEqualTo("1");
    assertThat(futureBuild.group("patch")).isEqualTo("2");
    assertThat(futureBuild.group("build")).isEqualTo("10");
    assertThat(versionPattern.matcher("v8.1.2-4-rc1").matches()).isFalse();
    assertThat(ffmpegRule.path("groupName").asString()).isEqualTo("FFmpeg runtime");
    assertThat(ffmpegRule.path("automerge").isBoolean()).isTrue();
    assertThat(ffmpegRule.path("automerge").asBoolean()).isFalse();
    assertThat(strings(renovate.path("gitIgnoredAuthors")))
        .containsExactlyInAnyOrder(
            LOCK_BOT_EMAIL, "streamarr-release[bot]@users.noreply.github.com");
  }

  @ParameterizedTest
  @CsvSource({"patch,false", "minor,false", "major,true"})
  @DisplayName(
      "Should apply isolated reviewed FFmpeg update policy when package rules are combined")
  void shouldApplyIsolatedReviewedFfmpegUpdatePolicyWhenPackageRulesAreCombined(
      String updateType, boolean approvalRequired) throws IOException {
    var mapper = new ObjectMapper();
    var renovate = mapper.readTree(Files.readString(Path.of("renovate.json")));
    var policy = mapper.createObjectNode();

    nodes(renovate.path("packageRules"))
        .filter(rule -> matchesRule(rule.path("matchDepNames"), DEPENDENCY))
        .filter(rule -> matchesRule(rule.path("matchPackageNames"), DEPENDENCY))
        .filter(rule -> matchesRule(rule.path("matchUpdateTypes"), updateType))
        .forEach(
            rule ->
                Stream.of("groupName", "automerge", "dependencyDashboardApproval")
                    .filter(rule::has)
                    .forEach(property -> policy.set(property, rule.get(property))));

    assertThat(policy.path("groupName").asString()).isEqualTo("FFmpeg runtime");
    assertThat(policy.path("automerge").isBoolean()).isTrue();
    assertThat(policy.path("automerge").asBoolean()).isFalse();
    assertThat(policy.path("dependencyDashboardApproval").asBoolean()).isEqualTo(approvalRequired);
  }

  private static boolean matchesRule(JsonNode matcher, String value) {
    return matcher.isMissingNode() || strings(matcher).anyMatch(value::equals);
  }

  @Test
  @DisplayName(
      "Should Synchronize Only Canonical Ffmpeg Lock Data When Running Trusted Workflow Code")
  void shouldSynchronizeOnlyCanonicalFfmpegLockDataWhenRunningTrustedWorkflowCode()
      throws IOException {
    var workflowPath = ".github/workflows/sync-ffmpeg-lock.yml";
    var source = Files.readString(Path.of(workflowPath));
    var workflow = yaml(workflowPath);
    var job = map(map(workflow.get("jobs")).get("sync_ffmpeg_lock"));
    var steps = listOfMaps(job.get("steps"));
    var names = steps.stream().map(step -> step.get("name")).toList();
    var trustedCheckout = stepNamed(steps, "Check out trusted resolver");
    var proposedCheckout = stepNamed(steps, "Check out proposed Renovate head");
    var resolve = stepNamed(steps, "Resolve FFmpeg lock from trusted code");
    var prepare = stepNamed(steps, "Prepare synchronized lock");
    var verifyHead = stepNamed(steps, "Verify Renovate head is unchanged");
    var token = stepNamed(steps, "Mint lock bot token");
    var commit = stepNamed(steps, "Commit synchronized lock");
    var tokenIndex = names.indexOf("Mint lock bot token");

    assertThat(source).contains("pull_request_target:", "- 'buildpacks/ffmpeg/release'");
    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat(workflow).containsKey("defaults");
    assertThat(map(map(workflow.get("defaults")).get("run"))).containsEntry("shell", "bash");
    assertThat((String) job.get("if"))
        .contains(
            "github.event.pull_request.user.login == 'renovate[bot]'",
            "github.event.pull_request.head.repo.full_name == github.repository",
            "startsWith(github.event.pull_request.head.ref, 'renovate/')");
    assertThat(map(trustedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.base.sha }}")
        .containsEntry("path", "trusted")
        .containsEntry("persist-credentials", false);
    assertThat(map(proposedCheckout.get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.head.sha }}")
        .containsEntry("path", "proposed")
        .containsEntry("persist-credentials", false);
    assertThat((String) resolve.get("run"))
        .contains(
            "git -C proposed show \"HEAD:buildpacks/ffmpeg/release\"",
            "trusted/buildpacks/ffmpeg/bin/update-lock",
            "--root \"${GITHUB_WORKSPACE}/trusted\"",
            "--release-file \"${release_file}\"")
        .doesNotContain("proposed/buildpacks/ffmpeg/bin/update-lock");
    assertThat((String) prepare.get("run"))
        .contains(
            "git -C proposed hash-object -w \"${GITHUB_WORKSPACE}/trusted/${path}\"",
            "git -C proposed rev-parse \"HEAD:${path}\"",
            "blobs=${blobs}",
            "changed=true");
    assertThat((String) verifyHead.get("run"))
        .contains(
            "git check-ref-format",
            "git -C proposed rev-parse HEAD",
            "gh api",
            "current_head_sha",
            "EXPECTED_HEAD_SHA");
    assertThat(names)
        .containsSubsequence(
            "Resolve FFmpeg lock from trusted code",
            "Prepare synchronized lock",
            "Verify Renovate head is unchanged",
            "Mint lock bot token",
            "Commit synchronized lock");
    assertThat(steps.subList(0, tokenIndex).toString()).doesNotContain("secrets.");
    assertThat((String) token.get("uses"))
        .isEqualTo("actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1");
    assertThat(map(token.get("with")))
        .containsEntry("client-id", "${{ secrets.ORG_STREAMARR_RELEASE_CLIENT_ID }}")
        .containsEntry("private-key", "${{ secrets.ORG_STREAMARR_RELEASE_PRIVATE_KEY }}")
        .containsEntry("permission-contents", "write");
    assertThat((String) commit.get("run"))
        .contains(
            "createCommitOnBranch",
            "expectedHeadOid: $expectedHead",
            "git cat-file blob",
            "fileChanges: {additions: $additions[0], deletions: $deletions}",
            "gh api graphql")
        .doesNotContain("git commit", "git push", "git config", "proposed/buildpacks/", "trusted/");
    assertThat(map(commit.get("env")))
        .containsEntry("EXPECTED_HEAD_SHA", "${{ github.event.pull_request.head.sha }}")
        .containsEntry("GH_TOKEN", "${{ steps.lock_bot.outputs.token }}")
        .containsEntry("BLOBS", "${{ steps.changes.outputs.blobs }}")
        .containsEntry("DELETIONS", "${{ steps.changes.outputs.deletions }}")
        .containsEntry("HEADLINE", "${{ steps.changes.outputs.headline }}");
  }

  @Test
  @DisplayName("Should carry the notice review forward only when trusted code confirms it")
  void shouldCarryTheNoticeReviewForwardOnlyWhenTrustedCodeConfirmsIt() throws IOException {
    var workflowPath = ".github/workflows/sync-ffmpeg-lock.yml";
    var job = map(map(yaml(workflowPath).get("jobs")).get("sync_ffmpeg_lock"));
    var steps = listOfMaps(job.get("steps"));
    var names = steps.stream().map(step -> step.get("name")).toList();
    var review = stepNamed(steps, "Review locked release from trusted code");
    var prepare = stepNamed(steps, "Prepare synchronized lock");
    var prepareRun = (String) prepare.get("run");

    assertThat(names)
        .containsSubsequence(
            "Resolve FFmpeg lock from trusted code",
            "Review locked release from trusted code",
            "Prepare synchronized lock",
            "Mint lock bot token");
    assertThat((String) review.get("run"))
        .contains(
            "trusted/buildpacks/ffmpeg/bin/review-release",
            "--root \"${GITHUB_WORKSPACE}/trusted\"",
            "0) echo \"reviewed=true\"",
            "3) echo \"reviewed=false\"",
            "*) exit \"${status}\"",
            "GITHUB_STEP_SUMMARY")
        .doesNotContain("proposed/");
    assertThat(map(prepare.get("env")))
        .containsEntry("REVIEWED", "${{ steps.review.outputs.reviewed }}");
    assertThat(prepareRun)
        .contains(
            "manifest='buildpacks/ffmpeg/notices/manifest'",
            "if [[ \"${path}\" == \"${manifest}\" && \"${REVIEWED}\" != 'true' ]]; then",
            "find buildpacks/ffmpeg/ffmpeg.lock buildpacks/ffmpeg/SOURCE.txt",
            "buildpacks/ffmpeg/notices -type f -print0",
            "ls-tree -r --name-only HEAD -- buildpacks/ffmpeg/notices");
  }

  @Test
  @DisplayName("Should run the downloaded binary only where no secret or write permission exists")
  void shouldRunTheDownloadedBinaryOnlyWhereNoSecretOrWritePermissionExists() throws IOException {
    var jobs = map(yaml(".github/workflows/sync-ffmpeg-lock.yml").get("jobs"));
    var capture = map(jobs.get("capture_buildconf"));
    var captureSteps = listOfMaps(capture.get("steps"));
    var sync = map(jobs.get("sync_ffmpeg_lock"));
    var syncSteps = listOfMaps(sync.get("steps"));
    var adopt = (String) stepNamed(syncSteps, "Adopt captured build configurations").get("run");

    assertThat(capture).doesNotContainKey("permissions");
    assertThat(captureSteps.toString())
        .contains("trusted/buildpacks/ffmpeg/bin/capture-buildconf")
        .doesNotContain("secrets.", "create-github-app-token", "proposed/buildpacks/");
    assertThat(capture.get("strategy").toString())
        .contains("architecture=amd64", "runner=ubuntu-24.04", "architecture=arm64")
        .contains("runner=ubuntu-24.04-arm");
    assertThat((String) sync.get("needs")).isEqualTo("capture_buildconf");
    assertThat((String) sync.get("if"))
        .contains("!cancelled()", "github.event.pull_request.user.login == 'renovate[bot]'");
    assertThat(syncSteps.toString()).doesNotContain("bin/capture-buildconf", " -buildconf");
    assertThat(adopt)
        .contains(
            "-L \"${capture}\"",
            "> 65536",
            "[^[:print:][:space:]]",
            "'ffmpeg version '*",
            "ffmpeg-uncaptured")
        .doesNotContain("bash \"${capture}\"", "source ", "eval ");
  }

  @Test
  @DisplayName("Should withhold the manifest and request review when the inventory changed")
  void shouldWithholdTheManifestAndRequestReviewWhenTheInventoryChanged() throws IOException {
    var workflow = yaml(".github/workflows/sync-ffmpeg-lock.yml");
    var sync = map(map(workflow.get("jobs")).get("sync_ffmpeg_lock"));
    var steps = listOfMaps(sync.get("steps"));
    var regenerate = stepNamed(steps, "Regenerate notice inputs from trusted code");
    var review = (String) stepNamed(steps, "Review locked release from trusted code").get("run");
    var request = stepNamed(steps, "Request maintainer review of a changed inventory");

    assertThat(map(workflow.get("env"))).containsEntry("REVIEW_LABEL", "ffmpeg-notices-review");
    assertThat(map(sync.get("permissions")))
        .containsOnly(
            Map.entry("contents", "read"),
            Map.entry("issues", "write"),
            Map.entry("pull-requests", "write"));
    assertThat((String) regenerate.get("run"))
        .contains(
            "if ! node trusted/buildpacks/ffmpeg/bin/vendor-notices.mjs",
            "--root \"${GITHUB_WORKSPACE}/trusted/buildpacks/ffmpeg\"",
            "Notice inputs could not be regenerated")
        .doesNotContain("proposed/");
    assertThat(review)
        .contains(
            "grep -q '^Inventory content unchanged'",
            "git -C trusted diff --quiet -- 'buildpacks/ffmpeg/notices/buildconf-*.txt'",
            "status=3",
            "if [[ \"${unchanged}\" == 'true' ]]; then");
    assertThat((String) request.get("if"))
        .isEqualTo(
            "steps.review.outputs.reviewed != 'true' && steps.changes.outputs.approved != 'true'");
    assertThat(map(request.get("env"))).containsEntry("GH_TOKEN", "${{ github.token }}");
    assertThat((String) request.get("run"))
        .contains(
            "gh label create \"${REVIEW_LABEL}\"",
            "--add-label \"${REVIEW_LABEL}\"",
            "approving review",
            "gh pr comment");
  }

  @Test
  @DisplayName("Should bind the manifest only when a maintainer approves the current labelled head")
  void shouldBindTheManifestOnlyWhenAMaintainerApprovesTheCurrentLabelledHead() throws IOException {
    var workflowPath = ".github/workflows/approve-ffmpeg-notices.yml";
    var source = Files.readString(Path.of(workflowPath));
    var workflow = yaml(workflowPath);
    var job = map(map(workflow.get("jobs")).get("bind_ffmpeg_notices"));
    var steps = listOfMaps(job.get("steps"));
    var names = steps.stream().map(step -> step.get("name")).toList();
    var current = stepNamed(steps, "Require approval of the current head");
    var bind =
        (String) stepNamed(steps, "Bind approved notice inventory from trusted code").get("run");
    var commit = (String) stepNamed(steps, "Commit approved manifest").get("run");
    var tokenIndex = names.indexOf("Mint lock bot token");

    assertThat(source).contains("pull_request_review:", "types: [ submitted ]");
    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat((String) job.get("if"))
        .contains(
            "github.event.review.state == 'approved'",
            "contains(fromJSON('[\"OWNER\", \"MEMBER\", \"COLLABORATOR\"]'),"
                + " github.event.review.author_association)",
            "github.event.pull_request.user.login == 'renovate[bot]'",
            "github.event.pull_request.head.repo.full_name == github.repository",
            "startsWith(github.event.pull_request.head.ref, 'renovate/')",
            "contains(github.event.pull_request.labels.*.name, 'ffmpeg-notices-review')");
    assertThat(map(stepNamed(steps, "Check out trusted reviewer").get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.base.sha }}")
        .containsEntry("persist-credentials", false);
    assertThat(map(current.get("env")))
        .containsEntry("APPROVED_SHA", "${{ github.event.review.commit_id }}")
        .containsEntry("EXPECTED_HEAD_SHA", "${{ github.event.pull_request.head.sha }}");
    assertThat((String) current.get("run"))
        .contains("\"${APPROVED_SHA}\" != \"${EXPECTED_HEAD_SHA}\"", "current_head_sha");
    assertThat(bind)
        .contains(
            "trusted/buildpacks/ffmpeg/bin/review-release --approved",
            "--root \"${GITHUB_WORKSPACE}/proposed\"")
        .doesNotContain("proposed/buildpacks/ffmpeg/bin", "proposed/buildpacks/ffmpeg/lib");
    assertThat(names)
        .containsSubsequence(
            "Require approval of the current head",
            "Bind approved notice inventory from trusted code",
            "Mint lock bot token",
            "Commit approved manifest",
            "Clear the review request");
    assertThat(steps.subList(0, tokenIndex).toString()).doesNotContain("secrets.");
    assertThat(commit)
        .contains(
            "createCommitOnBranch",
            "expectedHeadOid: $expectedHead",
            "additions: [{path: \"buildpacks/ffmpeg/notices/manifest\", contents: $contents}]")
        .doesNotContain("git commit", "git push", "deletions");
  }

  @Test
  @DisplayName("Should keep offline validation unconditional when upstream inputs are unchanged")
  void shouldKeepOfflineValidationUnconditionalWhenUpstreamInputsAreUnchanged() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var steps = listOfMaps(map(map(workflow.get("jobs")).get("verify")).get("steps"));
    var offline =
        steps.stream()
            .filter(step -> "./.github/actions/prepare-ffmpeg".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();
    var verify = map(map(workflow.get("jobs")).get("verify"));
    assertThat(map(verify.get("permissions")))
        .containsOnly(Map.entry("contents", "read"), Map.entry("pull-requests", "read"));
    var inputs = stepNamed(steps, "Detect FFmpeg input changes");
    var upstream = stepNamed(steps, "Verify FFmpeg lock against upstream");
    Map<String, Object> filters =
        new Yaml().load(map(inputs.get("with")).get("filters").toString());

    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat(offline).doesNotContainKeys("if", "env");
    assertThat(steps.indexOf(offline)).isLessThan(steps.indexOf(upstream));
    assertThat(filters)
        .containsEntry(
            "changed",
            List.of(
                "buildpacks/ffmpeg/release", "buildpacks/ffmpeg/ffmpeg.lock",
                "buildpacks/ffmpeg/bin/update-lock", "buildpacks/ffmpeg/lib/**"));
    assertThat(upstream).containsEntry("if", "steps.ffmpeg_inputs.outputs.changed == 'true'");
    assertThat(upstream.get("run").toString()).contains("--verify-upstream");
    assertThat(map(upstream.get("env"))).containsEntry("GITHUB_TOKEN", "${{ github.token }}");
    var preparation = Files.readString(Path.of(".github/actions/prepare-ffmpeg/action.yml"));
    assertThat(preparation)
        .contains("buildpacks/ffmpeg/bin/update-lock --check")
        .doesNotContain("--verify-upstream", "secrets.", "pull_request_target");
  }

  @Test
  @DisplayName(
      "Should prepare redistribution materials with the declared Node version when building worker images")
  void shouldPrepareRedistributionMaterialsWithDeclaredNodeVersionWhenBuildingWorkerImages()
      throws IOException {
    var prepare =
        listOfMaps(map(yaml(".github/actions/prepare-ffmpeg/action.yml").get("runs")).get("steps"));
    var node = prepare.getFirst();
    assertThat(node.get("uses").toString()).startsWith("actions/setup-node@");
    assertThat(map(node.get("with")))
        .containsEntry("node-version-file", "buildpacks/ffmpeg/.nvmrc");
    assertThat(
            stepNamed(prepare, "Prepare reviewed redistribution materials").get("run").toString())
        .contains("buildpacks/ffmpeg/bin/update-lock --check", "buildpacks/ffmpeg/bin/prepare");
    var jobs = map(yaml(".github/workflows/ci.yml").get("jobs"));
    for (var entry :
        Map.of(
                "verify",
                "Build and test the executable worker",
                "image",
                "Build the unpublished image and verify its media runtime")
            .entrySet()) {
      var steps = listOfMaps(map(jobs.get(entry.getKey())).get("steps"));
      var offline =
          steps.stream()
              .filter(step -> "./.github/actions/prepare-ffmpeg".equals(step.get("uses")))
              .findFirst()
              .orElseThrow();
      assertThat(steps.indexOf(offline))
          .isLessThan(steps.indexOf(stepNamed(steps, entry.getValue())));
    }
  }

  @Test
  @DisplayName("Should test the locked runtime on both architectures when verifying the worker")
  void shouldTestLockedRuntimeOnBothArchitecturesWhenVerifyingWorker() throws IOException {
    var jobs = map(yaml(".github/workflows/ci.yml").get("jobs"));
    var smokeSteps = listOfMaps(map(jobs.get("smoke")).get("steps"));
    var installation =
        smokeSteps.stream()
            .filter(step -> "./.github/actions/setup-ffmpeg".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();
    var smoke = stepNamed(smokeSteps, "Exercise the worker with real media processes");
    assertThat(smokeSteps.indexOf(installation)).isLessThan(smokeSteps.indexOf(smoke));
    assertThat(smoke.get("run").toString())
        .contains("-Dtest=WorkerMediaSmokeTest", "-Dsurefire.excludedGroups=");
    var image = map(jobs.get("image"));
    var targets = listOfMaps(map(map(image.get("strategy")).get("matrix")).get("include"));
    assertThat(targets.stream().map(target -> target.get("architecture")))
        .containsExactlyInAnyOrder("amd64", "arm64");
    assertThat(
            stepNamed(
                    listOfMaps(image.get("steps")),
                    "Exercise the packaged worker through its public interfaces")
                .get("run")
                .toString())
        .contains("-Dit.test=WorkerImageIT", "-Dworker.image=");
    var setup = map(yaml(".github/actions/setup-ffmpeg/action.yml").get("runs"));
    var setupSteps = listOfMaps(setup.get("steps"));
    assertThat(setupSteps.getFirst()).containsEntry("uses", "./.github/actions/prepare-ffmpeg");
    assertThat(stepNamed(setupSteps, "Install locked FFmpeg").get("run").toString())
        .contains("CNB_TARGET_ARCH=", "uname -m", "buildpacks/ffmpeg/bin/build", "GITHUB_PATH");
    assertThat(smokeSteps.toString()).doesNotContain("apt-get install", "apt-mirrors");
  }

  @Test
  @DisplayName(
      "Should keep tooling version markers outside the application when buildpacks detect dependencies")
  void shouldKeepToolingVersionMarkersOutsideApplicationWhenBuildpacksDetectDependencies() {
    assertThat(Path.of(".nvmrc")).doesNotExist();
    assertThat(Path.of(".node-version")).doesNotExist();
    assertThat(Path.of("buildpacks/ffmpeg/.nvmrc")).isRegularFile();
  }

  @Test
  @DisplayName("Should delegate verified snapshot receipts when main verification succeeds")
  void shouldDelegateVerifiedSnapshotReceiptsWhenMainVerificationSucceeds() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var jobs = map(workflow.get("jobs"));
    var publish = map(jobs.get("publish"));

    assertThat(map(workflow.get("concurrency")))
        .containsEntry("cancel-in-progress", "${{ github.ref != 'refs/heads/main' }}");
    assertThat(publish)
        .containsEntry("needs", List.of("build", "verify"))
        .containsEntry("if", "github.event_name == 'push' && github.ref == 'refs/heads/main'");
    assertThat(publish.get("uses").toString())
        .matches("streamarr/streamarr-workflows/.github/workflows/publish-image.yml@[a-f0-9]{40}");
    assertThat(map(publish.get("with")))
        .containsEntry("image-repository", "streamarr/streamarr-transcode-worker")
        .containsEntry("source-revision", "${{ github.sha }}")
        .containsEntry("version", "${{ needs.verify.outputs.version }}")
        .containsEntry("artifact-pattern", "worker-native-*-${{ github.sha }}")
        .containsEntry("publication-kind", "snapshot");
    assertThat(map(publish.get("secrets")))
        .containsEntry("dockerhub-username", "${{ secrets.DOCKERHUB_USERNAME }}")
        .containsEntry("dockerhub-token", "${{ secrets.DOCKERHUB_TOKEN }}");
    var verify = map(jobs.get("verify"));
    assertThat(map(verify.get("outputs")))
        .containsEntry("version", "${{ steps.version.outputs.version }}");
    assertThat(stepNamed(listOfMaps(verify.get("steps")), "Read Maven version"))
        .containsEntry("id", "version");
  }

  private static Stream<JsonNode> nodes(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false);
  }

  private static Stream<String> strings(JsonNode values) {
    return nodes(values).map(JsonNode::asString);
  }

  private static boolean managesFile(JsonNode manager, String path) {
    return strings(manager.path("managerFilePatterns"))
        .map(FfmpegAutomationWorkflowTest::renovatePattern)
        .anyMatch(pattern -> pattern.matcher(path).find());
  }

  private static Pattern renovatePattern(String value) {
    var lastSlash = value.lastIndexOf('/');
    return Pattern.compile(value.substring(1, lastSlash));
  }

  private static Map<String, Object> yaml(String file) throws IOException {
    try (var input = Files.newInputStream(Path.of(file))) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> stepNamed(
      List<Map<String, Object>> steps, String expectedName) {
    return steps.stream()
        .filter(step -> expectedName.equals(step.get("name")))
        .findFirst()
        .orElseThrow();
  }
}
