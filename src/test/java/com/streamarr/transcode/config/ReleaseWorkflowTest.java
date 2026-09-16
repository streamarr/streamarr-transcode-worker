package com.streamarr.transcode.config;

import static com.streamarr.transcode.config.ReleaseWorkflowFixture.step;
import static com.streamarr.transcode.config.ReleaseWorkflowFixture.steps;
import static com.streamarr.transcode.config.ReleaseWorkflowFixture.workflow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Release Workflow Tests")
class ReleaseWorkflowTest {

  @Test
  @DisplayName("Should publish merged releases first when maintaining release pull requests")
  void shouldPublishMergedReleasesFirstWhenMaintainingReleasePullRequests() throws Exception {
    var workflow = workflow();
    // YAML 1.1 interprets the GitHub Actions "on" key as the boolean true.
    var triggers = (Map<?, ?>) workflow.get(true);
    assertThat(triggers.keySet()).isEqualTo(Set.of("push", "workflow_dispatch"));
    assertThat(triggers.get("push")).asInstanceOf(MAP).containsEntry("branches", List.of("main"));
    assertThat(workflow).asInstanceOf(MAP).containsEntry("permissions", Map.of("contents", "read"));
    var concurrency = (Map<?, ?>) workflow.get("concurrency");
    assertThat(concurrency)
        .asInstanceOf(MAP)
        .containsEntry("group", "release-please-main")
        .containsEntry("cancel-in-progress", false);
    var steps = steps();
    var publish = step("Publish merged releases");
    var maintain = step("Maintain release PR");
    var guard = step("Verify merged releases were processed");

    assertThat(steps.indexOf(publish)).isLessThan(steps.indexOf(guard));
    assertThat(steps.indexOf(guard)).isLessThan(steps.indexOf(maintain));
    for (var action : List.of(publish, maintain)) {
      assertThat(action)
          .asInstanceOf(MAP)
          .containsEntry(
              "uses", "googleapis/release-please-action@45996ed1f6d02564a971a2fa1b5860e934307cf7");
      var options = (Map<?, ?>) action.get("with");
      assertThat(options)
          .asInstanceOf(MAP)
          .containsEntry("token", "${{ steps.bot.outputs.token }}")
          .containsEntry("target-branch", "main");
    }
    assertThat(publish.get("with"))
        .asInstanceOf(MAP)
        .containsEntry("skip-github-pull-request", true);
    assertThat(maintain.get("with")).asInstanceOf(MAP).containsEntry("skip-github-release", true);
  }

  @Test
  @DisplayName("Should block the next release when a merged release remains pending")
  void shouldBlockNextReleaseWhenMergedReleaseRemainsPending(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.repository(
        """
        {"pulls":[{"number":42,"base":"main","state":"merged","labels":["autorelease: pending"]}]}
        """);

    var result = fixture.run("Verify merged releases were processed");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output())
        .contains("Unprocessed merged release PR #42", "restore its release title and body");
  }

  @Test
  @DisplayName("Should authenticate as the release App when maintaining worker releases")
  void shouldAuthenticateAsReleaseAppWhenMaintainingWorkerReleases() throws Exception {
    var token = step("Mint release bot token");
    var inputs = (Map<?, ?>) token.get("with");

    assertThat(token)
        .asInstanceOf(MAP)
        .containsEntry("id", "bot")
        .containsEntry(
            "uses", "actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1");
    assertThat(inputs)
        .asInstanceOf(MAP)
        .containsEntry("client-id", "${{ secrets.ORG_STREAMARR_RELEASE_CLIENT_ID }}")
        .containsEntry("private-key", "${{ secrets.ORG_STREAMARR_RELEASE_PRIVATE_KEY }}");
    for (var permission : List.of("contents", "pull-requests", "issues")) {
      assertThat(inputs).asInstanceOf(MAP).containsEntry("permission-" + permission, "write");
    }
    assertThat(steps().indexOf(token)).isLessThan(steps().indexOf(step("Publish merged releases")));
  }

  @Test
  @DisplayName("Should allow the next release when no merged release remains pending")
  void shouldAllowNextReleaseWhenNoMergedReleaseRemainsPending(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.repository(
        """
        {"pulls":[
          {"number":42,"base":"main","state":"merged","labels":["autorelease: tagged"]},
          {"number":43,"base":"main","state":"open","labels":["autorelease: pending"]},
          {"number":44,"base":"other","state":"merged","labels":["autorelease: pending"]}
        ]}
        """);

    var result = fixture.run("Verify merged releases were processed");

    assertThat(result.exitCode()).as(result.output()).isZero();
  }

  @Test
  @DisplayName("Should stop release preparation when GitHub cannot report pending releases")
  void shouldStopReleasePreparationWhenGitHubCannotReportPendingReleases(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.repository(
        """
        {"unavailable":true}
        """);

    var result = fixture.run("Verify merged releases were processed");

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("GitHub repository unavailable");
  }

  @ParameterizedTest
  @CsvSource({"true, 0", "false, 1", "null, 1"})
  @DisplayName("Should require repository auto-merge when preparing release automation")
  void shouldRequireRepositoryAutoMergeWhenPreparingReleaseAutomation(
      String enabled, int expectedExit, @TempDir Path directory) throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.repository("{\"allow_auto_merge\":" + enabled + "}");

    var result = fixture.run("Verify repository auto-merge");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(expectedExit);
  }

  @ParameterizedTest
  @CsvSource({"pending, false", "snapshot, true"})
  @DisplayName("Should queue auto-merge only when the release PR is a snapshot")
  void shouldQueueAutoMergeOnlyWhenReleasePrIsSnapshot(
      String label, boolean queued, @TempDir Path directory) throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    var revision = "e".repeat(40);
    fixture.repository("{\"head\":{\"sha\":\"" + revision + "\"}}");
    fixture.releasePullRequest(
        """
        {"number":43,"title":"chore(main): release 0.1.1-SNAPSHOT","labels":["autorelease: %s"]}
        """
            .formatted(label));

    var result = fixture.run("Queue snapshot auto-merge");

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(fixture.repository().containsKey("autoMerge")).isEqualTo(queued);
    if (!queued) {
      return;
    }

    assertThat(fixture.repository())
        .asInstanceOf(MAP)
        .containsEntry(
            "autoMerge",
            Map.of(
                "number",
                43,
                "head",
                revision,
                "subject",
                "chore(main): release 0.1.1-SNAPSHOT",
                "body",
                "",
                "method",
                "squash"));
  }

  @Test
  @DisplayName("Should leave the snapshot unqueued when GitHub returns an invalid head revision")
  void shouldLeaveSnapshotUnqueuedWhenGitHubReturnsInvalidHeadRevision(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleaseWorkflowFixture(directory);
    fixture.repository(
        """
        {"head":{"sha":"missing"}}
        """);
    fixture.releasePullRequest(
        """
        {"number":43,"title":"chore(main): release 0.1.1-SNAPSHOT","labels":["autorelease: snapshot"]}
        """);

    var result = fixture.run("Queue snapshot auto-merge");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release PR has no head revision");
    assertThat(fixture.repository().containsKey("autoMerge")).isFalse();
  }
}
