package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("FFmpeg Release Review Tests")
class FfmpegReleaseReviewTest {

  private static final Path BUILDPACK = Path.of("buildpacks/ffmpeg");
  private static final Path REVIEWER = BUILDPACK.resolve("bin/review-release").toAbsolutePath();
  private static final Path LOCK_UPDATER = BUILDPACK.resolve("bin/update-lock").toAbsolutePath();
  private static final int HUMAN_REVIEW_REQUIRED = 3;
  private static final String LOCKED_RELEASE = "v99.0.0-1";
  private static final String LOCKED_REVISION = "c".repeat(40);
  private static final String LOCKED_AMD64_SHA256 = "d".repeat(64);
  private static final String LOCKED_ARM64_SHA256 = "e".repeat(64);

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should bind reviewed inputs to the locked release when the inventory is unchanged")
  void shouldBindReviewedInputsToTheLockedReleaseWhenTheInventoryIsUnchanged() throws Exception {
    var review = review();
    var reviewedRevision = reviewed("source_revision");

    var result =
        review
            .upstreamChanges(
                "debian/changelog",
                "debian/patches/0100-backport-trim-bitstream-filter.patch",
                "debian/patches/series",
                "build.yaml",
                "builder/images/macos/00-dep.sh",
                ".github/workflows/_meta_mac_portable.yaml")
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(review.manifest()))
        .isEqualTo(
            """
            release=%s
            source_revision=%s
            amd64_sha256=%s
            arm64_sha256=%s
            """
                .formatted(
                    LOCKED_RELEASE, LOCKED_REVISION, LOCKED_AMD64_SHA256, LOCKED_ARM64_SHA256));
    assertThat(Files.readString(review.inventory()))
        .contains(
            "https://raw.githubusercontent.com/jellyfin/jellyfin-ffmpeg/%s/LICENSE.md"
                .formatted(LOCKED_REVISION))
        .doesNotContain(reviewedRevision);
    assertThat(Files.readString(review.sourceAccess()))
        .contains("releases/tag/" + LOCKED_RELEASE, LOCKED_REVISION)
        .doesNotContain(reviewedRevision, "releases/tag/" + reviewed("release"));
    var offlineValidation =
        ScriptCommand.of(LOCK_UPDATER)
            .argument("--check")
            .argument("--root")
            .argument(review.repository().toString())
            .execute();
    assertThat(offlineValidation.exitCode()).as(offlineValidation.output()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "builder/scripts.d/50-x264.sh",
        "builder/images/base-linux64/Dockerfile",
        "builder/variants/linux64-gpl.sh",
        "LICENSE.md",
        "COPYING.GPLv3",
        "debian/copyright",
        "configure",
        "libavcodec/aacdec.c",
        "debian/patches/../../LICENSE.md",
        "debian/patches/odd name.patch"
      })
  @DisplayName("Should require human review when upstream changes a path inside the inventory")
  void shouldRequireHumanReviewWhenUpstreamChangesAPathInsideTheInventory(String path)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result = review.upstreamChanges("debian/changelog", path).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(new ObjectMapper().writeValueAsString(path));
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should require human review when an inventoried path is renamed away")
  void shouldRequireHumanReviewWhenAnInventoriedPathIsRenamedAway() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamComparison(
                """
                {"status": "ahead", "files": [
                  {"filename": "debian/patches/moved.patch",
                   "previous_filename": "builder/scripts.d/50-x264.sh"}
                ]}
                """)
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains("builder/scripts.d/50-x264.sh");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(strings = {"diverged", "behind", "identical"})
  @DisplayName("Should require human review when the locked revision does not follow the reviewed")
  void shouldRequireHumanReviewWhenTheLockedRevisionDoesNotFollowTheReviewed(String status)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamComparison(
                """
                {"status": "%s", "files": [{"filename": "debian/changelog"}]}
                """
                    .formatted(status))
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(status);
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should require human review when the upstream change list is truncated")
  void shouldRequireHumanReviewWhenTheUpstreamChangeListIsTruncated() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();
    var paths = IntStream.range(0, 300).mapToObj("debian/patches/%04d.patch"::formatted).toList();

    var result = review.upstreamChanges(paths.toArray(String[]::new)).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains("truncated");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should require human review when release assets change without a source change")
  void shouldRequireHumanReviewWhenReleaseAssetsChangeWithoutASourceChange() throws Exception {
    var review = review();
    Files.writeString(
        review.lock(),
        Files.readString(review.lock()).replace(LOCKED_REVISION, reviewed("source_revision")));
    var reviewedInputs = review.reviewedInputs();

    var result = review.execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains("without a source revision change");
    assertThat(review.upstreamRequests()).isEmpty();
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should leave reviewed inputs untouched when they are already bound to the lock")
  void shouldLeaveReviewedInputsUntouchedWhenTheyAreAlreadyBoundToTheLock() throws Exception {
    var review = review();
    Files.copy(
        BUILDPACK.resolve("ffmpeg.lock"), review.lock(), StandardCopyOption.REPLACE_EXISTING);
    var reviewedInputs = review.reviewedInputs();

    var result = review.execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(review.upstreamRequests()).isEmpty();
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should fail without approving anything when the upstream comparison is unavailable")
  void shouldFailWithoutApprovingAnythingWhenTheUpstreamComparisonIsUnavailable() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result = review.upstreamFailure(HUMAN_REVIEW_REQUIRED).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should fail without approving anything when the comparison has no change list")
  void shouldFailWithoutApprovingAnythingWhenTheComparisonHasNoChangeList() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result = review.upstreamComparison("{\"status\": \"ahead\"}\n").execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  private ReviewFixture review() throws IOException {
    var repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
    var buildpack = Files.createDirectories(repository.resolve(BUILDPACK));
    copyReviewedInputs(buildpack);
    var version = LOCKED_RELEASE.substring(1);
    Files.writeString(buildpack.resolve("release"), LOCKED_RELEASE + "\n");
    Files.writeString(
        buildpack.resolve("ffmpeg.lock"),
        """
        release=%s
        version=%s
        source_revision=%s
        asset_variant=gpl
        amd64_asset=jellyfin-ffmpeg_%s_portable_linux64-gpl.tar.xz
        amd64_sha256=%s
        arm64_asset=jellyfin-ffmpeg_%s_portable_linuxarm64-gpl.tar.xz
        arm64_sha256=%s
        """
            .formatted(
                LOCKED_RELEASE,
                version,
                LOCKED_REVISION,
                version,
                LOCKED_AMD64_SHA256,
                version,
                LOCKED_ARM64_SHA256));
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(
        commands,
        "curl",
        """
        url=
        output=
        while (( $# > 0 )); do
          case "$1" in
            --output)
              output="$2"
              shift 2
              ;;
            https://*)
              url="$1"
              shift
              ;;
            *)
              shift
              ;;
          esac
        done
        printf '%s\\n' "${url}" >>"${FAKE_UPSTREAM_REQUESTS}"
        if [[ -n "${FAKE_UPSTREAM_EXIT:-}" ]]; then
          exit "${FAKE_UPSTREAM_EXIT}"
        fi
        if [[ "${url}" != "${FAKE_COMPARISON_URL}" ]]; then
          echo "Unexpected URL: ${url}" >&2
          exit 1
        fi
        cp "${FAKE_COMPARISON}" "${output}"
        """);
    return new ReviewFixture(repository, commands, temporaryDirectory);
  }

  private static String reviewed(String key) throws IOException {
    var prefix = key + "=";
    return Files.readAllLines(BUILDPACK.resolve("notices/manifest")).stream()
        .filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length()))
        .collect(Collectors.joining());
  }

  private static void copyReviewedInputs(Path buildpack) throws IOException {
    try (var paths = Files.walk(BUILDPACK)) {
      var inputs =
          paths
              .filter(Files::isRegularFile)
              .filter(source -> !source.startsWith(BUILDPACK.resolve("generated")))
              .toList();
      for (var source : inputs) {
        var destination = buildpack.resolve(BUILDPACK.relativize(source));
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
      }
    }
  }

  private static final class ReviewFixture {

    private final Path repository;
    private final Path upstreamRequests;
    private final Path comparison;
    private final ScriptCommand command;

    private ReviewFixture(Path repository, Path commands, Path temporaryDirectory)
        throws IOException {
      this.repository = repository;
      upstreamRequests = Files.createFile(temporaryDirectory.resolve("upstream-requests"));
      comparison = temporaryDirectory.resolve("comparison.json");
      command =
          ScriptCommand.of(REVIEWER)
              .argument("--root")
              .argument(repository.toString())
              .prependPath(commands)
              .environment("FAKE_UPSTREAM_REQUESTS", upstreamRequests.toString())
              .environment("FAKE_COMPARISON", comparison.toString())
              .environment(
                  "FAKE_COMPARISON_URL",
                  "https://api.github.com/repos/jellyfin/jellyfin-ffmpeg/compare/%s...%s"
                      .formatted(reviewed("source_revision"), LOCKED_REVISION));
    }

    private ReviewFixture upstreamChanges(String... paths) throws IOException {
      var mapper = new ObjectMapper();
      var files = mapper.createArrayNode();
      List.of(paths).forEach(path -> files.addObject().put("filename", path));
      var body = mapper.createObjectNode().put("status", "ahead");
      body.set("files", files);
      return upstreamComparison(mapper.writeValueAsString(body));
    }

    private ReviewFixture upstreamComparison(String json) throws IOException {
      Files.writeString(comparison, json);
      return this;
    }

    private ReviewFixture upstreamFailure(int curlExitCode) {
      command.environment("FAKE_UPSTREAM_EXIT", Integer.toString(curlExitCode));
      return this;
    }

    private ScriptCommand.Result execute() throws IOException, InterruptedException {
      return command.execute();
    }

    private List<String> reviewedInputs() throws IOException {
      return List.of(
          Files.readString(manifest()),
          Files.readString(inventory()),
          Files.readString(sourceAccess()));
    }

    private List<String> upstreamRequests() throws IOException {
      return Files.readAllLines(upstreamRequests);
    }

    private Path repository() {
      return repository;
    }

    private Path lock() {
      return repository.resolve(BUILDPACK).resolve("ffmpeg.lock");
    }

    private Path manifest() {
      return repository.resolve(BUILDPACK).resolve("notices/manifest");
    }

    private Path inventory() {
      return repository.resolve(BUILDPACK).resolve("notices/sources.json");
    }

    private Path sourceAccess() {
      return repository.resolve(BUILDPACK).resolve("SOURCE.txt");
    }
  }
}
