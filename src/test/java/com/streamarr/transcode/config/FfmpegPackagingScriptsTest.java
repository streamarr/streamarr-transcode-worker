package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("FFmpeg Packaging Script Tests")
class FfmpegPackagingScriptsTest {

  private static final Path BUILDPACK = Path.of("buildpacks/ffmpeg/bin/build").toAbsolutePath();
  private static final Path LOCK_UPDATER =
      Path.of("buildpacks/ffmpeg/bin/update-lock").toAbsolutePath();
  private static final Path LOCK_LIBRARY =
      Path.of("buildpacks/ffmpeg/lib/lock.sh").toAbsolutePath();
  private static final Path HTTP_LIBRARY =
      Path.of("buildpacks/ffmpeg/lib/http.sh").toAbsolutePath();
  private static final Path IMAGE_VERIFIER =
      Path.of(".github/actions/pack-build/verify-ffmpeg-image.sh").toAbsolutePath();

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void prepareRedistributionMaterials() throws Exception {
    var process =
        new ProcessBuilder("buildpacks/ffmpeg/bin/prepare").redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as(output).isZero();
  }

  @Test
  @DisplayName("Should validate clean checkout when generated redistribution files are absent")
  void shouldValidateCleanCheckoutWhenGeneratedRedistributionFilesAreAbsent() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var generated = updater.lock().getParent().resolve("generated");
    Files.move(generated, generated.resolveSibling("unused-output"));

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(generated).doesNotExist();
  }

  @Test
  @DisplayName("Should reject stale notice inventory when validating the FFmpeg lock offline")
  void shouldRejectStaleNoticeInventoryWhenValidatingFfmpegLockOffline() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var manifest = updater.lock().getParent().resolve("notices/manifest");
    Files.writeString(
        manifest, Files.readString(manifest).replace("source_revision=", "source_revision=stale"));

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg notice inventory is stale", "source_revision");
  }

  @ParameterizedTest
  @EnumSource(UnreviewedEdit.class)
  @DisplayName("Should reject a reviewed input edited after the manifest bound the inventory")
  void shouldRejectAReviewedInputEditedAfterTheManifestBoundTheInventory(UnreviewedEdit edit)
      throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    edit.applyTo(updater.lock().getParent());

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg notice inventory changed since it was bound");
  }

  @Test
  @DisplayName("Should validate checked in FFmpeg release lock when upstream is unavailable")
  void shouldValidateCheckedInFfmpegReleaseLockWhenUpstreamIsUnavailable() throws Exception {
    assertThat(LOCK_UPDATER).exists().isExecutable();

    var result = command(LOCK_UPDATER).argument("--check").execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should regenerate FFmpeg lock when upstream release metadata is coherent")
  void shouldRegenerateFfmpegLockWhenUpstreamReleaseMetadataIsCoherent() throws Exception {
    var updater = lockUpdater();
    var result = updater.command().execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(updater.lock()))
        .isEqualTo(
            """
            release=v8.1.2-4
            version=8.1.2-4
            source_revision=9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b
            asset_variant=gpl
            amd64_asset=jellyfin-ffmpeg_8.1.2-4_portable_linux64-gpl.tar.xz
            amd64_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            arm64_asset=jellyfin-ffmpeg_8.1.2-4_portable_linuxarm64-gpl.tar.xz
            arm64_sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            """);
  }

  @Test
  @DisplayName("Should resolve explicit FFmpeg release when repository input is untrusted")
  void shouldResolveExplicitFfmpegReleaseWhenRepositoryInputIsUntrusted() throws Exception {
    var updater = lockUpdater();
    Files.writeString(updater.lock().resolveSibling("release"), "not-a-release\n");

    var result = updater.command().argument("--release").argument("v8.1.2-4").execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(updater.lock())).startsWith("release=v8.1.2-4\n");
  }

  @Test
  @DisplayName("Should resolve FFmpeg release when an explicitly trusted file is provided")
  void shouldResolveFfmpegReleaseWhenAnExplicitlyTrustedFileIsProvided() throws Exception {
    var updater = lockUpdater();
    Files.writeString(updater.lock().resolveSibling("release"), "not-a-release\n");
    var trustedRelease = temporaryDirectory.resolve("trusted-release");
    Files.writeString(trustedRelease, "v8.1.2-4\n");

    var result =
        updater.command().argument("--release-file").argument(trustedRelease.toString()).execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(updater.lock())).startsWith("release=v8.1.2-4\n");
  }

  @Test
  @DisplayName("Should accept one FFmpeg release line when the final newline is absent")
  void shouldAcceptOneFfmpegReleaseLineWhenTheFinalNewlineIsAbsent() throws Exception {
    var updater = lockUpdater();
    Files.writeString(updater.lock().resolveSibling("release"), "v8.1.2-4");

    var result = updater.command().execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should reject FFmpeg release when explicitly empty")
  void shouldRejectFfmpegReleaseWhenExplicitlyEmpty() throws Exception {
    var updater = lockUpdater();

    var result = updater.command().argument("--release").argument("").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected an exact Jellyfin release tag, found:");
  }

  @Test
  @DisplayName("Should report stale FFmpeg lock without modifying it when checking")
  void shouldReportStaleFfmpegLockWithoutModifyingItWhenChecking() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    Files.writeString(updater.lock().resolveSibling("release"), "v8.1.2-5\n");
    var staleLock = Files.readString(updater.lock());

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg lock is stale");
    assertThat(Files.readString(updater.lock())).isEqualTo(staleLock);
  }

  @Test
  @DisplayName("Should validate coherent FFmpeg lock without upstream access when checking")
  void shouldValidateCoherentFfmpegLockWithoutUpstreamAccessWhenChecking() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    Files.delete(updater.releaseJson());

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should preserve a coherent FFmpeg lock when verifying upstream metadata")
  void shouldPreserveCoherentFfmpegLockWhenVerifyingUpstreamMetadata() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var lock = Files.readString(updater.lock());

    var result = updater.command().argument("--verify-upstream").execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(Files.readString(updater.lock())).isEqualTo(lock);
  }

  @Test
  @DisplayName("Should reject FFmpeg lock when it differs from canonical upstream metadata")
  void shouldRejectFfmpegLockWhenItDiffersFromCanonicalUpstreamMetadata() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var noncanonicalLock = Files.readString(updater.lock()).replace("a".repeat(64), "c".repeat(64));
    Files.writeString(updater.lock(), noncanonicalLock);

    var result = updater.command().argument("--verify-upstream").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg lock does not match canonical upstream metadata");
    assertThat(Files.readString(updater.lock())).isEqualTo(noncanonicalLock);
  }

  @Test
  @DisplayName("Should reject missing FFmpeg lock when checking before upstream access")
  void shouldRejectMissingFfmpegLockWhenCheckingBeforeUpstreamAccess() throws Exception {
    var updater = lockUpdater();
    Files.delete(updater.lock());
    var attempts = temporaryDirectory.resolve("updater-curl-attempts");

    var result =
        updater
            .command()
            .argument("--verify-upstream")
            .environment("FAKE_CURL_ATTEMPTS", attempts.toString())
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected a regular readable FFmpeg lock file");
    assertThat(attempts).doesNotExist();
  }

  @Test
  @DisplayName("Should reject duplicate FFmpeg lock release when checking")
  void shouldRejectDuplicateFfmpegLockReleaseWhenChecking() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    Files.writeString(updater.lock(), "release=\n", StandardOpenOption.APPEND);

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected exactly one release entry");
  }

  @Test
  @DisplayName("Should reject an empty FFmpeg lock checksum when checking")
  void shouldRejectEmptyFfmpegLockChecksumWhenChecking() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var lock = Files.readString(updater.lock());
    Files.writeString(
        updater.lock(), lock.replaceAll("amd64_sha256=[0-9a-f]{64}", "amd64_sha256="));

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected exactly one amd64_sha256 entry");
  }

  @ParameterizedTest(name = "{2}")
  @MethodSource("incoherentLockValues")
  @DisplayName("Should reject incoherent FFmpeg lock values when checking")
  void shouldRejectIncoherentFfmpegLockValuesWhenChecking(
      String currentValue, String replacement, String expectedError) throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    var lock = Files.readString(updater.lock());
    Files.writeString(updater.lock(), lock.replace(currentValue, replacement));

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains(expectedError);
  }

  private static Stream<Arguments> incoherentLockValues() {
    return Stream.of(
        Arguments.of(
            "version=8.1.2-4", "version=8.1.2-5", "FFmpeg lock version contradicts release"),
        Arguments.of(
            "source_revision=9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b",
            "source_revision=not-a-commit",
            "FFmpeg lock source revision is not a full commit SHA"),
        Arguments.of(
            "amd64_asset=jellyfin-ffmpeg_8.1.2-4_portable_linux64-gpl.tar.xz",
            "amd64_asset=unexpected.tar.xz",
            "FFmpeg lock asset contradicts version and variant"),
        Arguments.of(
            "amd64_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "amd64_sha256=not-a-sha256",
            "FFmpeg lock checksum is not SHA-256"));
  }

  @Test
  @DisplayName("Should preserve lock when GitHub asset digests are missing")
  void shouldPreserveLockWhenGithubAssetDigestsAreMissing() throws Exception {
    var updater = lockUpdater();
    var staleLock = Files.readString(updater.lock());
    Files.writeString(
        updater.releaseJson(),
        Files.readString(updater.releaseJson()).replace("sha256:" + "a".repeat(64), ""));

    var result = updater.command().execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("SHA-256 GitHub asset digest");
    assertThat(Files.readString(updater.lock())).isEqualTo(staleLock);
  }

  @Test
  @DisplayName("Should not retry permanent HTTP failures when resolving an FFmpeg lock")
  void shouldNotRetryPermanentHttpFailuresWhenResolvingFfmpegLock() throws Exception {
    var updater = lockUpdater();

    var result = updater.command().environment("REJECT_RETRY_ALL_ERRORS", "true").execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should retry a transient partial transfer when resolving an FFmpeg lock")
  void shouldRetryTransientPartialTransferWhenResolvingFfmpegLock() throws Exception {
    var updater = lockUpdater();
    var attempts = temporaryDirectory.resolve("updater-curl-attempts");

    var result =
        updater
            .command()
            .environment("FAKE_CURL_TRANSIENT_EXIT", "18")
            .environment("FAKE_CURL_ATTEMPTS", attempts.toString())
            .execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readAllLines(attempts)).hasSize(3);
  }

  @Test
  @DisplayName("Should resolve full source revision when given a Jellyfin release tag")
  void shouldResolveFullSourceRevisionWhenGivenAJellyfinReleaseTag() throws Exception {
    var updater = lockUpdater();
    var fullRevision = "1234567890abcdef1234567890abcdef12345678";

    var result = updater.command().environment("FAKE_FFMPEG_COMMIT", fullRevision).execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(updater.lock()))
        .contains("version=8.1.2-4", "source_revision=" + fullRevision);
  }

  @Test
  @DisplayName("Should preserve lock when architecture assets are missing")
  void shouldPreserveLockWhenArchitectureAssetsAreMissing() throws Exception {
    var updater = lockUpdater();
    var staleLock = Files.readString(updater.lock());
    Files.writeString(
        updater.releaseJson(),
        Files.readString(updater.releaseJson()).replace("linuxarm64", "unsupported"));

    var result = updater.command().execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected exactly one", "linuxarm64");
    assertThat(Files.readString(updater.lock())).isEqualTo(staleLock);
  }

  private LockUpdaterFixture lockUpdater() throws Exception {
    var release = "v8.1.2-4";
    var version = "8.1.2-4";
    var fullRevision = "9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b";
    var amd64Asset = "jellyfin-ffmpeg_%s_portable_linux64-gpl.tar.xz".formatted(version);
    var arm64Asset = "jellyfin-ffmpeg_%s_portable_linuxarm64-gpl.tar.xz".formatted(version);
    var amd64Digest = "a".repeat(64);
    var arm64Digest = "b".repeat(64);
    var repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
    var buildpack = Files.createDirectories(repository.resolve("buildpacks/ffmpeg"));
    copyRedistributionFiles(buildpack);
    Files.writeString(
        buildpack.resolve("notices/manifest"),
        """
        release=%s
        source_revision=%s
        amd64_sha256=%s
        arm64_sha256=%s
        inventory_sha256=%s
        """
            .formatted(release, fullRevision, amd64Digest, arm64Digest, "0".repeat(64)));
    Files.writeString(buildpack.resolve("release"), release + "\n");
    var lock = buildpack.resolve("ffmpeg.lock");
    Files.copy(Path.of("buildpacks/ffmpeg/LICENSE.txt"), buildpack.resolve("LICENSE.txt"));
    Files.writeString(
        lock,
        lockWithVersion(version, fullRevision)
            .replace(
                lockValue(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), "amd64_sha256"), amd64Digest)
            .replace(
                lockValue(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), "arm64_sha256"), arm64Digest));
    generateFixtureMaterials(buildpack);
    Files.writeString(lock, "stale\n");
    var upstream = Files.createDirectory(temporaryDirectory.resolve("upstream"));
    var releaseJson = upstream.resolve("release.json");
    Files.writeString(
        releaseJson,
        """
        {
          "tag_name": "%s",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "%s",
              "digest": "sha256:%s",
              "browser_download_url": "https://downloads.example/%s"
            },
            {
              "name": "%s",
              "digest": "sha256:%s",
              "browser_download_url": "https://downloads.example/%s"
            }
          ]
        }
        """
            .formatted(
                release, amd64Asset, amd64Digest, amd64Asset, arm64Asset, arm64Digest, arm64Asset));
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    ScriptCommand.writeFake(
        commands,
        "curl",
        """
        if [[ "${REJECT_RETRY_ALL_ERRORS:-false}" == "true" \
          && " $* " == *" --retry-all-errors "* ]]; then
          echo 'Permanent HTTP failures must not be retried' >&2
          exit 1
        fi
        if [[ -n "${FAKE_CURL_ATTEMPTS:-}" ]]; then
          echo attempt >>"${FAKE_CURL_ATTEMPTS}"
          attempts="$(wc -l <"${FAKE_CURL_ATTEMPTS}" | tr -d ' ')"
          if [[ -n "${FAKE_CURL_TRANSIENT_EXIT:-}" && "${attempts}" == "1" ]]; then
            exit "${FAKE_CURL_TRANSIENT_EXIT}"
          fi
        fi
        output=
        url=
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
        case "${url}" in
          */releases/tags/*) cp "${FAKE_RELEASE_JSON}" "${output}" ;;
          */repos/jellyfin/jellyfin-ffmpeg/commits/v8.1.2-4) printf '{"sha":"%s"}\n' "${FAKE_FFMPEG_COMMIT}" >"${output}" ;;
          *) echo "Unexpected URL: ${url}" >&2; exit 1 ;;
        esac
        """);

    var command =
        command(LOCK_UPDATER)
            .argument("--root")
            .argument(repository.toString())
            .prependPath(commands)
            .environment("FAKE_RELEASE_JSON", releaseJson.toString())
            .environment("FAKE_FFMPEG_COMMIT", fullRevision);
    return new LockUpdaterFixture(lock, releaseJson, command);
  }

  @Test
  @DisplayName("Should contribute an FFmpeg launch dependency with an SBOM when buildpack runs")
  void shouldContributeFfmpegLaunchDependencyWithAnSbomWhenBuildpackRuns() throws Exception {
    var buildpack = buildpack();
    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(buildpack.layer().resolve("bin/ffmpeg")).exists().isExecutable();
    assertThat(Files.readString(buildpack.layers().resolve("ffmpeg.toml")))
        .contains("launch = true", "cache = true");
    assertThat(buildpack.layer().resolve("env.launch")).doesNotExist();

    var sbom =
        new ObjectMapper()
            .readTree(Files.readString(buildpack.layers().resolve("ffmpeg.sbom.cdx.json")));
    assertThat(sbom.path("components").get(0).path("name").asString()).isEqualTo("FFmpeg");
    assertThat(sbom.path("components").get(0).path("version").asString())
        .isEqualTo(lockValue(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), "version"));
  }

  @Test
  @DisplayName("Should contribute selected FFmpeg version when runtime lock changes")
  void shouldContributeSelectedFfmpegVersionWhenRuntimeLockChanges() throws Exception {
    var buildpackRoot = Files.createDirectories(temporaryDirectory.resolve("future-buildpack"));
    var buildpackScript = copyBuildpackScript(buildpackRoot);
    var futureVersion = "8.2.0-1";
    var futureLock = lockWithVersion(futureVersion, "abcdef1234" + "0".repeat(30));
    Files.writeString(buildpackRoot.resolve("ffmpeg.lock"), futureLock);
    Files.writeString(
        buildpackRoot.resolve("notices/manifest"),
        String.join(
                "\n",
                futureLock
                    .lines()
                    .filter(
                        line ->
                            line.matches("(release|source_revision|amd64_sha256|arm64_sha256)=.*"))
                    .toList())
            + "\ninventory_sha256="
            + "0".repeat(64)
            + "\n");
    generateFixtureMaterials(buildpackRoot);
    var buildpack = buildpack(buildpackScript);

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    var sbom =
        new ObjectMapper()
            .readTree(Files.readString(buildpack.layers().resolve("ffmpeg.sbom.cdx.json")));
    assertThat(sbom.path("components").get(0).path("version").asString()).isEqualTo(futureVersion);
  }

  @Test
  @DisplayName("Should remove obsolete PATH metadata when reusing cached FFmpeg layer")
  void shouldRemoveObsoletePathMetadataWhenReusingCachedFfmpegLayer() throws Exception {
    var buildpack = buildpack();
    assertThat(buildpack.execute().exitCode()).isZero();
    var obsoleteLaunchEnvironment = Files.createDirectory(buildpack.layer().resolve("env.launch"));
    Files.writeString(obsoleteLaunchEnvironment.resolve("PATH.prepend"), "obsolete");

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(obsoleteLaunchEnvironment).doesNotExist();
  }

  @Test
  @DisplayName("Should reject runtime banner when it does not match locked Jellyfin version")
  void shouldRejectRuntimeBannerWhenItDoesNotMatchLockedJellyfinVersion() throws Exception {
    var buildpack = buildpack();

    var result = buildpack.command().environment("FAKE_FFMPEG_SUFFIX", "other-build").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected Jellyfin FFmpeg runtime version");
  }

  @Test
  @DisplayName("Should reject runtime when its build configuration differs from the reviewed one")
  void shouldRejectRuntimeWhenItsBuildConfigurationDiffersFromTheReviewedOne() throws Exception {
    var buildpack = buildpack();
    var reviewed = Files.readString(Path.of("buildpacks/ffmpeg/notices/buildconf-amd64.txt"));
    var unreviewed = temporaryDirectory.resolve("unreviewed-buildconf.txt");
    Files.writeString(unreviewed, reviewed.replace("--enable-gpl", "--enable-gpl --enable-libnew"));

    var result =
        buildpack.command().environment("FAKE_FFMPEG_BUILDCONF", unreviewed.toString()).execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output())
        .contains("FFmpeg build configuration differs from notices/buildconf-amd64.txt");
  }

  @Test
  @DisplayName("Should accept version banner when prefixed by diagnostic output")
  void shouldAcceptVersionBannerWhenPrefixedByDiagnosticOutput() throws Exception {
    var buildpack = buildpack();

    var result =
        buildpack.command().environment("FAKE_FFMPEG_BANNER_PREFIX", "loader diagnostic").execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should not retry permanent HTTP failures when downloading a locked FFmpeg asset")
  void shouldNotRetryPermanentHttpFailuresWhenDownloadingLockedFfmpegAsset() throws Exception {
    var buildpack = buildpack();

    var result = buildpack.command().environment("REJECT_RETRY_ALL_ERRORS", "true").execute();

    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("Should retry a transient partial transfer when downloading locked FFmpeg asset")
  void shouldRetryTransientPartialTransferWhenDownloadingLockedFfmpegAsset() throws Exception {
    var buildpack = buildpack();
    var attempts = temporaryDirectory.resolve("buildpack-curl-attempts");

    var result =
        buildpack
            .command()
            .environment("FAKE_CURL_TRANSIENT_EXIT", "18")
            .environment("FAKE_CURL_ATTEMPTS", attempts.toString())
            .execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readAllLines(attempts)).hasSize(2);
  }

  @Test
  @DisplayName("Should reject FFmpeg lock asset when version and architecture do not match")
  void shouldRejectFfmpegLockAssetWhenVersionAndArchitectureDoNotMatch() throws Exception {
    var buildpackRoot = Files.createDirectories(temporaryDirectory.resolve("buildpack"));
    var buildpackScript = copyBuildpackScript(buildpackRoot);
    var lock = Files.readString(Path.of("buildpacks/ffmpeg/ffmpeg.lock"));
    var amd64Asset = lockValue(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), "amd64_asset");
    Files.writeString(
        buildpackRoot.resolve("ffmpeg.lock"),
        lock.replace("amd64_asset=" + amd64Asset, "amd64_asset=unexpected.tar.xz"));

    var result = buildpack(buildpackScript).execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg lock asset contradicts version and variant");
  }

  @Test
  @DisplayName("Should reject duplicate FFmpeg lock entries when the second value is empty")
  void shouldRejectDuplicateFfmpegLockEntryWhenSecondValueIsEmpty() throws Exception {
    var buildpackRoot = Files.createDirectories(temporaryDirectory.resolve("buildpack"));
    var buildpackScript = copyBuildpackScript(buildpackRoot);
    var lock = Files.readString(Path.of("buildpacks/ffmpeg/ffmpeg.lock"));
    Files.writeString(buildpackRoot.resolve("ffmpeg.lock"), lock + "amd64_asset=\n");

    var result = buildpack(buildpackScript).execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Expected exactly one amd64_asset entry");
  }

  @Test
  @DisplayName("Should extract portable Jellyfin binaries when archive has no wrapper directory")
  void shouldExtractPortableJellyfinBinariesWhenArchiveHasNoWrapperDirectory() throws Exception {
    var buildpack = buildpack();

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(buildpack.tarArguments()))
        .contains("ffmpeg ffprobe")
        .doesNotContain("--strip-components", "/bin/ffmpeg");
    assertThat(Files.readString(buildpack.layer().resolve("LICENSE.txt")))
        .isEqualTo(Files.readString(Path.of("buildpacks/ffmpeg/LICENSE.txt")));
  }

  @Test
  @DisplayName("Should ship bundled notices and source access when building FFmpeg layer")
  void shouldShipBundledNoticesAndSourceAccessWhenBuildingFfmpegLayer() throws Exception {
    var buildpack = buildpack();

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    var notices = Path.of("buildpacks/ffmpeg/notices");
    var document = Files.readString(buildpack.layer().resolve("THIRD-PARTY-NOTICES.txt"));
    assertThat(buildpack.layer().resolve("THIRD-PARTY-NOTICES.txt"))
        .hasBinaryContent(
            Files.readAllBytes(Path.of("buildpacks/ffmpeg/generated/THIRD-PARTY-NOTICES.txt")));
    var inventory = new ObjectMapper().readTree(Files.readString(notices.resolve("sources.json")));
    for (var component : inventory) {
      for (var notice : component.path("notices")) {
        assertThat(document)
            .contains(Files.readString(notices.resolve(notice.path("file").asString())));
      }
    }

    assertThat(buildpack.layer().resolve("notices/ffmpeg")).doesNotExist();
    var installedNotices = buildpack.layer().resolve("notices");
    try (var files = Files.walk(installedNotices)) {
      assertThat(
              files
                  .filter(Files::isRegularFile)
                  .map(installedNotices::relativize)
                  .map(Path::toString)
                  .toList())
          .containsExactlyInAnyOrder(
              "manifest",
              "sources.json",
              "buildconf-amd64.txt",
              "buildconf-arm64.txt",
              "fdk-aac-stripped/NOTICE.txt",
              "fdk-aac-stripped/README.fedora.txt");
    }
    assertThat(buildpack.layer().resolve("notices/buildconf-amd64.txt"))
        .hasSameTextualContentAs(notices.resolve("buildconf-amd64.txt"));
    assertThat(buildpack.layer().resolve("notices/buildconf-arm64.txt"))
        .hasSameTextualContentAs(notices.resolve("buildconf-arm64.txt"));
    assertThat(buildpack.layer().resolve("SOURCE.txt"))
        .hasSameTextualContentAs(Path.of("buildpacks/ffmpeg/SOURCE.txt"));
    assertThat(Files.readString(buildpack.layer().resolve("SOURCE.txt")))
        .contains("Corresponding Source", "fdk-aac-stripped", "builder/patches");
  }

  @ParameterizedTest
  @ValueSource(strings = {"amd64", "arm64", "x86_64", "aarch64"})
  @DisplayName("Should install generated sbom when architecture is requested")
  void shouldInstallGeneratedSbomWhenArchitectureIsRequested(String architecture) throws Exception {
    var buildpack = buildpack();

    var result = buildpack.command().environment("CNB_TARGET_ARCH", architecture).execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    var canonical =
        switch (architecture) {
          case "amd64", "x86_64" -> "amd64";
          default -> "arm64";
        };
    assertThat(buildpack.layers().resolve("ffmpeg.sbom.cdx.json"))
        .hasSameTextualContentAs(
            Path.of("buildpacks/ffmpeg/generated/ffmpeg." + canonical + ".cdx.json"));
  }

  @ParameterizedTest
  @CsvSource({"amd64,amd64", "x86_64,amd64", "arm64,arm64", "aarch64,arm64"})
  @DisplayName("Should verify the archive against the locked digest of the requested architecture")
  void shouldVerifyTheArchiveAgainstTheLockedDigestOfTheRequestedArchitecture(
      String architecture, String locked) throws Exception {
    var buildpack = buildpack();

    var result = buildpack.command().environment("CNB_TARGET_ARCH", architecture).execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    var lock = Path.of("buildpacks/ffmpeg/ffmpeg.lock");
    assertThat(Files.readString(buildpack.verified()))
        .startsWith(lockValue(lock, locked + "_sha256") + "  ")
        .endsWith("/" + lockValue(lock, locked + "_asset") + "\n");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SOURCE.txt",
        "LICENSE.txt",
        "notices/fdk-aac-stripped/NOTICE.txt",
        "notices/sources.json",
        "notices/buildconf-arm64.txt",
        "generated/THIRD-PARTY-NOTICES.txt",
        "generated/ffmpeg.amd64.cdx.json"
      })
  @DisplayName("Should reject stale redistribution materials when preparing FFmpeg download")
  void shouldRejectStaleRedistributionMaterialsWhenPreparingFfmpegDownload(String file)
      throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("stale-materials"));
    var script = copyBuildpackScript(root);
    Files.copy(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), root.resolve("ffmpeg.lock"));
    Files.writeString(root.resolve(file), "stale", StandardOpenOption.APPEND);
    var buildpack = buildpack(script);
    var attempts = temporaryDirectory.resolve("download-attempts");

    var result =
        buildpack.command().environment("FAKE_CURL_ATTEMPTS", attempts.toString()).execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg redistribution materials are stale", file);
    assertThat(attempts).doesNotExist();
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  @Test
  @DisplayName("Should reject stale source access when validating the lock offline")
  void shouldRejectStaleSourceAccessWhenValidatingLockOffline() throws Exception {
    var updater = lockUpdater();
    assertThat(updater.command().execute().exitCode()).isZero();
    Files.writeString(updater.lock().getParent().resolve("SOURCE.txt"), "stale");

    var result = updater.command().argument("--check").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("SOURCE.txt is missing source access");
  }

  @Test
  @DisplayName("Should reject omitted notice checksum when preparing FFmpeg download")
  void shouldRejectOmittedNoticeChecksumWhenPreparingFfmpegDownload() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("omitted-notice"));
    var script = copyBuildpackScript(root);
    Files.copy(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), root.resolve("ffmpeg.lock"));
    var notice = "notices/fdk-aac-stripped/NOTICE.txt";
    var sums = root.resolve("generated/SHA256SUMS");
    Files.writeString(sums, Files.readString(sums).replaceAll("(?m)^.*  " + notice + "\\n", ""));
    Files.writeString(root.resolve(notice), "incorrect notice");
    var buildpack = buildpack(script);

    var result = buildpack.execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains(notice);
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  @Test
  @DisplayName("Should reject missing required output checksum when preparing FFmpeg download")
  void shouldRejectMissingRequiredOutputChecksumWhenPreparingFfmpegDownload() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("missing-output-checksum"));
    var script = copyBuildpackScript(root);
    Files.copy(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), root.resolve("ffmpeg.lock"));
    var output = "generated/THIRD-PARTY-NOTICES.txt";
    var sums = root.resolve("generated/SHA256SUMS");
    var entries = Files.readAllLines(sums);
    assertThat(entries.stream().filter(line -> line.endsWith("  " + output))).hasSize(1);
    Files.write(sums, entries.stream().filter(line -> !line.endsWith("  " + output)).toList());
    var buildpack = buildpack(script);

    var result = buildpack.execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("checksum inventory mismatch", output);
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  @Test
  @DisplayName("Should restore notices when the FFmpeg binary layer is reused from cache")
  void shouldRestoreNoticesWhenFfmpegBinaryLayerIsReusedFromCache() throws Exception {
    var buildpack = buildpack();
    assertThat(buildpack.execute().exitCode()).isZero();
    var notice = buildpack.layer().resolve("notices/fdk-aac-stripped/NOTICE.txt");
    Files.writeString(notice, "outdated notice");
    var document = buildpack.layer().resolve("THIRD-PARTY-NOTICES.txt");
    var sbom = buildpack.layers().resolve("ffmpeg.sbom.cdx.json");
    Files.writeString(document, "outdated document");
    Files.writeString(sbom, "outdated SBOM");
    var obsolete = buildpack.layer().resolve("notices/obsolete.txt");
    Files.writeString(obsolete, "obsolete cached notice");

    var result = buildpack.execute();

    assertThat(result.exitCode()).isZero();
    assertThat(notice)
        .hasSameTextualContentAs(Path.of("buildpacks/ffmpeg/notices/fdk-aac-stripped/NOTICE.txt"));
    assertThat(document)
        .hasBinaryContent(
            Files.readAllBytes(Path.of("buildpacks/ffmpeg/generated/THIRD-PARTY-NOTICES.txt")));
    assertThat(sbom)
        .hasBinaryContent(
            Files.readAllBytes(Path.of("buildpacks/ffmpeg/generated/ffmpeg.amd64.cdx.json")));
    assertThat(obsolete).doesNotExist();
  }

  @Test
  @DisplayName("Should reject missing checksum manifest when preparing FFmpeg download")
  void shouldRejectMissingChecksumManifestWhenPreparingFfmpegDownload() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("missing-checksums"));
    var script = copyBuildpackScript(root);
    Files.copy(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), root.resolve("ffmpeg.lock"));
    Files.delete(root.resolve("generated/SHA256SUMS"));
    var buildpack = buildpack(script);

    var result = buildpack.execute();

    assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
    assertThat(result.output())
        .contains("Expected regular FFmpeg redistribution checksums", "SHA256SUMS");
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(strings = {"release", "source_revision", "amd64_sha256", "arm64_sha256"})
  @DisplayName("Should reject stale notice inventory when preparing FFmpeg extraction")
  void shouldRejectStaleNoticeInventoryWhenPreparingFfmpegExtraction(String key) throws Exception {
    var buildpackRoot = Files.createDirectories(temporaryDirectory.resolve("stale-notices"));
    var script = copyBuildpackScript(buildpackRoot);
    Files.copy(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), buildpackRoot.resolve("ffmpeg.lock"));
    var manifest = buildpackRoot.resolve("notices/manifest");
    Files.writeString(manifest, Files.readString(manifest).replace(key + "=", key + "=stale"));
    var buildpack = buildpack(script);

    var result = buildpack.execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg notice inventory is stale", key);
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  @Test
  @DisplayName("Should stop before extraction when the archive checksum is incorrect")
  void shouldStopBeforeExtractionWhenArchiveChecksumIsIncorrect() throws Exception {
    var buildpack = buildpack();

    var result = buildpack.command().environment("FAKE_SHA256_EXIT", "1").execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(buildpack.tarArguments()).doesNotExist();
  }

  private BuildpackFixture buildpack() throws IOException {
    return buildpack(BUILDPACK);
  }

  private static Path copyBuildpackScript(Path buildpackRoot) throws IOException {
    var buildpackBin = Files.createDirectories(buildpackRoot.resolve("bin"));
    var buildpackLibrary = Files.createDirectories(buildpackRoot.resolve("lib"));
    var buildpackScript = Files.copy(BUILDPACK, buildpackBin.resolve("build"));
    Files.copy(LOCK_LIBRARY, buildpackLibrary.resolve("lock.sh"));
    Files.copy(HTTP_LIBRARY, buildpackLibrary.resolve("http.sh"));
    Files.copy(Path.of("buildpacks/ffmpeg/lib/notices.sh"), buildpackLibrary.resolve("notices.sh"));
    Files.copy(
        Path.of("buildpacks/ffmpeg/lib/materials.sh"), buildpackLibrary.resolve("materials.sh"));
    Files.copy(
        Path.of("buildpacks/ffmpeg/lib/checksum.sh"), buildpackLibrary.resolve("checksum.sh"));
    Files.copy(Path.of("buildpacks/ffmpeg/lib/runtime.sh"), buildpackLibrary.resolve("runtime.sh"));
    Files.copy(
        BUILDPACK.getParent().getParent().resolve("LICENSE.txt"),
        buildpackRoot.resolve("LICENSE.txt"));
    copyRedistributionFiles(buildpackRoot);
    assertThat(buildpackScript.toFile().setExecutable(true)).isTrue();
    return buildpackScript;
  }

  private static void copyRedistributionFiles(Path buildpackRoot) throws IOException {
    Files.copy(Path.of("buildpacks/ffmpeg/SOURCE.txt"), buildpackRoot.resolve("SOURCE.txt"));
    var root = Path.of("buildpacks/ffmpeg");
    try (var paths = Files.walk(root)) {
      var materials =
          paths.filter(
              source ->
                  source.startsWith(root.resolve("notices"))
                      || source.startsWith(root.resolve("generated")));
      for (var source : materials.filter(Files::isRegularFile).toList()) {
        var destination = buildpackRoot.resolve(root.relativize(source));
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
      }
    }
  }

  private BuildpackFixture buildpack(Path buildpackScript) throws IOException {
    var ffmpegVersion =
        lockValue(buildpackScript.getParent().getParent().resolve("ffmpeg.lock"), "version");
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var layers = Files.createDirectory(temporaryDirectory.resolve("layers"));
    var layer = layers.resolve("ffmpeg");
    var tarArguments = temporaryDirectory.resolve("tar-arguments");
    var verified = temporaryDirectory.resolve("verified");
    ScriptCommand.writeFake(
        commands,
        "curl",
        """
        if [[ "${REJECT_RETRY_ALL_ERRORS:-false}" == "true" \
          && " $* " == *" --retry-all-errors "* ]]; then
          echo 'Permanent HTTP failures must not be retried' >&2
          exit 1
        fi
        if [[ -n "${FAKE_CURL_ATTEMPTS:-}" ]]; then
          echo attempt >>"${FAKE_CURL_ATTEMPTS}"
          attempts="$(wc -l <"${FAKE_CURL_ATTEMPTS}" | tr -d ' ')"
          if [[ -n "${FAKE_CURL_TRANSIENT_EXIT:-}" && "${attempts}" == "1" ]]; then
            exit "${FAKE_CURL_TRANSIENT_EXIT}"
          fi
        fi
        while (( $# > 0 )); do
          if [[ "$1" == "--output" ]]; then
            archive="$2"
            shift 2
            continue
          fi
          shift
        done
        : > "${archive}"
        """);
    ScriptCommand.writeFake(
        commands,
        "sha256sum",
        """
        if [[ "$*" == *"generated/SHA256SUMS"* ]]; then
          exec shasum -a 256 "$@"
        fi
        if [[ " $* " == *" --strict "* && " $* " != *" --check "* ]]; then
          echo 'Reading a digest is not verifying one' >&2
          exit 1
        fi
        if [[ " $* " != *" --check "* ]]; then
          exec shasum -a 256 "$@"
        fi
        cat >"${FAKE_VERIFIED}"
        exit "${FAKE_SHA256_EXIT:-0}"
        """);
    ScriptCommand.writeFake(
        commands,
        "tar",
        """
        printf '%s\n' "$*" > "${FAKE_TAR_ARGUMENTS}"
        mkdir -p "${FAKE_FFMPEG_LAYER}/bin"
        cat >"${FAKE_FFMPEG_LAYER}/bin/ffmpeg" <<'SCRIPT'
        #!/bin/bash
        if [[ "$*" == *"-version"* ]]; then
          if [[ -n "${FAKE_FFMPEG_BANNER_PREFIX:-}" ]]; then
            printf '%s\n' "${FAKE_FFMPEG_BANNER_PREFIX}"
          fi
          printf '%s\n' \
            "ffmpeg version ${FAKE_FFMPEG_VERSION%-*}-${FAKE_FFMPEG_SUFFIX:-Jellyfin} Copyright" \
            'configuration: --enable-gpl --enable-libfdk-aac'
        fi
        if [[ "$*" == *"muxer=hls"* ]]; then
          printf '%s\n' '-hls_segment_options'
        fi
        if [[ "$*" == *"-buildconf"* ]]; then
          if [[ -n "${FAKE_FFMPEG_BANNER_PREFIX:-}" ]]; then
            printf '%s\n' "${FAKE_FFMPEG_BANNER_PREFIX}"
          fi
          case "${CNB_TARGET_ARCH}" in
            arm64 | aarch64) reviewed=buildconf-arm64.txt ;;
            *) reviewed=buildconf-amd64.txt ;;
          esac
          cat "${FAKE_FFMPEG_BUILDCONF:-${FAKE_FFMPEG_NOTICES}/${reviewed}}"
        fi
        SCRIPT
        cp "${FAKE_FFMPEG_LAYER}/bin/ffmpeg" "${FAKE_FFMPEG_LAYER}/bin/ffprobe"
        chmod +x "${FAKE_FFMPEG_LAYER}/bin/ffmpeg" "${FAKE_FFMPEG_LAYER}/bin/ffprobe"
        """);

    return BuildpackFixture.builder()
        .layers(layers)
        .layer(layer)
        .tarArguments(tarArguments)
        .verified(verified)
        .command(
            command(buildpackScript)
                .prependPath(commands)
                .environment("CNB_LAYERS_DIR", layers.toString())
                .environment("CNB_TARGET_ARCH", "amd64")
                .environment("FAKE_FFMPEG_LAYER", layer.toString())
                .environment("FAKE_TAR_ARGUMENTS", tarArguments.toString())
                .environment("FAKE_VERIFIED", verified.toString())
                .environment("FAKE_FFMPEG_VERSION", ffmpegVersion)
                .environment(
                    "FAKE_FFMPEG_NOTICES",
                    Path.of("buildpacks/ffmpeg/notices").toAbsolutePath().toString()))
        .build();
  }

  private static String lockValue(Path lock, String key) throws IOException {
    var prefix = key + "=";
    var values =
        Files.readAllLines(lock).stream()
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()))
            .toList();
    assertThat(values).as("exactly one %s entry in %s", key, lock).hasSize(1);
    assertThat(values.getFirst()).as("non-empty %s entry in %s", key, lock).isNotEmpty();
    return values.getFirst();
  }

  private void generateFixtureMaterials(Path root) throws Exception {
    var inventory = root.resolve("notices/sources.json");
    var currentRevision = lockValue(Path.of("buildpacks/ffmpeg/ffmpeg.lock"), "source_revision");
    var original = Files.readString(inventory);
    assertThat(original).contains(currentRevision);
    Files.writeString(
        inventory,
        original.replace(
            currentRevision, lockValue(root.resolve("ffmpeg.lock"), "source_revision")));
    var source = root.resolve("SOURCE.txt");
    Files.writeString(
        source,
        Files.readString(source)
            .replace(currentRevision, lockValue(root.resolve("ffmpeg.lock"), "source_revision")));
    var manifest = root.resolve("notices/manifest");
    Files.writeString(
        manifest,
        Files.readString(manifest)
            .replaceAll(
                "(?m)^inventory_sha256=.*$", "inventory_sha256=" + FfmpegInventoryDigest.of(root)));
    var result =
        command(Path.of("node"))
            .argument("buildpacks/ffmpeg/bin/generate-notices.mjs")
            .argument("--root")
            .argument(root.toString())
            .execute();
    assertThat(result.exitCode()).as(result.output()).isZero();
  }

  private static String lockWithVersion(String version, String sourceRevision) throws IOException {
    var lock = Path.of("buildpacks/ffmpeg/ffmpeg.lock");
    var currentVersion = lockValue(lock, "version");
    var currentSourceRevision = lockValue(lock, "source_revision");
    return Files.readString(lock)
        .replace(currentVersion, version)
        .replace("source_revision=" + currentSourceRevision, "source_revision=" + sourceRevision);
  }

  @Test
  @DisplayName("Should reject unsupported architecture when FFmpeg buildpack runs")
  void shouldRejectUnsupportedArchitectureWhenFfmpegBuildpackRuns() throws Exception {
    var result =
        command(BUILDPACK)
            .environment(
                "CNB_LAYERS_DIR", temporaryDirectory.resolve("layers").toAbsolutePath().toString())
            .environment("CNB_TARGET_ARCH", "riscv64")
            .execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("Unsupported FFmpeg target architecture: riscv64");
  }

  @Test
  @DisplayName("Should reject nonfree runtime when verifying packaged image")
  void shouldRejectNonfreeRuntimeWhenVerifyingPackagedImage() throws Exception {
    var verifier = imageVerifier();
    ScriptCommand.writeFake(
        verifier.runtime(),
        "ffmpeg",
        """
        printf '%s\\n' \\
          'ffmpeg version 8.2.0-Jellyfin Copyright' \\
          'configuration: --enable-gpl --disable-libfdk-aac --enable-nonfree'
        """);
    ScriptCommand.writeFake(verifier.runtime(), "ffprobe", ":");

    var result = verifier.command().execute();

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output()).contains("FFmpeg must not include nonfree components");
  }

  private ImageVerifierFixture imageVerifier() throws IOException {
    var verifierRoot = Files.createDirectories(temporaryDirectory.resolve("verifier"));
    var verifierDirectory =
        Files.createDirectories(verifierRoot.resolve(".github/actions/pack-build"));
    var imageVerifier =
        Files.copy(IMAGE_VERIFIER, verifierDirectory.resolve("verify-ffmpeg-image.sh"));
    assertThat(imageVerifier.toFile().setExecutable(true)).isTrue();
    var buildpackDirectory = Files.createDirectories(verifierRoot.resolve("buildpacks/ffmpeg"));
    var buildpackLibrary = Files.createDirectory(buildpackDirectory.resolve("lib"));
    Files.copy(LOCK_LIBRARY, buildpackLibrary.resolve("lock.sh"));
    var futureVersion = "8.2.0-1";
    var futureLock = lockWithVersion(futureVersion, "abcdef1234" + "0".repeat(30));
    Files.writeString(buildpackDirectory.resolve("ffmpeg.lock"), futureLock);
    var commands = Files.createDirectory(temporaryDirectory.resolve("commands"));
    var runtime = Files.createDirectories(temporaryDirectory.resolve("runtime/bin"));
    ScriptCommand.writeFake(
        commands,
        "docker",
        """
        if [[ "$1 $2" == "image inspect" ]]; then
          case "$*" in
            *org.opencontainers.image.version*) printf '%s\n' 'test' ;;
            *org.opencontainers.image.source*) printf '%s\n' 'https://github.com/streamarr/streamarr-transcode-worker' ;;
            *org.opencontainers.image.revision*) printf '%s\n' 'abc123' ;;
            *org.streamarr.contract.version*) printf '%s\n' 'test-contract' ;;
          esac
          exit
        fi
        if [[ " $* " != *" --interactive "* ]]; then
          exit
        fi
        PATH="${FAKE_FFMPEG_ROOT}/bin:${PATH}" exec /bin/bash -euo pipefail -s
        """);
    return new ImageVerifierFixture(
        runtime,
        command(imageVerifier)
            .argument("streamarr/streamarr-transcode-worker:test")
            .argument("test")
            .argument("https://github.com/streamarr/streamarr-transcode-worker")
            .argument("abc123")
            .argument("test-contract")
            .prependPath(commands)
            .environment("FAKE_FFMPEG_ROOT", runtime.getParent().toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"libx264", "libsvtav1"})
  @DisplayName("Should complete every image check when FFmpeg would otherwise consume shell input")
  void shouldCompleteEveryImageCheckWhenFfmpegWouldOtherwiseConsumeShellInput(String encoder)
      throws Exception {
    var verifier = imageVerifier();
    var probes = temporaryDirectory.resolve("completed-probes");
    ScriptCommand.writeFake(
        verifier.runtime(),
        "ffmpeg",
        """
        case " $* " in
          *' -version '*)
            printf '%s\\n' 'ffmpeg version 8.2.0-Jellyfin Copyright' 'configuration: --enable-gpl'
            exit 0 ;;
          *' muxer=hls '*) echo '-hls_segment_options'; exit 0 ;;
        esac
        if [[ " $* " == *" -c:v ${STDIN_ENCODER} "* && " $* " != *' -nostdin '* ]]; then
          cat >/dev/null
        fi
        output="${!#}"
        directory="$(dirname "${output}")"
        printf '%s\\n' '#EXT-X-MAP:URI="init.mp4"' 'segment0.m4s' >"${output}"
        printf segment >"${directory}/init.mp4"
        printf segment >"${directory}/segment0.m4s"
        """);
    ScriptCommand.writeFake(
        verifier.runtime(),
        "ffprobe",
        """
        case "${!#}" in
          */playlist.m3u8) echo hls >>"${COMPLETED_PROBES}"; echo 'format_name=hls' ;;
          */av1.mp4) echo av1 >>"${COMPLETED_PROBES}"; echo 'codec_name=av1' ;;
        esac
        """);

    var result =
        verifier
            .command()
            .environment("STDIN_ENCODER", encoder)
            .environment("COMPLETED_PROBES", probes.toString())
            .execute();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(probes).exists();
    assertThat(Files.readAllLines(probes)).containsExactly("hls", "av1");
  }

  private ScriptCommand command(Path script) {
    return ScriptCommand.of(script);
  }

  private enum UnreviewedEdit {
    SOURCE_OFFER,
    BUILD_CONFIGURATION,
    LICENCE_EXPRESSION;

    private void applyTo(Path buildpack) throws IOException {
      switch (this) {
        case SOURCE_OFFER ->
            append(buildpack.resolve("SOURCE.txt"), "Ask for the source by post instead.\n");
        case BUILD_CONFIGURATION ->
            append(buildpack.resolve("notices/buildconf-amd64.txt"), " --enable-libunreviewed\n");
        case LICENCE_EXPRESSION -> {
          var inventory = buildpack.resolve("notices/sources.json");
          Files.writeString(
              inventory,
              Files.readString(inventory)
                  .replace(
                      "\"licenseExpression\": \"MIT\"",
                      "\"licenseExpression\": \"LicenseRef-unreviewed\""));
        }
      }
    }

    private static void append(Path input, String unreviewed) throws IOException {
      Files.writeString(input, Files.readString(input) + unreviewed);
    }
  }

  private record LockUpdaterFixture(Path lock, Path releaseJson, ScriptCommand command) {}

  private record ImageVerifierFixture(Path runtime, ScriptCommand command) {}

  @Builder
  private record BuildpackFixture(
      Path layers, Path layer, Path tarArguments, Path verified, ScriptCommand command) {

    private ScriptCommand.Result execute() throws IOException, InterruptedException {
      return command.execute();
    }
  }
}
