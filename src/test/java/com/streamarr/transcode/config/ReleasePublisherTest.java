package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Publisher Tests")
class ReleasePublisherTest {

  @Test
  @DisplayName("Should retain latest when GitHub cannot identify the latest release")
  void shouldRetainLatestWhenGitHubCannotIdentifyLatestRelease(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.publishedNativeImages();
    fixture.releaseState(
        """
        {"unavailable":true}
        """);

    var result = fixture.runStep("Publish latest multi-architecture image");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(23);
    assertThat(fixture.registry().get("indexes"))
        .asInstanceOf(MAP)
        .containsEntry(
            "index.docker.io/streamarr/streamarr-transcode-worker:latest",
            List.of(Map.of("version", "1.2.2")));
  }

  @Test
  @DisplayName("Should forward validated release outputs when coordinating publication jobs")
  void shouldForwardValidatedReleaseOutputsWhenCoordinatingPublicationJobs() throws Exception {
    var workflow = ReleasePublisherFixture.workflow();
    assertThat(workflow.get("permissions")).isEqualTo(Map.of());
    assertThat(workflow.get("concurrency"))
        .asInstanceOf(MAP)
        .containsEntry("group", "publish-release")
        .containsEntry("cancel-in-progress", false);
    var validation = ReleasePublisherFixture.job("validate_release");
    assertThat(validation.get("outputs"))
        .isEqualTo(
            Map.of(
                "version", "${{ steps.release.outputs.version }}",
                "revision", "${{ steps.release.outputs.revision }}"));
    var release = ReleasePublisherFixture.step("Verify tagged Maven version");
    assertThat(release).asInstanceOf(MAP).containsEntry("id", "release");
    assertThat(release.get("env"))
        .asInstanceOf(MAP)
        .containsEntry("GH_TOKEN", "${{ github.token }}")
        .containsEntry(
            "RELEASE_TAG",
            "${{ github.event_name == 'workflow_dispatch' && inputs.tag || github.event.release.tag_name }}");
    assertThat(ReleasePublisherFixture.steps("validate_release").getFirst().get("with"))
        .asInstanceOf(MAP)
        .containsEntry(
            "ref",
            "${{ github.event_name == 'workflow_dispatch' && format('refs/tags/{0}', inputs.tag) || github.sha }}")
        .containsEntry("fetch-depth", 0)
        .containsEntry("persist-credentials", false);
    assertThat(ReleasePublisherFixture.job("publish_release"))
        .asInstanceOf(MAP)
        .containsEntry("needs", List.of("validate_release", "build_release_images"));
    for (var name :
        List.of(
            "Publish immutable multi-architecture image",
            "Publish latest multi-architecture image")) {
      var step = ReleasePublisherFixture.step(name);
      assertThat(step).asInstanceOf(MAP).doesNotContainKeys("if", "continue-on-error");
      assertThat(step.get("env"))
          .asInstanceOf(MAP)
          .containsEntry("IMAGE_VERSION", "${{ needs.validate_release.outputs.version }}");
    }
    assertThat(ReleasePublisherFixture.step("Publish latest multi-architecture image").get("env"))
        .asInstanceOf(MAP)
        .containsEntry("GH_TOKEN", "${{ github.token }}");
    assertThat(ReleasePublisherFixture.step("Publish the verified native image").get("env"))
        .asInstanceOf(MAP)
        .containsEntry("IMAGE_ARCHITECTURE", "${{ matrix.architecture }}");
    for (var name : List.of("validate_release", "build_release_images", "publish_release")) {
      var job = ReleasePublisherFixture.job(name);
      assertThat(job).asInstanceOf(MAP).doesNotContainKeys("if", "continue-on-error");
      assertThat(job.get("permissions")).isEqualTo(Map.of("contents", "read"));
    }
  }

  @Test
  @DisplayName("Should require the CI image tests when publishing native release images")
  void shouldRequireCiImageTestsWhenPublishingNativeReleaseImages() throws Exception {
    var steps = ReleasePublisherFixture.steps("build_release_images");
    Map<?, ?> ci = new Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml")));
    var ciImage = (Map<?, ?>) ((Map<?, ?>) ci.get("jobs")).get("image");
    var ciTest =
        ((List<?>) ciImage.get("steps"))
            .stream()
                .map(step -> (Map<?, ?>) step)
                .filter(
                    step ->
                        "Exercise the packaged worker through its public interfaces"
                            .equals(step.get("name")))
                .findFirst()
                .orElseThrow();
    assertThat(
            ReleasePublisherFixture.step(
                    "Exercise the packaged worker through its public interfaces")
                .get("run"))
        .isEqualTo(ciTest.get("run"));
    for (var job : List.of("build_release_images", "publish_release")) {
      var login =
          ReleasePublisherFixture.steps(job).stream()
              .filter(step -> "Login to Docker Hub".equals(step.get("name")))
              .findFirst()
              .orElseThrow();
      assertThat(login.get("with"))
          .asInstanceOf(MAP)
          .containsEntry("username", "${{ secrets.DOCKERHUB_USERNAME }}")
          .containsEntry("password", "${{ secrets.DOCKERHUB_TOKEN }}");
    }
    assertThat(
            steps.indexOf(
                ReleasePublisherFixture.step(
                    "Exercise the packaged worker through its public interfaces")))
        .isLessThan(steps.indexOf(ReleasePublisherFixture.step("Login to Docker Hub")));
  }

