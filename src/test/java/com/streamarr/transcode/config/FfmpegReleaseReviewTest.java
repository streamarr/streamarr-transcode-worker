package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

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
  private static final String PATCH = "debian/patches/0099-fix-qsv-av1-hdr-side-data.patch";
  private static final String CONTENTS_URL =
      "https://api.github.com/repos/jellyfin/jellyfin-ffmpeg/contents";

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
                "debian/patches/series",
                "build.yaml",
                "builder/images/macos/00-dep.sh",
                ".github/workflows/_meta_mac_portable.yaml")
            .upstreamPatch(
                PatchChange.builder()
                    .status("added")
                    .path(PATCH)
                    .locked(modifying("libavcodec/qsvdec.c", "export HDR side data"))
                    .build())
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
        "debian/patches/odd name.patch",
        "debian/patches/README",
        "debian/patches/0101-vendor-codec.diff",
        "debian/patches/nested/0101-vendor-codec.patch"
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
  @ValueSource(
      strings = {
        "configure",
        "LICENSE.md",
        "COPYING.GPLv3",
        "libavcodec/thirdparty/COPYING",
        "libavcodec/thirdparty/license.txt"
      })
  @DisplayName("Should require human review when an added patch changes a licensing file")
  void shouldRequireHumanReviewWhenAnAddedPatchChangesALicensingFile(String target)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamChanges("debian/changelog", "debian/patches/series")
            .upstreamPatch(
                PatchChange.builder()
                    .status("added")
                    .path(PATCH)
                    .locked(
                        modifying("libavcodec/qsvdec.c", "export HDR side data")
                            + modifying(target, "All advertising must display an acknowledgement"))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " changes " + target);
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        Index: FFmpeg/libavcodec/bsf/trim.c
        ===================================================================
        --- /dev/null
        +++ FFmpeg/libavcodec/bsf/trim.c
        @@ -0,0 +1,2 @@
        +/* Copyright (c) 2026 Some Third Party */
        +int trim;
        """,
        """
        diff --git a/libavcodec/bsf/trim.c b/libavcodec/bsf/trim.c
        new file mode 100644
        index 0000000000..e69de29bb2
        """,
        """
        Index: FFmpeg/libavcodec/bsf/trim.c
        ===================================================================
        --- FFmpeg.orig/libavcodec/bsf/trim.c
        +++ FFmpeg/libavcodec/bsf/trim.c
        @@ -0,0 +1,2 @@
        +/* Copyright (c) 2026 Some Third Party */
        +int trim;
        """,
        """
        --- FFmpeg.orig/libavcodec/bsf/trim.c\t1970-01-01 00:00:00.000000000 +0000
        +++ FFmpeg/libavcodec/bsf/trim.c\t2026-01-01 00:00:00.000000000 +0000
        @@ -0,0 +1,2 @@
        +/* Copyright (c) 2026 Some Third Party */
        +int trim;
        """,
        """
        diff --git a/libavcodec/bsf/trim.c b/libavcodec/bsf/trim.c
        index 0000000000..0fd9f0a1c5 100644
        --- a/libavcodec/bsf/trim.c
        +++ b/libavcodec/bsf/trim.c
        @@ -1,0 +1,2 @@
        +/* Copyright (c) 2026 Some Third Party */
        +int trim;
        """,
        """
        diff --git a/libavcodec/bsf/trim.c b/libavcodec/bsf/trim.c
        index e69de29bb2..0fd9f0a1c5 100644
        --- a/libavcodec/bsf/trim.c
        +++ b/libavcodec/bsf/trim.c
        @@ -1,0 +1,2 @@
        +/* Copyright (c) 2026 Some Third Party */
        +int trim;
        """
      })
  @DisplayName("Should require human review when an added patch creates a file")
  void shouldRequireHumanReviewWhenAnAddedPatchCreatesAFile(String creation) throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("added")
                    .path(PATCH)
                    .locked(modifying("libavcodec/bsf/Makefile", "bsf/trim.o") + creation)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " creates libavcodec/bsf/trim.c");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(strings = {"modified", "renamed"})
  @DisplayName("Should carry the review forward when a changed patch keeps its licensing edits")
  void shouldCarryTheReviewForwardWhenAChangedPatchKeepsItsLicensingEdits(String status)
      throws Exception {
    var review = review();
    var previousPath = "debian/patches/0098-fix-qsv-av1-hdr-side-data.patch";
    var unchanged = modifying("configure", "require_pkg_config rkmpp") + creating("libavutil/rk.c");

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status(status)
                    .path(PATCH)
                    .previousPath(status.equals("renamed") ? previousPath : null)
                    .reviewed(unchanged + modifying("libavcodec/qsvdec.c", "export HDR side data"))
                    .locked(
                        modifying("libavcodec/qsvdec.c", "export HDR10+ side data")
                            + unchanged.replace("@@ -1,2 +1,3 @@", "@@ -11,2 +11,3 @@"))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(review.upstreamRequests())
        .contains(
            "%s/%s?ref=%s"
                .formatted(
                    CONTENTS_URL,
                    status.equals("renamed") ? previousPath : PATCH,
                    reviewed("source_revision")),
            "%s/%s?ref=%s".formatted(CONTENTS_URL, PATCH, LOCKED_REVISION));
    assertThat(Files.readString(review.manifest())).contains("release=" + LOCKED_RELEASE);
  }

  @ParameterizedTest
  @MethodSource("changedLicensingEdits")
  @DisplayName("Should require human review when a changed patch alters its licensing edits")
  void shouldRequireHumanReviewWhenAChangedPatchAltersItsLicensingEdits(
      String reviewed, String locked) throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("modified")
                    .path(PATCH)
                    .reviewed(reviewed)
                    .locked(locked)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " changes its edits to configure");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  private static Stream<Arguments> changedLicensingEdits() {
    var source = modifying("libavcodec/qsvdec.c", "export HDR side data");
    var gated = modifying("configure", "EXTERNAL_LIBRARY_NONFREE_LIST=libfdk_aac");
    var ungated = modifying("configure", "EXTERNAL_LIBRARY_LIST=libfdk_aac");
    return Stream.of(
        Arguments.of(source + gated, source + ungated),
        Arguments.of(source, source + ungated),
        Arguments.of(source + gated, source));
  }

  @ParameterizedTest
  @ValueSource(strings = {"modified", "renamed"})
  @DisplayName("Should require human review when a changed patch creates another file")
  void shouldRequireHumanReviewWhenAChangedPatchCreatesAnotherFile(String status) throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();
    var reviewed = creating("libavutil/rk.c");

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status(status)
                    .path(PATCH)
                    .previousPath(status.equals("renamed") ? "debian/patches/0098-rk.patch" : null)
                    .reviewed(reviewed)
                    .locked(reviewed + creating("libavutil/thirdparty/rga.c"))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output())
        .contains(PATCH + " creates libavutil/thirdparty/rga.c")
        .doesNotContain("creates libavutil/rk.c");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should require human review when a removed patch changed a licensing file")
  void shouldRequireHumanReviewWhenARemovedPatchChangedALicensingFile() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("removed")
                    .path(PATCH)
                    .reviewed(modifying("configure", "EXTERNAL_LIBRARY_LIST=libfdk_aac"))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains("removed " + PATCH + " changed configure");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should carry the review forward when a removed patch changed only FFmpeg sources")
  void shouldCarryTheReviewForwardWhenARemovedPatchChangedOnlyFfmpegSources() throws Exception {
    var review = review();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("removed")
                    .path(PATCH)
                    .reviewed(
                        modifying("libavcodec/qsvdec.c", "export HDR side data")
                            + creating("libavcodec/qsv_hdr.c"))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(review.manifest())).contains("release=" + LOCKED_RELEASE);
  }

  @ParameterizedTest
  @ValueSource(ints = {HUMAN_REVIEW_REQUIRED, 22})
  @DisplayName("Should require human review when a changed patch cannot be fetched")
  void shouldRequireHumanReviewWhenAChangedPatchCannotBeFetched(int curlExitCode) throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamPatch(PatchChange.builder().status("added").path(PATCH).build())
            .patchFailure(curlExitCode)
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " could not be fetched");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        """
        {"type": "file", "encoding": "base64", "content": "SW5kZXg6IEZGbXBlZy9jb25maWd1cmUK"}
        """,
        """
        --- FFmpeg.orig/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        """,
        """
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1,3 +1,3 @@
        -old
        +new
        """,
        """
        Index: FFmpeg/LICENSE.md
        ===================================================================
        1c1
        < old
        ---
        > new
        """,
        """
        Index: FFmpeg/LICENSE.md
        ===================================================================
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        """,
        """
          --- FFmpeg.orig/LICENSE.md
          +++ FFmpeg/LICENSE.md
          @@ -1 +1 @@
          -old
          +new
        """,
        """
        *** FFmpeg.orig/LICENSE.md
        --- FFmpeg/LICENSE.md
        ***************
        *** 1 ****
        ! old
        --- 1 ----
        ! new
        """,
        """
        diff --git a/libavcodec/qsvdec.c b/LICENSE.md
        similarity index 100%
        rename from libavcodec/qsvdec.c
        rename to LICENSE.md
        """,
        """
        diff --git a/libavcodec/logo.png b/libavcodec/logo.png
        index e69de29bb2..0fd9f0a1c5 100644
        GIT binary patch
        literal 4
        LcmZQzU|;|M00aO5
        """,
        """
        --- "FFmpeg.orig/LICENSE.md"
        +++ "FFmpeg/LICENSE.md"
        @@ -1 +1 @@
        -old
        +new
        """,
        """
        --- LICENSE.md
        +++ LICENSE.md
        @@ -1 +1 @@
        -old
        +new
        """,
        """
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        X--- FFmpeg.orig/configure
        X+++ FFmpeg/configure
        X@@ -1 +1,2 @@
        X unchanged
        X+enable nonfree
        """,
        """
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        - --- FFmpeg.orig/configure
        - +++ FFmpeg/configure
        @@ -1 +1,2 @@
         unchanged
        +enable nonfree
        """,
        """
        Description: export HDR side data
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        """,
        """
        --- FFmpeg.orig/libavcodec/qsvdec.c
        +++ FFmpeg/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new

        """,
        """
        diff --git a/libavcodec/qsvdec.c b/libavcodec/qsvdec.c
        index e69de29bb2,0fd9f0a1c5..c301f4a0c9
        --- a/libavcodec/qsvdec.c
        +++ b/libavcodec/qsvdec.c
        @@ -1 +1 @@
        -old
        +new
        """
      })
  @DisplayName("Should require human review when a changed patch cannot be read unambiguously")
  void shouldRequireHumanReviewWhenAChangedPatchCannotBeReadUnambiguously(String body)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result =
        review
            .upstreamPatch(PatchChange.builder().status("added").path(PATCH).locked(body).build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " cannot be read unambiguously");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        8a9
        > enable nonfree
        """,
        """
        8a
        enable nonfree
        .
        """
      })
  @DisplayName("Should require human review when a changed patch gains an edit outside its hunks")
  void shouldRequireHumanReviewWhenAChangedPatchGainsAnEditOutsideItsHunks(String edit)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();
    var reviewed = modifying("configure", "require_pkg_config rkmpp");

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("modified")
                    .path(PATCH)
                    .reviewed(reviewed)
                    .locked(reviewed.replaceFirst("=+\n", edit))
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH + " cannot be read unambiguously");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should read an unmarked blank hunk line as context when a patch is added")
  void shouldReadAnUnmarkedBlankHunkLineAsContextWhenAPatchIsAdded() throws Exception {
    var review = review();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("added")
                    .path(PATCH)
                    .locked(
                        """
                        Index: FFmpeg/libavcodec/videotoolbox.c
                        ===================================================================
                        --- FFmpeg.orig/libavcodec/videotoolbox.c
                        +++ FFmpeg/libavcodec/videotoolbox.c
                        @@ -124,4 +124,4 @@ static int videotoolbox_postproc_frame(v
                        -        return AVERROR_EXTERNAL;
                        +        return 0;
                             }

                             frame->crop_right = 0;
                        \\ No newline at end of file
                        """)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
  }

  @Test
  @DisplayName("Should carry the review forward when an added git patch changes only sources")
  void shouldCarryTheReviewForwardWhenAnAddedGitPatchChangesOnlySources() throws Exception {
    var review = review();

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("added")
                    .path(PATCH)
                    .locked(
                        """
                        diff --git a/libavfilter/vf_scale_d3d11.c b/libavfilter/vf_scale_d3d11.c
                        index c301f4a0c9..aab98c8cec 100644
                        --- a/libavfilter/vf_scale_d3d11.c
                        +++ b/libavfilter/vf_scale_d3d11.c
                        @@ -1,2 +1,3 @@
                         unchanged
                        +scale the visible source rectangle
                         unchanged
                        diff --git a/tests/fate-run.sh b/tests/fate-run.sh
                        old mode 100644
                        new mode 100755
                        """)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(review.manifest())).contains("release=" + LOCKED_RELEASE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"copied", "changed", "unchanged"})
  @DisplayName("Should require human review when a patch has an unsupported change status")
  void shouldRequireHumanReviewWhenAPatchHasAnUnsupportedChangeStatus(String status)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();
    var body = modifying("libavcodec/qsvdec.c", "export HDR side data");

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status(status)
                    .path(PATCH)
                    .reviewed(body)
                    .locked(body)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH, status);
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @Test
  @DisplayName("Should require human review when a patch is renamed from another kind of file")
  void shouldRequireHumanReviewWhenAPatchIsRenamedFromAnotherKindOfFile() throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();
    var body = modifying("libavcodec/qsvdec.c", "export HDR side data");

    var result =
        review
            .upstreamPatch(
                PatchChange.builder()
                    .status("renamed")
                    .path(PATCH)
                    .previousPath("debian/changelog")
                    .reviewed(body)
                    .locked(body)
                    .build())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(HUMAN_REVIEW_REQUIRED);
    assertThat(result.output()).contains(PATCH, "debian/changelog");
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
  @DisplayName("Should name the locked release exactly when an interrupted bind is repeated")
  void shouldNameTheLockedReleaseExactlyWhenAnInterruptedBindIsRepeated() throws Exception {
    var lockedRelease = reviewed("release") + "0";
    var review = review(lockedRelease).upstreamChanges("debian/changelog");
    var reviewedManifest = Files.readString(review.manifest());
    var firstBind = review.execute();
    assertThat(firstBind.exitCode()).as(firstBind.output()).isZero();
    // The manifest is moved last, so an interrupted bind leaves it naming the reviewed release.
    Files.writeString(review.manifest(), reviewedManifest);

    var result = review.execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readAllLines(review.sourceAccess()))
        .filteredOn(line -> line.startsWith("Binary release:"))
        .containsExactly(
            "Binary release: https://github.com/jellyfin/jellyfin-ffmpeg/releases/tag/"
                + lockedRelease);
    assertThat(Files.readString(review.manifest())).contains("release=" + lockedRelease + "\n");
  }

  @Test
  @DisplayName("Should fail without approving anything when the source offer names a longer tag")
  void shouldFailWithoutApprovingAnythingWhenTheSourceOfferNamesALongerTag() throws Exception {
    var review = review(reviewed("release") + "0").upstreamChanges("debian/changelog");
    Files.writeString(
        review.sourceAccess(),
        Files.readString(review.sourceAccess())
            .replace(
                "releases/tag/" + reviewed("release"),
                "releases/tag/" + reviewed("release") + "00"));
    var reviewedInputs = review.reviewedInputs();

    var result = review.execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("bind them by hand");
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        {"status": "ahead", "files": [
          {"filename": "debian/changelog"}, "unexpected-entry", {"filename": "LICENSE.md"}]}
        """,
        """
        {"status": "ahead", "files": [
          {"filename": "debian/changelog"}, 7, {"filename": "builder/scripts.d/50-x264.sh"}]}
        """,
        """
        {"status": "ahead", "files": [
          {"filename": "debian/changelog"}, ["nested"], {"filename": "configure"}]}
        """
      })
  @DisplayName("Should fail without approving anything when a change list entry is malformed")
  void shouldFailWithoutApprovingAnythingWhenAChangeListEntryIsMalformed(String comparison)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result = review.upstreamComparison(comparison).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).doesNotContain("inventory is unchanged");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        {"status": "ahead", "files": [
          {"filename": "debian/changelog"}, "unexpected-entry", {"filename": "LICENSE.md"}]}
        {"status": "", "files": []}
        """,
        """
        {"status": "ahead", "files": [{"filename": "debian/changelog"}, 7, \
        {"filename": "configure"}]}{"status": "", "files": []}
        """
      })
  @DisplayName("Should fail without approving anything when the comparison is several documents")
  void shouldFailWithoutApprovingAnythingWhenTheComparisonIsSeveralDocuments(String comparison)
      throws Exception {
    var review = review();
    var reviewedInputs = review.reviewedInputs();

    var result = review.upstreamComparison(comparison).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).doesNotContain("inventory is unchanged");
    assertThat(review.reviewedInputs()).isEqualTo(reviewedInputs);
  }

  private ReviewFixture review() throws IOException {
    return review(LOCKED_RELEASE);
  }

  private ReviewFixture review(String lockedRelease) throws IOException {
    var repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
    var buildpack = Files.createDirectories(repository.resolve(BUILDPACK));
    copyReviewedInputs(buildpack);
    var version = lockedRelease.substring(1);
    Files.writeString(buildpack.resolve("release"), lockedRelease + "\n");
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
                lockedRelease,
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
        media_type=
        while (( $# > 0 )); do
          case "$1" in
            --output)
              output="$2"
              shift 2
              ;;
            --header)
              if [[ "$2" == 'Accept: '* ]]; then
                media_type="${2#Accept: }"
              fi
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
        if [[ "${url}" == "${FAKE_COMPARISON_URL}" ]]; then
          cp "${FAKE_COMPARISON}" "${output}"
          exit 0
        fi

        content="${url#"${FAKE_CONTENTS_URL}/"}"
        if [[ "${content}" == "${url}" ]]; then
          echo "Unexpected URL: ${url}" >&2
          exit 1
        fi
        if [[ -n "${FAKE_CONTENTS_EXIT:-}" ]]; then
          exit "${FAKE_CONTENTS_EXIT}"
        fi
        file="${FAKE_CONTENTS}/${content#*\\?ref=}/${content%%\\?ref=*}"
        if [[ ! -f "${file}" ]]; then
          echo "curl: (22) The requested URL returned error: 404" >&2
          exit 22
        fi
        if [[ "${media_type}" != application/vnd.github.raw+json ]]; then
          printf '{"type": "file", "encoding": "base64"}\\n' >"${output}"
          exit 0
        fi
        cp "${file}" "${output}"
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

  private static String modifying(String target, String addedLine) {
    return """
        Index: FFmpeg/%1$s
        ===================================================================
        --- FFmpeg.orig/%1$s
        +++ FFmpeg/%1$s
        @@ -1,2 +1,3 @@
         unchanged
        +%2$s
         unchanged
        """
        .formatted(target, addedLine);
  }

  private static String creating(String target) {
    return """
        Index: FFmpeg/%1$s
        ===================================================================
        --- /dev/null
        +++ FFmpeg/%1$s
        @@ -0,0 +1,2 @@
        +/* This file is part of FFmpeg. */
        +int created;
        """
        .formatted(target);
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

  @Builder
  private record PatchChange(
      String status, String path, String previousPath, String reviewed, String locked) {}

  private static final class ReviewFixture {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ArrayNode files = mapper.createArrayNode();
    private final Path repository;
    private final Path upstreamRequests;
    private final Path comparison;
    private final Path contents;
    private final ScriptCommand command;

    private ReviewFixture(Path repository, Path commands, Path temporaryDirectory)
        throws IOException {
      this.repository = repository;
      upstreamRequests = Files.createFile(temporaryDirectory.resolve("upstream-requests"));
      comparison = temporaryDirectory.resolve("comparison.json");
      contents = Files.createDirectory(temporaryDirectory.resolve("contents"));
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
                      .formatted(reviewed("source_revision"), LOCKED_REVISION))
              .environment("FAKE_CONTENTS", contents.toString())
              .environment("FAKE_CONTENTS_URL", CONTENTS_URL);
    }

    private ReviewFixture upstreamChanges(String... paths) throws IOException {
      List.of(paths).forEach(path -> files.addObject().put("filename", path));
      return upstreamChangeList();
    }

    private ReviewFixture upstreamPatch(PatchChange change) throws IOException {
      var previousPath = Optional.ofNullable(change.previousPath());
      var file = files.addObject().put("filename", change.path()).put("status", change.status());
      previousPath.ifPresent(path -> file.put("previous_filename", path));
      serve(reviewed("source_revision"), previousPath.orElse(change.path()), change.reviewed());
      serve(LOCKED_REVISION, change.path(), change.locked());
      return upstreamChangeList();
    }

    private void serve(String revision, String path, String body) throws IOException {
      if (body == null) {
        return;
      }

      var file = contents.resolve(revision).resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, body);
    }

    private ReviewFixture upstreamChangeList() throws IOException {
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

    private ReviewFixture patchFailure(int curlExitCode) {
      command.environment("FAKE_CONTENTS_EXIT", Integer.toString(curlExitCode));
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
