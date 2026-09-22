package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("FFmpeg Automation Workflow Tests")
class FfmpegAutomationWorkflowTest {

  private static final String DEPENDENCY = "jellyfin/jellyfin-ffmpeg";
  private static final String LOCK_BOT_EMAIL =
      "327604351+streamarr-release[bot]@users.noreply.github.com";
  private static final String LOCK = "buildpacks/ffmpeg/ffmpeg.lock";
  private static final String MANIFEST = "buildpacks/ffmpeg/notices/manifest";
  private static final List<String> REVIEWED_INPUTS =
      List.of(MANIFEST, "buildpacks/ffmpeg/notices/sources.json", "buildpacks/ffmpeg/SOURCE.txt");
  private static final List<String> REGENERATED_INPUTS =
      List.of("buildpacks/ffmpeg/SOURCE.txt", "buildpacks/ffmpeg/notices/sources.json", MANIFEST);
  private static final String APPROVAL_WORKFLOW = ".github/workflows/approve-ffmpeg-notices.yml";
  private static final String RENOVATE_HEAD =
      "github.event.pull_request.user.login == 'renovate[bot]'"
          + " && github.event.pull_request.head.repo.full_name == github.repository"
          + " && startsWith(github.event.pull_request.head.ref, 'renovate/')";
  private static final String SYNCHRONIZED_RENOVATE_HEAD = "!cancelled() && " + RENOVATE_HEAD;
  private static final String CAPTURED_BUILDCONF = buildconfNotice("amd64");
  private static final String UNCAPTURED_BUILDCONF = buildconfNotice("arm64");
  private static final String HEAD_ONLY_NOTICE = "buildpacks/ffmpeg/notices/x264/COPYING";
  private static final String ANOTHER_HEAD = "0".repeat(40);
  private static final List<String> ARCHITECTURES = List.of("amd64", "arm64");

  @TempDir Path temporaryDirectory;

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
    assertThat((String) job.get("if")).isEqualTo(SYNCHRONIZED_RENOVATE_HEAD);
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
        .containsEntry("REVIEWED", "${{ steps.review.outputs.reviewed }}")
        .containsEntry("SENDER", "${{ github.event.sender.login }}");
    assertThat(prepareRun)
        .contains(
            "manifest=buildpacks/ffmpeg/notices/manifest",
            "if [[ \"${path}\" == \"${manifest}\" && \"${REVIEWED}\" != 'true' ]]; then",
            "if [[ \"${path}\" != \"${lock}\" && \"${SENDER}\" != 'renovate[bot]' ]]; then",
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
    assertThat((String) capture.get("if")).isEqualTo(RENOVATE_HEAD);
    assertThat((String) sync.get("needs")).isEqualTo("capture_buildconf");
    assertThat((String) sync.get("if")).isEqualTo(SYNCHRONIZED_RENOVATE_HEAD);
    assertThat(syncSteps.toString()).doesNotContain("bin/capture-buildconf", " -buildconf");
    assertThat(adopt)
        .contains(
            "-L \"${capture}\"",
            "> 65536",
            "LC_ALL=C tr -d '[:print:]\\n' <\"${capture}\" | wc -c",
            "'ffmpeg version '*",
            "ffmpeg-uncaptured")
        .doesNotContain("bash \"${capture}\"", "source ", "eval ");
  }

  @ParameterizedTest
  @ValueSource(ints = {0x00, 0x0b, 0x0c, 0x0d, 0x1b, 0xe9})
  @DisplayName("Should refuse a captured build configuration holding a byte the banner never has")
  void shouldRefuseACapturedBuildConfigurationHoldingAByteTheBannerNeverHas(int unprintable)
      throws Exception {
    captureHolding("amd64", "--enable-gpl%c --enable-libx264".formatted(unprintable));
    captureHolding("arm64", "--enable-gpl");

    var result = adoptStep().execute();

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(adoptedNotices()).isEmptyDirectory();
    assertThat(temporaryDirectory.resolve("ffmpeg-captured")).doesNotExist();
  }