  @ParameterizedTest
  @CsvSource({"v1.2.3, true", "v1.2.4, false"})
  @DisplayName("Should update latest only when GitHub identifies the published version as latest")
  void shouldUpdateLatestOnlyWhenGitHubIdentifiesPublishedVersionAsLatest(
      String latest, boolean update, @TempDir Path directory) throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.publishedNativeImages();
    fixture.releaseState(
        """
        {"latest":"%s"}
        """
            .formatted(latest));

    var result = fixture.runStep("Publish latest multi-architecture image");

    assertThat(result.exitCode()).as(result.output()).isZero();
    var expected =
        update
            ? List.of(
                Map.of("version", "1.2.3", "architecture", "amd64"),
                Map.of("version", "1.2.3", "architecture", "arm64"))
            : List.of(Map.of("version", "1.2.2"));
    assertThat(fixture.registry().get("indexes"))
        .asInstanceOf(MAP)
        .containsEntry("index.docker.io/streamarr/streamarr-transcode-worker:latest", expected);
  }

  @Test
  @DisplayName("Should preserve Docker failure when inspection emits both expected platforms")
  void shouldPreserveDockerFailureWhenInspectionEmitsBothExpectedPlatforms(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedNativeImages();
    var command = fixture.stepCommand("Publish immutable multi-architecture image");
    command.environment().put("INSPECTION_STATUS", "23");

    var result = fixture.run(command);

    assertThat(result.exitCode()).as(result.output()).isEqualTo(23);
  }

  @ParameterizedTest
  @ValueSource(strings = {"amd64", "arm64"})
  @DisplayName("Should fail publication when the registry index lacks a supported platform")
  void shouldFailPublicationWhenRegistryIndexLacksSupportedPlatform(
      String architecture, @TempDir Path directory) throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedNativeImages();
    fixture.registryInspection(
        """
        {"manifests":[{"platform":{"os":"linux","architecture":"%s"}}]}
        """
            .formatted(architecture));

    var result = fixture.runStep("Publish immutable multi-architecture image");

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should publish the versioned index when both native release images are available")
  void shouldPublishVersionedIndexWhenBothNativeReleaseImagesAreAvailable(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedNativeImages();

    var result = fixture.runStep("Publish immutable multi-architecture image");

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(fixture.registry().get("indexes"))
        .asInstanceOf(MAP)
        .containsEntry(
            "index.docker.io/streamarr/streamarr-transcode-worker:1.2.3",
            List.of(
                Map.of("version", "1.2.3", "architecture", "amd64"),
                Map.of("version", "1.2.3", "architecture", "arm64")));
  }

  @ParameterizedTest
  @CsvSource({"amd64, 0", "amd64, 31", "arm64, 0", "arm64, 31"})
  @DisplayName("Should publish the rebuilt image only when its packaged worker tests succeed")
  void shouldPublishRebuiltImageOnlyWhenPackagedWorkerTestsSucceed(
      String architecture, int status, @TempDir Path directory) throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.registryState(
        """
        {"authenticated":false,"local":{
          "streamarr-worker:release-amd64":{"source":"release-revision","version":"1.2.3","architecture":"amd64"},
          "streamarr-worker:release-arm64":{"source":"release-revision","version":"1.2.3","architecture":"arm64"}
        },"registry":{},"indexes":{}}
        """);

    var result =
        fixture.publishNativeImage(
            ReleasePublisherFixture.ImageTestScenario.builder()
                .architecture(architecture)
                .status(status)
                .build());

    assertThat(result.exitCode()).as(result.output()).isEqualTo(status);
    assertThat(fixture.registry()).asInstanceOf(MAP).containsEntry("authenticated", status == 0);
    var published = (Map<?, ?>) fixture.registry().get("registry");
    if (status != 0) {
      assertThat(published).isEmpty();
      return;
    }

    assertThat(published)
        .isEqualTo(
            Map.of(
                "index.docker.io/streamarr/streamarr-transcode-worker:1.2.3-" + architecture,
                Map.of(
                    "source",
                    "release-revision",
                    "version",
                    "1.2.3",
                    "architecture",
                    architecture)));
  }

  @Test
  @DisplayName("Should rebuild the validated release when preparing native architecture images")
  void shouldRebuildValidatedReleaseWhenPreparingNativeArchitectureImages() throws Exception {
    var job = ReleasePublisherFixture.job("build_release_images");

    assertThat(job).isNotNull();
    assertThat(job).asInstanceOf(MAP).containsEntry("needs", "validate_release");
    assertThat(job.get("env"))
        .asInstanceOf(MAP)
        .containsEntry("IMAGE_VERSION", "${{ needs.validate_release.outputs.version }}")
        .containsEntry("WORKER_IMAGE", "streamarr-worker:release-${{ matrix.architecture }}");
    var strategy = (Map<?, ?>) job.get("strategy");
    var matrix = (Map<?, ?>) strategy.get("matrix");
    assertThat(matrix.get("include"))
        .isEqualTo(
            List.of(
                Map.of("architecture", "amd64", "runner", "ubuntu-24.04"),
                Map.of("architecture", "arm64", "runner", "ubuntu-24.04-arm")));
    var steps = ReleasePublisherFixture.steps("build_release_images");
    assertThat(steps.getFirst().get("with"))
        .asInstanceOf(MAP)
        .containsEntry("ref", "${{ needs.validate_release.outputs.revision }}")
        .containsEntry("persist-credentials", false);
    assertThat(steps.stream().map(step -> (String) step.get("uses")).toList())
        .contains(
            "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
            "./.github/actions/prepare-ffmpeg",
            "buildpacks/github-actions/setup-pack@e3b14c6e906f91da358e01dc2849ce068188107f");
    assertThat(
            ReleasePublisherFixture.step(
                "Build the unpublished image and verify its media runtime"))
        .asInstanceOf(MAP)
        .containsEntry(
            "run",
            ".github/actions/pack-build/build-worker-image.sh \"$WORKER_IMAGE\" \"$IMAGE_VERSION\"");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
      {"releases":{"v1.2.3":{"draft":true}}}
      """,
        """
      {"releases":{"v1.2.3":{"draft":null}}}
      """,
        """
      {"releases":{}}
      """,
        """
      {"unavailable":true}
      """
      })
  @DisplayName("Should withhold image builds when GitHub cannot confirm a published release")
  void shouldWithholdImageBuildsWhenGitHubCannotConfirmPublishedRelease(
      String state, @TempDir Path directory) throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.releaseState(state);

    var result = fixture.runStep("Verify tagged Maven version");

    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(fixture.outputs()).isEmpty();
  }

  @Test
  @DisplayName("Should reject version drift when Maven differs from the release tag")
  void shouldRejectVersionDriftWhenMavenDiffersFromReleaseTag(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.mavenVersion("1.2.4-SNAPSHOT");

    var result = fixture.runStep("Verify tagged Maven version");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag and Maven version must agree");
    assertThat(fixture.outputs()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"01.2.3", "1.2", "1.2.3-SNAPSHOT", "1.2.3-rc.1", "1.2.3+build"})
  @DisplayName("Should reject the release when its tag is not stable SemVer")
  void shouldRejectReleaseWhenTagIsNotStableSemver(String version, @TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.git("tag", "v" + version);
    fixture.mavenVersion(version);
    var command =
        fixture.command(
            (String) ReleasePublisherFixture.step("Verify tagged Maven version").get("run"));
    command.environment().put("RELEASE_TAG", "v" + version);

    var result = fixture.run(command);

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag must be stable SemVer");
    assertThat(fixture.outputs()).isEmpty();
  }

  @Test
  @DisplayName("Should reject an unmerged release when the tagged commit is outside main")
  void shouldRejectUnmergedReleaseWhenTaggedCommitIsOutsideMain(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.git("commit", "--quiet", "--allow-empty", "-m", "unmerged revision");
    fixture.git("tag", "--force", "v1.2.3");

    var result = fixture.runStep("Verify tagged Maven version");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release revision is not an ancestor of main");
    assertThat(fixture.outputs()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a different checkout when the release tag names another revision")
  void shouldRejectDifferentCheckoutWhenReleaseTagNamesAnotherRevision(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();
    fixture.git("commit", "--quiet", "--allow-empty", "-m", "later revision");

    var result = fixture.runStep("Verify tagged Maven version");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Release tag does not match the checked-out revision");
    assertThat(fixture.outputs()).isEmpty();
  }

  @Test
  @DisplayName("Should export the version and revision when the published release matches Maven")
  void shouldExportVersionAndRevisionWhenPublishedReleaseMatchesMaven(@TempDir Path directory)
      throws Exception {
    var fixture = new ReleasePublisherFixture(directory);
    fixture.publishedRelease();

    var result = fixture.runStep("Verify tagged Maven version");

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(fixture.outputs())
        .isEqualTo("version=1.2.3\nrevision=" + fixture.git("rev-parse", "HEAD") + "\n");
  }

  @Test
  @DisplayName("Should accept published releases and tag retries when selecting publication events")
  void shouldAcceptPublishedReleasesAndTagRetriesWhenSelectingPublicationEvents() throws Exception {
    Map<?, ?> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/publish-release.yml")));
    var triggers = (Map<?, ?>) workflow.get(true);

    assertThat(triggers.keySet()).isEqualTo(Set.of("release", "workflow_dispatch"));
    assertThat(triggers.get("release"))
        .asInstanceOf(MAP)
        .containsEntry("types", List.of("released"));
    var dispatch = (Map<?, ?>) triggers.get("workflow_dispatch");
    var inputs = (Map<?, ?>) dispatch.get("inputs");
    assertThat(inputs.get("tag"))
        .asInstanceOf(MAP)
        .containsEntry("required", true)
        .containsEntry("type", "string");
  }
}
