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
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Publisher Tests")
class ReleasePublisherTest {

  @Test
  @DisplayName("Should delegate validated receipts when native release images pass")
  void shouldDelegateValidatedReceiptsWhenNativeReleaseImagesPass() throws Exception {
    var validation = ReleasePublisherFixture.job("validate_release");
    var publish = ReleasePublisherFixture.job("publish_release");

    assertThat(validation.get("uses"))
        .asString()
        .matches(
            "streamarr/streamarr-workflows/.github/workflows/validate-release.yml@[a-f0-9]{40}");
    assertThat(validation.get("with"))
        .asInstanceOf(MAP)
        .containsEntry("tag", "${{ inputs.tag || '' }}");
    assertThat(publish.get("uses"))
        .asString()
        .matches("streamarr/streamarr-workflows/.github/workflows/publish-image.yml@[a-f0-9]{40}");
    assertThat(publish)
        .asInstanceOf(MAP)
        .containsEntry("needs", List.of("validate_release", "build_release_images"));
    assertThat(publish.get("with"))
        .asInstanceOf(MAP)
        .containsEntry("image-repository", "streamarr/streamarr-transcode-worker")
        .containsEntry("source-revision", "${{ needs.validate_release.outputs.revision }}")
        .containsEntry("version", "${{ needs.validate_release.outputs.version }}")
        .containsEntry(
            "artifact-pattern", "worker-release-*-${{ needs.validate_release.outputs.revision }}")
        .containsEntry("publication-kind", "release");
    assertThat(publish.get("secrets"))
        .asInstanceOf(MAP)
        .containsEntry("dockerhub-username", "${{ secrets.DOCKERHUB_USERNAME }}")
        .containsEntry("dockerhub-token", "${{ secrets.DOCKERHUB_TOKEN }}");
  }

  @Test
  @DisplayName("Should forward validated release outputs when coordinating publication jobs")
  void shouldForwardValidatedReleaseOutputsWhenCoordinatingPublicationJobs() throws Exception {
    var workflow = ReleasePublisherFixture.workflow();
    assertThat(workflow).asInstanceOf(MAP).containsEntry("permissions", Map.of());
    assertThat(workflow.get("concurrency"))
        .asInstanceOf(MAP)
        .containsEntry("group", "publish-release")
        .containsEntry("cancel-in-progress", false);
    assertThat(ReleasePublisherFixture.job("publish_release"))
        .asInstanceOf(MAP)
        .containsEntry("needs", List.of("validate_release", "build_release_images"));
    assertThat(ReleasePublisherFixture.step("Publish the verified native image").get("env"))
        .asInstanceOf(MAP)
        .containsEntry("IMAGE_ARCHITECTURE", "${{ matrix.architecture }}")
        .containsEntry("SOURCE_REVISION", "${{ needs.validate_release.outputs.revision }}");
    assertThat(ReleasePublisherFixture.step("Preserve the native image digest").get("with"))
        .asInstanceOf(MAP)
        .containsEntry(
            "name",
            "worker-release-${{ matrix.architecture }}-${{ needs.validate_release.outputs.revision }}")
        .containsEntry("path", "${{ matrix.architecture }}-image.json")
        .containsEntry("if-no-files-found", "error");
    for (var name : List.of("validate_release", "build_release_images", "publish_release")) {
      var job = ReleasePublisherFixture.job(name);
      assertThat(job)
          .asInstanceOf(MAP)
          .doesNotContainKeys("if", "continue-on-error")
          .containsEntry("permissions", Map.of("contents", "read"));
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
                "Exercise the packaged worker through its public interfaces"))
        .asInstanceOf(MAP)
        .containsEntry("run", ciTest.get("run"));
    assertThat(ReleasePublisherFixture.step("Login to Docker Hub").get("with"))
        .asInstanceOf(MAP)
        .containsEntry("username", "${{ secrets.DOCKERHUB_USERNAME }}")
        .containsEntry("password", "${{ secrets.DOCKERHUB_TOKEN }}");
    assertThat(
            steps.indexOf(
                ReleasePublisherFixture.step(
                    "Exercise the packaged worker through its public interfaces")))
        .isLessThan(steps.indexOf(ReleasePublisherFixture.step("Login to Docker Hub")));
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
          "streamarr-worker:release-amd64":{"source":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":"1.2.3","architecture":"amd64"},
          "streamarr-worker:release-arm64":{"source":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":"1.2.3","architecture":"arm64"}
        },"registry":{},"indexes":{}}
        """);

    var result =
        fixture.publishNativeImage(
            ReleasePublisherFixture.ImageTestScenario.builder()
                .architecture(architecture)
                .sourceRevision("a".repeat(40))
                .status(status)
                .build());

    assertThat(result.exitCode()).as(result.output()).isEqualTo(status);
    assertThat(fixture.registry()).asInstanceOf(MAP).containsEntry("authenticated", status == 0);
    var published = (Map<?, ?>) fixture.registry().get("registry");
    if (status != 0) {
      assertThat(published).isEmpty();
      assertThat(fixture.nativeReceipt(architecture)).isEmpty();
      return;
    }

    assertThat(published)
        .isEqualTo(
            Map.of(
                "index.docker.io/streamarr/streamarr-transcode-worker:sha-"
                    + "a".repeat(40)
                    + "-"
                    + architecture,
                Map.of(
                    "source", "a".repeat(40), "version", "1.2.3", "architecture", architecture)));
    assertThat(fixture.nativeReceipt(architecture))
        .isEqualTo(
            Map.of(
                "sourceRevision",
                "a".repeat(40),
                "architecture",
                architecture,
                "image",
                "streamarr/streamarr-transcode-worker@sha256:" + "d".repeat(64)));
  }

  @Test
  @DisplayName("Should rebuild the validated release when preparing native architecture images")
  void shouldRebuildValidatedReleaseWhenPreparingNativeArchitectureImages() throws Exception {
    var job = ReleasePublisherFixture.job("build_release_images");

    assertThat(job).asInstanceOf(MAP).containsEntry("needs", "validate_release");
    assertThat(job.get("env"))
        .asInstanceOf(MAP)
        .containsEntry("IMAGE_VERSION", "${{ needs.validate_release.outputs.version }}")
        .containsEntry("WORKER_IMAGE", "streamarr-worker:release-${{ matrix.architecture }}");
    var strategy = (Map<?, ?>) job.get("strategy");
    var matrix = (Map<?, ?>) strategy.get("matrix");
    assertThat(matrix)
        .asInstanceOf(MAP)
        .containsEntry(
            "include",
            List.of(
                Map.of("architecture", "amd64", "runner", "ubuntu-24.04"),
                Map.of("architecture", "arm64", "runner", "ubuntu-24.04-arm")));
    var steps = ReleasePublisherFixture.steps("build_release_images");
    assertThat(steps.getFirst().get("with"))
        .asInstanceOf(MAP)
        .containsEntry("ref", "${{ needs.validate_release.outputs.revision }}")
        .containsEntry("persist-credentials", false);
    assertThat(
            ReleasePublisherFixture.step(
                "Build the unpublished image and verify its media runtime"))
        .asInstanceOf(MAP)
        .containsEntry(
            "run",
            ".github/actions/pack-build/build-worker-image.sh \"$WORKER_IMAGE\" \"$IMAGE_VERSION\"");
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