  @ParameterizedTest
  @MethodSource("hostileCaptures")
  @DisplayName("Should hold the reviewed build configuration when a capture is not a banner")
  void shouldHoldTheReviewedBuildConfigurationWhenACaptureIsNotABanner(
      String architecture, HostileCapture shape) throws Exception {
    reviewedBuildConfigurations();
    for (var companion : otherArchitectures(architecture)) {
      captureHolding(companion, "--enable-gpl");
    }
    captureShapedAs(architecture, shape);

    var result = adoptStep().execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output())
        .contains("Unexpected %s build configuration capture".formatted(architecture));
    assertThat(adoptedNotices().resolve(buildconfFile(architecture)))
        .hasSameBinaryContentAs(Path.of(buildconfNotice(architecture)));
    assertThat(recordedLines("ffmpeg-captured")).doesNotContain(buildconfNotice(architecture));
  }

  @ParameterizedTest
  @MethodSource("architectures")
  @DisplayName("Should report the architecture whose build configuration never arrived")
  void shouldReportTheArchitectureWhoseBuildConfigurationNeverArrived(String architecture)
      throws Exception {
    reviewedBuildConfigurations();
    for (var captured : otherArchitectures(architecture)) {
      captureCopiedFrom(captured, Path.of(buildconfNotice(captured)));
    }

    var result = adoptStep().execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(recordedLines("ffmpeg-uncaptured"))
        .containsExactly(
            "- Build configuration of %s could not be captured".formatted(architecture));
    assertThat(recordedLines("ffmpeg-captured")).doesNotContain(buildconfNotice(architecture));
  }

  @Test
  @DisplayName("Should adopt the captured build configurations that the reviewed notices hold")
  void shouldAdoptTheCapturedBuildConfigurationsThatTheReviewedNoticesHold() throws Exception {
    for (var architecture : ARCHITECTURES) {
      captureCopiedFrom(architecture, Path.of(buildconfNotice(architecture)));
    }

    var result = adoptStep().execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readAllLines(temporaryDirectory.resolve("ffmpeg-captured")))
        .containsExactly(CAPTURED_BUILDCONF, UNCAPTURED_BUILDCONF);
    for (var architecture : ARCHITECTURES) {
      assertThat(adoptedNotices().resolve(buildconfFile(architecture)))
          .hasSameBinaryContentAs(Path.of(buildconfNotice(architecture)));
    }
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
            "node trusted/buildpacks/ffmpeg/bin/vendor-notices.mjs",
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
    var source = Files.readString(Path.of(APPROVAL_WORKFLOW));
    var workflow = yaml(APPROVAL_WORKFLOW);
    var job = map(map(workflow.get("jobs")).get("bind_ffmpeg_notices"));
    var steps = approvalSteps();
    var names = steps.stream().map(step -> step.get("name")).toList();
    var current = stepNamed(steps, "Require approval of the current head");
    var bind =
        (String) stepNamed(steps, "Bind approved notice inventory from trusted code").get("run");
    var commit = (String) stepNamed(steps, "Commit approved manifest").get("run");
    var tokenIndex = names.indexOf("Mint lock bot token");

    assertThat(source).contains("pull_request_review:", "types: [ submitted ]");
    assertThat(steps.toString()).doesNotContain("author_association");
    assertThat(map(workflow.get("permissions"))).containsOnly(Map.entry("contents", "read"));
    assertThat((String) job.get("if"))
        .isEqualTo(
            "github.event.review.state == 'approved' && "
                + RENOVATE_HEAD
                + " && contains(github.event.pull_request.labels.*.name,"
                + " 'ffmpeg-notices-review')");
    assertThat(map(stepNamed(steps, "Check out trusted reviewer").get("with")))
        .containsEntry("ref", "${{ github.event.pull_request.base.sha }}")
        .containsEntry("persist-credentials", false);
    assertThat(map(stepNamed(steps, "Require a maintaining approver").get("env")))
        .containsEntry("GH_TOKEN", "${{ github.token }}")
        .containsEntry("APPROVER", "${{ github.event.review.user.login }}");
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
            "Require a maintaining approver",
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

  @ParameterizedTest
  @CsvSource({"admin,true", "maintain,true", "write,false", "triage,false", "read,false"})
  @DisplayName("Should let only an approver who maintains this repository bind the inventory")
  void shouldLetOnlyAnApproverWhoMaintainsThisRepositoryBindTheInventory(String role, boolean binds)
      throws Exception {
    var requests = temporaryDirectory.resolve("requests");
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(
        commands,
        "gh",
        """
        printf '%s\\n' "$*" >>"${FAKE_REQUESTS}"
        printf '%s\\n' "${FAKE_ROLE}"
        """);

    var result =
        maintainingApproverStep()
            .prependPath(commands)
            .environment("FAKE_REQUESTS", requests.toString())
            .environment("FAKE_ROLE", role)
            .execute();

    assertThat(Files.readAllLines(requests))
        .containsExactly(
            "api repos/streamarr/streamarr-transcode-worker/collaborators/an-approver/permission"
                + " --jq .role_name");
    if (binds) {
      assertThat(result.exitCode()).as(result.output()).isZero();
      return;
    }

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(result.output()).contains("an-approver", role);
  }

  @Test
  @DisplayName("Should refuse the approval when the approver's permission cannot be read")
  void shouldRefuseTheApprovalWhenTheApproversPermissionCannotBeRead() throws Exception {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(
        commands,
        "gh",
        """
        printf 'gh: Not Found (HTTP 404)\\n' >&2
        exit 1
        """);

    var result = maintainingApproverStep().prependPath(commands).execute();

    assertThat(result.exitCode()).as(result.output()).isNotZero();
  }

  @ParameterizedTest
  @CsvSource({"the approval,false", "the branch,false", "the checkout,false", "nothing,true"})
  @DisplayName("Should bind only the Renovate head the approval, branch and checkout agree on")
  void shouldBindOnlyTheRenovateHeadTheApprovalBranchAndCheckoutAgreeOn(
      String moved, boolean covered) throws Exception {
    var workspace = approvedWorkspace();
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(commands, "gh", "printf '%s\\n' \"${FAKE_HEAD_SHA}\"");
    // The checked-out revision is the one this run cannot restate, so moving it moves the rest.
    var expected = moved.equals("the checkout") ? ANOTHER_HEAD : headOf(workspace);

    var result =
        currentHeadStep(workspace)
            .prependPath(commands)
            .environment("APPROVED_SHA", moved.equals("the approval") ? ANOTHER_HEAD : expected)
            .environment("EXPECTED_HEAD_SHA", expected)
            .environment("FAKE_HEAD_SHA", moved.equals("the branch") ? ANOTHER_HEAD : expected)
            .execute();

    if (covered) {
      assertThat(result.exitCode()).as(result.output()).isZero();
      return;
    }

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("The approval does not cover the current Renovate head");
  }

  @ParameterizedTest
  @CsvSource({"the branch,false", "the checkout,false", "nothing,true"})
  @DisplayName("Should commit only the Renovate head the branch and checkout agree on")
  void shouldCommitOnlyTheRenovateHeadTheBranchAndCheckoutAgreeOn(String moved, boolean unchanged)
      throws Exception {
    var workspace = workspaceWhoseHeadDiffersFromTheTrustedCheckout();
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(commands, "gh", "printf '%s\\n' \"${FAKE_HEAD_SHA}\"");
    // The checked-out revision is the one this run cannot restate, so moving it moves the rest.
    var expected = moved.equals("the checkout") ? ANOTHER_HEAD : headOf(workspace);

    var result =
        unchangedHeadStep(workspace)
            .prependPath(commands)
            .environment("EXPECTED_HEAD_SHA", expected)
            .environment("FAKE_HEAD_SHA", moved.equals("the branch") ? ANOTHER_HEAD : expected)
            .execute();

    if (unchanged) {
      assertThat(result.exitCode()).as(result.output()).isZero();
      return;
    }

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Renovate head moved while resolving the FFmpeg lock");
  }

  @Test
  @DisplayName("Should refuse a branch name git will not accept before asking for its head")
  void shouldRefuseABranchNameGitWillNotAcceptBeforeAskingForItsHead() throws Exception {
    var workspace = workspaceWhoseHeadDiffersFromTheTrustedCheckout();
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var requests = temporaryDirectory.resolve("requests");
    ScriptCommand.writeFake(commands, "gh", "printf '%s\\n' \"$*\" >>\"${FAKE_REQUESTS}\"");

    var result =
        unchangedHeadStep(workspace)
            .prependPath(commands)
            .environment("FAKE_REQUESTS", requests.toString())
            .environment("HEAD_REF", "renovate/..")
            .environment("EXPECTED_HEAD_SHA", headOf(workspace))
            .execute();

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(requests).doesNotExist();
  }

  @ParameterizedTest
  @CsvSource({
    "true, renovate[bot], true, true",
    "true, streamarr-release[bot], false, false",
    "true, a-maintainer, false, false",
    "false, renovate[bot], true, true",
    "false, a-maintainer, false, true"
  })
  @DisplayName(
      "Should replace pushed reviewed inputs only when Renovate started a confirmed review")
  void shouldReplacePushedReviewedInputsOnlyWhenRenovateStartedAConfirmedReview(
      String reviewed, String sender, boolean replaced, boolean carriesTheManifest)
      throws Exception {
    var workspace = workspaceWhoseHeadDiffersFromTheTrustedCheckout();
    var outputs = temporaryDirectory.resolve("outputs");
    recordRegeneratedInputs();

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", reviewed)
            .environment("SENDER", sender)
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    var carried =
        REGENERATED_INPUTS.stream()
            .filter(path -> path.equals(MANIFEST) ? carriesTheManifest : replaced);
    // The step walks the trusted checkout with find, whose directory order is the filesystem's.
    assertThat(pathsOf(outputs, "blobs"))
        .containsExactlyInAnyOrderElementsOf(Stream.concat(Stream.of(LOCK), carried).toList());
    assertThat(output(outputs, "headline"))
        .isEqualTo(
            reviewed.equals("true")
                ? "behavioral: synchronize FFmpeg lock and unchanged notice review"
                : "behavioral: synchronize FFmpeg lock and notice inputs for review");
    assertThat(result.output().lines().filter(line -> line.startsWith("::warning ")))
        .hasSize(replaced ? 0 : REVIEWED_INPUTS.size() - (reviewed.equals("true") ? 0 : 1))
        .allSatisfy(
            warning -> assertThat(warning).containsAnyOf(REVIEWED_INPUTS.toArray(String[]::new)));
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  @DisplayName("Should commit only what this run produced when a regeneration or capture failed")
  void shouldCommitOnlyWhatThisRunProducedWhenARegenerationOrCaptureFailed(
      boolean regenerated, boolean captured) throws Exception {
    var workspace =
        workspaceWhoseHeadDiffersFromTheTrustedCheckout(
            List.of(CAPTURED_BUILDCONF, UNCAPTURED_BUILDCONF), List.of(HEAD_ONLY_NOTICE));
    var outputs = temporaryDirectory.resolve("outputs");
    if (captured) {
      recordCapturedBuildConfiguration(CAPTURED_BUILDCONF);
    }
    if (regenerated) {
      recordRegeneratedInputs();
    }

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", "false")
            .environment("SENDER", "renovate[bot]")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(pathsOf(outputs, "blobs"))
        .containsExactlyInAnyOrderElementsOf(
            Stream.of(
                    Stream.of(LOCK, MANIFEST),
                    captured ? Stream.of(CAPTURED_BUILDCONF) : Stream.<String>empty(),
                    regenerated
                        ? REGENERATED_INPUTS.stream().filter(path -> !path.equals(MANIFEST))
                        : Stream.<String>empty())
                .flatMap(paths -> paths)
                .toList());
    assertThat(pathsOf(outputs, "deletions"))
        .containsExactlyElementsOf(regenerated ? List.of(HEAD_ONLY_NOTICE) : List.of());
  }

  @Test
  @DisplayName("Should keep a notice only the head carries when a maintainer started the run")
  void shouldKeepANoticeOnlyTheHeadCarriesWhenAMaintainerStartedTheRun() throws Exception {
    var workspace =
        workspaceWhoseHeadDiffersFromTheTrustedCheckout(List.of(), List.of(HEAD_ONLY_NOTICE));
    var outputs = temporaryDirectory.resolve("outputs");
    recordRegeneratedInputs();

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", "false")
            .environment("SENDER", "a-maintainer")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(pathsOf(outputs, "deletions")).isEmpty();
  }

  @Test
  @DisplayName("Should keep an approved binding when the approval's own commit reran the run")
  void shouldKeepAnApprovedBindingWhenTheApprovalsOwnCommitReranTheRun() throws Exception {
    var workspace = workspaceWhoseApprovedHeadHoldsANoticeTheBaseDoesNot();
    var outputs = temporaryDirectory.resolve("outputs");
    recordRegeneratedInputs();

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", "false")
            .environment("SENDER", "streamarr-release[bot]")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output().lines().filter(line -> line.startsWith("::warning ")))
        .anySatisfy(warning -> assertThat(warning).contains(HEAD_ONLY_NOTICE, "stays as pushed"));
    assertThat(pathsOf(outputs, "blobs")).isEmpty();
    assertThat(pathsOf(outputs, "deletions")).isEmpty();
    assertThat(output(outputs, "changed")).isEqualTo("false");
    assertThat(output(outputs, "approved")).isEqualTo("true");
  }

  @Test
  @DisplayName("Should ask for a review when a run that commits nothing leaves a rejected head")
  void shouldAskForAReviewWhenARunThatCommitsNothingLeavesARejectedHead() throws Exception {
    var workspace = workspaceWhoseApprovedHeadHoldsANoticeTheBaseDoesNot();
    var outputs = temporaryDirectory.resolve("outputs");
    recordRegeneratedInputs();
    ScriptCommand.writeFake(
        workspace.resolve("trusted/buildpacks/ffmpeg/bin"), "update-lock", "exit 1");

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", "false")
            .environment("SENDER", "a-maintainer")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(output(outputs, "changed")).isEqualTo("false");
    assertThat(output(outputs, "approved")).isEqualTo("false");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should withdraw a bound manifest when an unreviewed run commits an inventory")
  void shouldWithdrawABoundManifestWhenAnUnreviewedRunCommitsAnInventory(boolean producedAnything)
      throws Exception {
    var workspace =
        producedAnything
            ? workspaceWhoseHeadDiffersFromTheTrustedCheckout()
            : workspaceWhoseHeadMatchesTheTrustedCheckout();
    var outputs = temporaryDirectory.resolve("outputs");
    recordRegeneratedInputs();

    var result =
        prepareStep(workspace)
            .environment("REVIEWED", "false")
            .environment("SENDER", "renovate[bot]")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    if (!producedAnything) {
      assertThat(pathsOf(outputs, "blobs")).isEmpty();
      assertThat(pathsOf(outputs, "deletions")).isEmpty();
      assertThat(output(outputs, "changed")).isEqualTo("false");
      assertThat(output(outputs, "approved")).isEqualTo("true");
      return;
    }

    assertThat(pathsOf(outputs, "blobs")).contains(MANIFEST);
    assertThat(contentCommittedFor(workspace, outputs, MANIFEST))
        .isEqualTo(Files.readString(workspace.resolve("trusted").resolve(MANIFEST)))
        .isNotEqualTo(Files.readString(workspace.resolve("proposed").resolve(MANIFEST)));
    assertThat(output(outputs, "changed")).isEqualTo("true");
    assertThat(output(outputs, "approved")).isEqualTo("false");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should ask for a local regeneration when this run could not regenerate the inputs")
  void shouldAskForALocalRegenerationWhenThisRunCouldNotRegenerateTheInputs(boolean regenerated)
      throws Exception {
    var review = "- Build configuration changed; compare notices/buildconf-*.txt";
    Files.writeString(
        temporaryDirectory.resolve("ffmpeg-review-block"), "```text\n" + review + "\n```\n");
    var requests = temporaryDirectory.resolve("requests");
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(commands, "gh", "printf '%s\\n' \"$*\" >>\"${FAKE_REQUESTS}\"");
    if (regenerated) {
      recordRegeneratedInputs();
    }

    var result =
        requestReviewStep()
            .prependPath(commands)
            .environment("FAKE_REQUESTS", requests.toString())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    var comment = Files.readString(temporaryDirectory.resolve("ffmpeg-comment.md"));
    assertThat(comment).contains("```text\n" + review + "\n```", "**approving review**");
    assertThat(Files.readAllLines(requests))
        .anySatisfy(request -> assertThat(request).contains("--add-label ffmpeg-notices-review"))
        .anySatisfy(
            request ->
                assertThat(request)
                    .contains(
                        "pr comment 21",
                        "--body-file " + temporaryDirectory.resolve("ffmpeg-comment.md")));
    if (regenerated) {
      assertThat(comment).doesNotContain("vendor-notices");
      return;
    }

    assertThat(comment)
        .contains(
            "buildpacks/ffmpeg/ffmpeg.lock", "    node buildpacks/ffmpeg/bin/vendor-notices.mjs");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "zz ##[stop-commands]resume-token",
        "zz ##[error title=FFmpeg notice review]inventory unchanged, safe to merge",
        "zz ##[add-mask]x264%0Aneeds human review%0A",
        "zz <img src=https://example.invalid/pixel>"
      })
  @DisplayName("Should log upstream names as inert data when the review needs a person")
  void shouldLogUpstreamNamesAsInertDataWhenTheReviewNeedsAPerson(String upstreamName)
      throws Exception {
    var review =
        "FFmpeg notice inventory needs human review: upstream changed \"%s\""
            .formatted(upstreamName);

    var step = reviewStep().reviewerPrinting(review).reviewerExitingWith(3).execute();

    assertThat(step.result().exitCode()).as(step.result().output()).isZero();
    assertThat(output(step.outputs(), "reviewed")).isEqualTo("false");
    assertThat(linesTheRunnerReadsForCommands(step.result().output()))
        .containsExactly(
            "::warning title=FFmpeg notice review::"
                + (REGENERATED + "\n" + review).replace("%", "%25").replace("\n", "%0A"));
    assertThat(step.result().output()).containsPattern("(?m)^::stop-commands::[0-9a-f]{32}$");
    assertThat(Files.readAllLines(step.summary()))
        .containsExactly("```text", REGENERATED, review, "```");
  }

  @ParameterizedTest
  @EnumSource(UnconfirmedReview.class)
  @DisplayName("Should withhold the notice review when this run could not confirm the inventory")
  void shouldWithholdTheNoticeReviewWhenThisRunCouldNotConfirmTheInventory(
      UnconfirmedReview withheld) throws Exception {
    var confirmation = "Inventory content unchanged from v8.1.2-4 to v8.1.2-5";
    var reviewer = reviewStep().reviewerPrinting(confirmation).reviewerExitingWith(0);

    var withholding =
        switch (withheld) {
          case CHANGED_INVENTORY -> reviewer.regenerationReportingAChangedInventory();
          case UNCAPTURED_ARCHITECTURE -> reviewer.architectureNotCaptured();
          case CHANGED_BUILD_CONFIGURATION -> reviewer.buildConfigurationChangedSinceTheReview();
        };

    var step = withholding.execute();

    assertThat(step.result().exitCode()).as(step.result().output()).isZero();
    assertThat(output(step.outputs(), "reviewed")).isEqualTo("false");
    assertThat(Files.readAllLines(step.summary())).doesNotContain(confirmation);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "- vendored x264 from upstream file \"notes: Inventory content unchanged\"",
        "  Inventory content unchanged; only the FFmpeg revision was rebound."
      })
  @DisplayName("Should withhold the notice review when upstream text only quotes the confirmation")
  void shouldWithholdTheNoticeReviewWhenUpstreamTextOnlyQuotesTheConfirmation(String quoted)
      throws Exception {
    var step =
        reviewStep()
            .regenerationQuoting(quoted)
            .reviewerPrinting(REGENERATED)
            .reviewerExitingWith(0)
            .execute();

    assertThat(step.result().exitCode()).as(step.result().output()).isZero();
    assertThat(output(step.outputs(), "reviewed")).isEqualTo("false");
  }

  @Test
  @DisplayName("Should confirm the notice review when trusted code reviewed the locked release")
  void shouldConfirmTheNoticeReviewWhenTrustedCodeReviewedTheLockedRelease() throws Exception {
    var confirmation = "Inventory content unchanged from v8.1.2-4 to v8.1.2-5";

    var step = reviewStep().reviewerPrinting(confirmation).reviewerExitingWith(0).execute();

    assertThat(step.result().exitCode()).as(step.result().output()).isZero();
    assertThat(output(step.outputs(), "reviewed")).isEqualTo("true");
    assertThat(Files.readAllLines(step.summary())).contains(confirmation);
  }

  @ParameterizedTest
  @ValueSource(strings = {"```", "   ``` ", "````", "```````"})
  @DisplayName("Should read an upstream fence as report text when asking a maintainer to approve")
  void shouldReadAnUpstreamFenceAsReportTextWhenAskingAMaintainerToApprove(String fence)
      throws Exception {
    var report =
        """
        %s
        ### Inventory content unchanged
        Upstream changed nothing. **Approve now to bind `notices/manifest`.**"""
            .formatted(fence);
    recordRegeneratedInputs();
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(commands, "gh", ":");

    var reviewed = reviewStep().regenerationReporting(report).reviewerExitingWith(3).execute();
    var requested = requestReviewStep().prependPath(commands).execute();

    assertThat(reviewed.result().exitCode()).as(reviewed.result().output()).isZero();
    assertThat(requested.exitCode()).as(requested.output()).isZero();
    assertThat(linesRenderedAsMarkdown(reviewed.summary())).isEmpty();
    assertThat(linesRenderedAsMarkdown(temporaryDirectory.resolve("ffmpeg-comment.md")))
        .containsExactly(
            "### FFmpeg notice inventory needs a maintainer review",
            "Read this pull request's diff of `buildpacks/ffmpeg/notices` and `SOURCE.txt`.",
            "Submit an **approving review** to bind `notices/manifest`; CI stays red until then.");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "zz ##[stop-commands]resume-token",
        "zz ##[error title=FFmpeg notice review]inventory unchanged, safe to merge",
        "zz ##[add-mask]x264"
      })
  @DisplayName("Should log a regeneration report as inert data when upstream names its lines")
  void shouldLogARegenerationReportAsInertDataWhenUpstreamNamesItsLines(String upstreamName)
      throws Exception {
    var report = "- Pin moved, license text unchanged: %s".formatted(upstreamName);

    var result = regenerateStep().generatorReporting(report).generatorExitingWith(0).execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(linesTheRunnerReadsForCommands(result.output())).isEmpty();
    assertThat(result.output()).containsPattern("(?m)^::stop-commands::[0-9a-f]{32}$");
    assertThat(Files.readAllLines(temporaryDirectory.resolve("ffmpeg-review")))
        .containsExactly(report);
    assertThat(temporaryDirectory.resolve("ffmpeg-regenerated")).exists();
  }

  @Test
  @DisplayName("Should keep a failed regeneration's diagnostics away from the runner")
  void shouldKeepAFailedRegenerationsDiagnosticsAwayFromTheRunner() throws Exception {
    var diagnostic = "Unable to fetch https://example.invalid/x264/##[add-mask]COPYING";

    var result = regenerateStep().generatorReporting(diagnostic).generatorExitingWith(1).execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(linesTheRunnerReadsForCommands(result.output())).isEmpty();
    assertThat(Files.readAllLines(temporaryDirectory.resolve("ffmpeg-review")))
        .containsExactly(
            diagnostic, "- Notice inputs could not be regenerated; run vendor-notices.mjs locally");
    assertThat(temporaryDirectory.resolve("ffmpeg-regenerated")).doesNotExist();
  }

  @Test
  @DisplayName("Should bound the posted report when upstream floods it with one long line")
  void shouldBoundThePostedReportWhenUpstreamFloodsItWithOneLongLine() throws Exception {
    var flood = "Unable to fetch https://example.invalid/" + "x".repeat(200_000);

    var reviewed = reviewStep().regenerationReporting(flood).reviewerExitingWith(3).execute();

    assertThat(reviewed.result().exitCode()).as(reviewed.result().output()).isZero();
    assertThat(Files.size(reviewed.summary())).isLessThan(20_000);
    assertThat(Files.readAllLines(reviewed.summary())).endsWith("[report truncated]", "```");
  }

  @Test
  @DisplayName("Should resume runner commands before failing when the review fails")
  void shouldResumeRunnerCommandsBeforeFailingWhenTheReviewFails() throws Exception {
    var diagnostic = "jq: error: Cannot iterate over string (\"##[add-mask]x264\")";

    var step = reviewStep().reviewerFailingWith(diagnostic).reviewerExitingWith(1).execute();

    assertThat(step.result().exitCode()).as(step.result().output()).isEqualTo(1);
    assertThat(step.result().output()).contains(diagnostic);
    assertThat(linesTheRunnerReadsForCommands(step.result().output())).isEmpty();
    assertThat(step.outputs()).doesNotExist();
  }

  @Test
  @DisplayName("Should keep offline validation unconditional when upstream inputs are unchanged")
  void shouldKeepOfflineValidationUnconditionalWhenUpstreamInputsAreUnchanged() throws IOException {
    var workflow = yaml(".github/workflows/ci.yml");
    var tooling = map(map(workflow.get("jobs")).get("tooling"));
    var steps = listOfMaps(tooling.get("steps"));
    var offline =
        steps.stream()
            .filter(step -> "./.github/actions/prepare-ffmpeg".equals(step.get("uses")))
            .findFirst()
            .orElseThrow();
    assertThat(map(tooling.get("permissions")))
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
                "tooling",
                "Verify redistribution tooling",
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
  @DisplayName("Should withhold worker images when tooling verification fails")
  void shouldWithholdWorkerImagesWhenToolingVerificationFails() throws IOException {
    var jobs = map(yaml(".github/workflows/ci.yml").get("jobs"));
    var build = map(jobs.get("build"));
    var gate =
        stepNamed(
            listOfMaps(build.get("steps")), "Require successful verification and smoke tests");

    assertThat(map(jobs.get("image")))
        .containsEntry("needs", List.of("verify", "tooling", "smoke"));
    assertThat(build).containsEntry("needs", List.of("verify", "tooling", "smoke", "image"));
    assertThat(map(gate.get("env"))).containsEntry("TOOLING_RESULT", "${{ needs.tooling.result }}");
    assertThat(gate.get("run").toString()).contains("test \"$TOOLING_RESULT\" = success");
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

  private Path workspaceWhoseHeadDiffersFromTheTrustedCheckout() throws Exception {
    return workspaceWhoseHeadDiffersFromTheTrustedCheckout(List.of(), List.of());
  }

  private Path workspaceWhoseHeadDiffersFromTheTrustedCheckout(
      List<String> alsoInBothCheckouts, List<String> onlyOnTheHead) throws Exception {
    var workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    var shared =
        Stream.of(Stream.of(LOCK), REVIEWED_INPUTS.stream(), alsoInBothCheckouts.stream())
            .flatMap(paths -> paths)
            .toList();
    for (var path : shared) {
      for (var checkout : List.of("trusted", "proposed")) {
        writeCheckoutCopy(workspace.resolve(checkout), path);
      }
    }
    for (var path : onlyOnTheHead) {
      writeCheckoutCopy(workspace.resolve("proposed"), path);
    }
    seedProposedHead(workspace);
    return workspace;
  }

  private Path workspaceWhoseHeadMatchesTheTrustedCheckout() throws Exception {
    var workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    for (var path : Stream.concat(Stream.of(LOCK), REVIEWED_INPUTS.stream()).toList()) {
      for (var checkout : List.of("trusted", "proposed")) {
        var file = workspace.resolve(checkout).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%s as both checkouts hold it\n".formatted(path));
      }
    }
    ScriptCommand.writeFake(
        Files.createDirectories(workspace.resolve("trusted/buildpacks/ffmpeg/bin")),
        "update-lock",
        "exit 0");
    seedProposedHead(workspace);
    return workspace;
  }

  private Path workspaceWhoseApprovedHeadHoldsANoticeTheBaseDoesNot() throws Exception {
    var workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    var trusted = workspace.resolve("trusted");
    var proposed = workspace.resolve("proposed");
    for (var path : Stream.concat(Stream.of(LOCK), REVIEWED_INPUTS.stream()).toList()) {
      for (var checkout : List.of(trusted, proposed)) {
        writeCheckoutCopy(checkout, path);
      }
    }
    // An earlier run already synchronized the lock, and an approving review bound the inventory
    // the maintainer pushed, so only the notice they added is missing from the base.
    Files.copy(trusted.resolve(LOCK), proposed.resolve(LOCK), StandardCopyOption.REPLACE_EXISTING);
    writeCheckoutCopy(proposed, HEAD_ONLY_NOTICE);
    ScriptCommand.writeFake(
        Files.createDirectories(trusted.resolve("buildpacks/ffmpeg/bin")), "update-lock", "exit 0");
    seedProposedHead(workspace);
    return workspace;
  }

  private String contentCommittedFor(Path workspace, Path outputs, String path) throws Exception {
    ScriptCommand.writeFake(
        temporaryDirectory, "read-proposed-blob", "git -C \"$1\" cat-file blob \"$2\"");
    var blob =
        nodes(new ObjectMapper().readTree(output(outputs, "blobs")))
            .filter(entry -> path.equals(entry.path("path").asString()))
            .map(entry -> entry.path("blob").asString())
            .findFirst()
            .orElseThrow();
    var read =
        ScriptCommand.of(temporaryDirectory.resolve("read-proposed-blob"))
            .argument(workspace.resolve("proposed").toString())
            .argument(blob)
            .execute();

    assertThat(read.exitCode()).as(read.output()).isZero();
    return read.output();
  }

  private void seedProposedHead(Path workspace) throws Exception {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "commit-proposed-head",
        """
        cd "$1"
        export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
        git init --quiet
        git add .
        git -c user.name=Renovate -c user.email=renovate@example.invalid commit --quiet -m head
        """);
    var seeded =
        ScriptCommand.of(temporaryDirectory.resolve("commit-proposed-head"))
            .argument(workspace.resolve("proposed").toString())
            .execute();
    assertThat(seeded.exitCode()).as(seeded.output()).isZero();
  }

  private static void writeCheckoutCopy(Path checkout, String path) throws IOException {
    var file = checkout.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(
        file, "%s as the %s checkout holds it\n".formatted(path, checkout.getFileName()));
  }

  private ScriptCommand prepareStep(Path workspace) throws IOException {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "prepare-synchronized-lock",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(syncSteps(), "Prepare synchronized lock").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("prepare-synchronized-lock"))
        .environment("GITHUB_WORKSPACE", workspace.toString())
        .environment("RUNNER_TEMP", temporaryDirectory.toString())
        .environment("GITHUB_OUTPUT", temporaryDirectory.resolve("outputs").toString());
  }

  private enum UnconfirmedReview {
    CHANGED_INVENTORY,
    UNCAPTURED_ARCHITECTURE,
    CHANGED_BUILD_CONFIGURATION
  }

  private enum HostileCapture {
    NUL_BYTE,
    OVERSIZED,
    FOREIGN_BANNER,
    SYMBOLIC_LINK
  }

  private static Stream<Arguments> hostileCaptures() {
    return ARCHITECTURES.stream()
        .flatMap(
            architecture ->
                Stream.of(HostileCapture.values()).map(shape -> Arguments.of(architecture, shape)));
  }

  private static Stream<String> architectures() {
    return ARCHITECTURES.stream();
  }

  private static List<String> otherArchitectures(String architecture) {
    return ARCHITECTURES.stream().filter(each -> !each.equals(architecture)).toList();
  }

  private void captureShapedAs(String architecture, HostileCapture shape) throws IOException {
    var capture = capturePath(architecture);
    switch (shape) {
      case NUL_BYTE -> captureHolding(architecture, "--enable-gpl\u0000 --enable-libx264");
      case OVERSIZED -> captureHolding(architecture, " --enable-libx264".repeat(4096));
      case FOREIGN_BANNER -> Files.writeString(capture, "<html>ffmpeg version 7.1.1</html>\n");
      case SYMBOLIC_LINK -> {
        captureHolding(architecture, "--enable-gpl");
        Files.createSymbolicLink(
            capture, Files.move(capture, temporaryDirectory.resolve("capture-elsewhere")));
      }
    }
  }

  private List<String> recordedLines(String record) throws IOException {
    var recorded = temporaryDirectory.resolve(record);
    return Files.exists(recorded) ? Files.readAllLines(recorded) : List.of();
  }

  private void reviewedBuildConfigurations() throws IOException {
    Files.createDirectories(adoptedNotices());
    for (var architecture : ARCHITECTURES) {
      Files.copy(
          Path.of(buildconfNotice(architecture)),
          adoptedNotices().resolve(buildconfFile(architecture)));
    }
  }

  private void captureHolding(String architecture, String configuration) throws IOException {
    Files.writeString(
        capturePath(architecture),
        "ffmpeg version 7.1.1-Jellyfin\nconfiguration: %s\n".formatted(configuration));
  }

  private void captureCopiedFrom(String architecture, Path reviewed) throws IOException {
    Files.copy(reviewed, capturePath(architecture));
  }

  private Path capturePath(String architecture) throws IOException {
    return Files.createDirectories(temporaryDirectory.resolve("buildconf"))
        .resolve(buildconfFile(architecture));
  }

  private Path adoptedNotices() {
    return temporaryDirectory.resolve("workspace/trusted/buildpacks/ffmpeg/notices");
  }

  private ScriptCommand adoptStep() throws IOException {
    Files.createDirectories(adoptedNotices());
    ScriptCommand.writeFake(
        temporaryDirectory,
        "adopt-captured-build-configurations",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(syncSteps(), "Adopt captured build configurations").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("adopt-captured-build-configurations"))
        .environment("GITHUB_WORKSPACE", temporaryDirectory.resolve("workspace").toString())
        .environment("RUNNER_TEMP", temporaryDirectory.toString());
  }

  private GeneratorStep regenerateStep() throws IOException {
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(
        commands,
        "node",
        """
        printf '%s\\n' "${FAKE_REPORT}"
        exit "${FAKE_GENERATOR_EXIT}"
        """);
    ScriptCommand.writeFake(
        temporaryDirectory,
        "regenerate-notice-inputs",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(syncSteps(), "Regenerate notice inputs from trusted code").get("run"));
    return new GeneratorStep(
        ScriptCommand.of(temporaryDirectory.resolve("regenerate-notice-inputs"))
            .prependPath(commands)
            .environment(
                "GITHUB_WORKSPACE",
                Files.createDirectory(temporaryDirectory.resolve("workspace")).toString())
            .environment("RUNNER_TEMP", temporaryDirectory.toString()));
  }

  private Path approvedWorkspace() throws Exception {
    var workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    writeCheckoutCopy(workspace.resolve("proposed"), LOCK);
    seedProposedHead(workspace);
    return workspace;
  }

  private String headOf(Path workspace) throws Exception {
    ScriptCommand.writeFake(
        temporaryDirectory, "read-proposed-head", "git -C \"$1/proposed\" rev-parse HEAD");
    var read =
        ScriptCommand.of(temporaryDirectory.resolve("read-proposed-head"))
            .argument(workspace.toString())
            .execute();

    assertThat(read.exitCode()).as(read.output()).isZero();
    return read.output().strip();
  }

  private ScriptCommand currentHeadStep(Path workspace) throws IOException {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "require-approval-of-the-current-head",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(approvalSteps(), "Require approval of the current head").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("require-approval-of-the-current-head"))
        .environment("GITHUB_WORKSPACE", workspace.toString())
        .environment("GH_TOKEN", "the workflow token")
        .environment("GITHUB_REPOSITORY", "streamarr/streamarr-transcode-worker")
        .environment("HEAD_REF", "renovate/jellyfin-ffmpeg")
        .environment("PR_NUMBER", "21");
  }

  private ScriptCommand unchangedHeadStep(Path workspace) throws IOException {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "verify-renovate-head-is-unchanged",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(syncSteps(), "Verify Renovate head is unchanged").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("verify-renovate-head-is-unchanged"))
        .environment("GITHUB_WORKSPACE", workspace.toString())
        .environment("GH_TOKEN", "the workflow token")
        .environment("GITHUB_REPOSITORY", "streamarr/streamarr-transcode-worker")
        .environment("HEAD_REF", "renovate/jellyfin-ffmpeg")
        .environment("PR_NUMBER", "21");
  }

  private ScriptCommand maintainingApproverStep() throws IOException {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "require-maintaining-approver",
        (String) stepNamed(approvalSteps(), "Require a maintaining approver").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("require-maintaining-approver"))
        .environment("GH_TOKEN", "the workflow token")
        .environment("GITHUB_REPOSITORY", "streamarr/streamarr-transcode-worker")
        .environment("APPROVER", "an-approver");
  }

  private ScriptCommand requestReviewStep() throws IOException {
    ScriptCommand.writeFake(
        temporaryDirectory,
        "request-maintainer-review",
        (String)
            stepNamed(syncSteps(), "Request maintainer review of a changed inventory").get("run"));
    return ScriptCommand.of(temporaryDirectory.resolve("request-maintainer-review"))
        .environment("RUNNER_TEMP", temporaryDirectory.toString())
        .environment("GH_TOKEN", "the workflow token")
        .environment("GITHUB_REPOSITORY", "streamarr/streamarr-transcode-worker")
        .environment("PR_NUMBER", "21")
        .environment(
            "REVIEW_LABEL",
            (String)
                map(yaml(".github/workflows/sync-ffmpeg-lock.yml").get("env")).get("REVIEW_LABEL"));
  }

  private void recordRegeneratedInputs() throws IOException {
    Files.createFile(temporaryDirectory.resolve("ffmpeg-regenerated"));
  }

  private void recordCapturedBuildConfiguration(String path) throws IOException {
    Files.writeString(temporaryDirectory.resolve("ffmpeg-captured"), path + "\n");
  }

  private static List<Map<String, Object>> approvalSteps() throws IOException {
    return listOfMaps(
        map(map(yaml(APPROVAL_WORKFLOW).get("jobs")).get("bind_ffmpeg_notices")).get("steps"));
  }

  private static List<Map<String, Object>> syncSteps() throws IOException {
    return listOfMaps(
        map(map(yaml(".github/workflows/sync-ffmpeg-lock.yml").get("jobs")).get("sync_ffmpeg_lock"))
            .get("steps"));
  }

  private static List<String> pathsOf(Path outputs, String name) throws IOException {
    return nodes(new ObjectMapper().readTree(output(outputs, name)))
        .map(entry -> entry.path("path").asString())
        .toList();
  }

  private static final String REGENERATED =
      "Inventory content unchanged; only the FFmpeg revision was rebound.";

  private ReviewStep reviewStep() throws Exception {
    var workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    var commands = Files.createDirectories(workspace.resolve("trusted/buildpacks/ffmpeg/bin"));
    Files.writeString(temporaryDirectory.resolve("ffmpeg-review"), REGENERATED + "\n");
    ScriptCommand.writeFake(
        commands,
        "review-release",
        """
        printf '%s' "${FAKE_REVIEW:-}"
        printf '%s' "${FAKE_DIAGNOSTIC:-}" >&2
        exit "${FAKE_REVIEW_EXIT}"
        """);
    var trusted = workspace.resolve("trusted");
    Files.copy(
        Path.of(CAPTURED_BUILDCONF),
        Files.createDirectories(trusted.resolve("buildpacks/ffmpeg/notices"))
            .resolve(buildconfFile("amd64")));
    for (var arguments :
        List.of(
            List.of("git", "init", "--quiet"),
            List.of("git", "add", "-A"),
            List.of(
                "git",
                "-c",
                "user.email=t@t",
                "-c",
                "user.name=t",
                "commit",
                "--no-gpg-sign",
                "--quiet",
                "-m",
                "trusted"))) {
      var process = new ProcessBuilder(arguments).directory(trusted.toFile()).start();
      assertThat(process.waitFor()).isZero();
    }
    ScriptCommand.writeFake(
        temporaryDirectory,
        "review-locked-release",
        "cd \"${GITHUB_WORKSPACE}\"\n"
            + stepNamed(syncSteps(), "Review locked release from trusted code").get("run"));
    return new ReviewStep(
        ScriptCommand.of(temporaryDirectory.resolve("review-locked-release"))
            .environment("GITHUB_WORKSPACE", workspace.toString())
            .environment("RUNNER_TEMP", temporaryDirectory.toString())
            .environment("GITHUB_OUTPUT", temporaryDirectory.resolve("outputs").toString())
            .environment("GITHUB_STEP_SUMMARY", temporaryDirectory.resolve("summary").toString()),
        temporaryDirectory);
  }

  // The runner reads `::command::` at the start of a line and the legacy `##[command]` anywhere
  // in one, except between `::stop-commands::<token>` and `::<token>::`. A line that parses as
  // the former is never read for the latter.
  private static List<String> linesTheRunnerReadsForCommands(String log) {
    var read = new ArrayList<String>();
    var resume = Optional.<String>empty();
    for (var line : log.lines().toList()) {
      if (resume.isPresent()) {
        resume = resume.filter(token -> !line.equals("::" + token + "::"));
        continue;
      }
      if (line.startsWith("::stop-commands::")) {
        resume = Optional.of(line.substring("::stop-commands::".length()));
        continue;
      }

      read.add(line);
    }
    return read;
  }

  // CommonMark: a fence of N backticks opens a code block, and only a line of N or more backticks
  // indented at most three spaces and followed by nothing else closes it. Everything else a reader
  // sees is Markdown the document author wrote.
  private static List<String> linesRenderedAsMarkdown(Path document) throws IOException {
    var rendered = new ArrayList<String>();
    var open = 0;
    for (var line : Files.readAllLines(document)) {
      var text = line.stripLeading();
      var backticks = text.length() - text.replaceFirst("^`+", "").length();
      var fence = line.length() - text.length() <= 3 && backticks >= Math.max(open, 3);
      if (open > 0) {
        open = fence && text.substring(backticks).isBlank() ? 0 : open;
        continue;
      }
      if (fence) {
        open = backticks;
        continue;
      }
      if (!line.isBlank()) {
        rendered.add(line);
      }
    }
    return rendered;
  }

  private record GeneratorStep(ScriptCommand command) {

    private GeneratorStep generatorReporting(String report) {
      command.environment("FAKE_REPORT", report);
      return this;
    }

    private GeneratorStep generatorExitingWith(int exitCode) {
      command.environment("FAKE_GENERATOR_EXIT", Integer.toString(exitCode));
      return this;
    }

    private ScriptCommand.Result execute() throws IOException, InterruptedException {
      return command.execute();
    }
  }

  private record ReviewStep(ScriptCommand command, Path temporaryDirectory) {

    private ReviewStep regenerationReporting(String report) throws IOException {
      Files.writeString(
          temporaryDirectory.resolve("ffmpeg-review"), REGENERATED + "\n" + report + "\n");
      return this;
    }

    private ReviewStep regenerationQuoting(String upstreamText) throws IOException {
      Files.writeString(temporaryDirectory.resolve("ffmpeg-review"), upstreamText + "\n");
      return this;
    }

    private ReviewStep regenerationReportingAChangedInventory() throws IOException {
      Files.writeString(
          temporaryDirectory.resolve("ffmpeg-review"),
          "Inventory content changed: upstream added mbedtls/LICENSE\n");
      return this;
    }

    private ReviewStep architectureNotCaptured() throws IOException {
      Files.writeString(
          temporaryDirectory.resolve("ffmpeg-uncaptured"),
          "- Build configuration of arm64 could not be captured\n");
      return this;
    }

    private ReviewStep buildConfigurationChangedSinceTheReview() throws IOException {
      Files.writeString(
          temporaryDirectory.resolve("workspace/trusted").resolve(CAPTURED_BUILDCONF),
          "ffmpeg version 7.1.1-Jellyfin\nconfiguration: --enable-gpl --enable-nonfree\n");
      return this;
    }

    private ReviewStep reviewerPrinting(String review) {
      command.environment("FAKE_REVIEW", review + "\n");
      return this;
    }

    private ReviewStep reviewerFailingWith(String diagnostic) {
      command.environment("FAKE_DIAGNOSTIC", diagnostic + "\n");
      return this;
    }

    private ReviewStep reviewerExitingWith(int exitCode) {
      command.environment("FAKE_REVIEW_EXIT", Integer.toString(exitCode));
      return this;
    }

    private ExecutedReviewStep execute() throws IOException, InterruptedException {
      return new ExecutedReviewStep(command.execute(), temporaryDirectory);
    }
  }

  private record ExecutedReviewStep(ScriptCommand.Result result, Path temporaryDirectory) {

    private Path outputs() {
      return temporaryDirectory.resolve("outputs");
    }

    private Path summary() {
      return temporaryDirectory.resolve("summary");
    }
  }

  private static String output(Path outputs, String name) throws IOException {
    var prefix = name + "=";
    return Files.readAllLines(outputs).stream()
        .filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length()))
        .collect(Collectors.joining());
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

  private static String buildconfFile(String architecture) {
    return "buildconf-%s.txt".formatted(architecture);
  }

  private static String buildconfNotice(String architecture) {
    return "buildpacks/ffmpeg/notices/" + buildconfFile(architecture);
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
