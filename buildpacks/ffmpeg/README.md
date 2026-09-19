# FFmpeg runtime lock

`release` is the only Renovate-owned input. `ffmpeg.lock` is generated from that exact
[Jellyfin FFmpeg release](https://github.com/jellyfin/jellyfin-ffmpeg/releases) and pins the
full package version, fork source commit, Linux amd64/arm64 portable GPL assets, and SHA-256
digests. Builds consume only this lock: no floating `latest`, distro FFmpeg, or daily autobuilds.
See [ADR 0029](https://github.com/streamarr/streamarr-adr/blob/main/adr/0029-pin-jellyfin-ffmpeg-releases.adoc).

Regenerate or validate the lock with:

```bash
buildpacks/ffmpeg/bin/update-lock
buildpacks/ffmpeg/bin/update-lock --release v8.1.2-4
buildpacks/ffmpeg/bin/update-lock --check
buildpacks/ffmpeg/bin/update-lock --verify-upstream
buildpacks/ffmpeg/bin/review-release
node buildpacks/ffmpeg/bin/vendor-notices.mjs --dry-run
```

The resolver requires Bash, `curl`, and `jq`. Offline input validation also requires
the Node.js toolchain declared in `buildpacks/ffmpeg/.nvmrc`. It accepts only published, non-prerelease tags
in `vMAJOR.MINOR.PATCH-BUILD` form, requires exactly one portable GPL asset and a GitHub
SHA-256 digest per architecture, resolves the tag's full fork commit, and writes the lock
atomically. `--check` validates the lock, independent review manifest, source-access
instructions and all inventoried notice bytes offline, without requiring or writing generated
artifacts; `--verify-upstream` regenerates canonical
metadata from GitHub and compares it byte-for-byte without modifying the lock.

The buildpack verifies the downloaded archive against the locked checksum before extracting
its root-level `ffmpeg` and `ffprobe` binaries. Jellyfin's banner omits the packaging revision:
package `8.1.2-4` reports `8.1.2-Jellyfin`; the checksum pins the exact build. The runtime must
enable GPL, omit the `--enable-nonfree` build flag, and support `hls_segment_options`.
Its `-buildconf` output, from the banner onward, must also equal the reviewed
`notices/buildconf-<architecture>.txt` capture byte for byte: the notice inventory starts from
that capture, so a binary with another toolchain, flag set or library version is unreviewed.
This flag check does not cover FDK-AAC: Jellyfin's GPL build includes stripped FDK-AAC
without setting `--enable-nonfree`. Streamarr selects FFmpeg's native `aac` encoder,
not `libfdk_aac`; the latter is nevertheless present in the distributed binary.
The layer includes GPLv3 text, bundled-library notices (including FDK's notice),
source-access instructions, and a CycloneDX SBOM.

## Redistribution materials

[`SOURCE.txt`](SOURCE.txt) supplies Corresponding Source locations, exact upstream
revisions and build/patch instructions. [`notices/sources.json`](notices/sources.json)
is the component metadata authority, including the origins/checksums of vendored
notice text inputs. The offline generator produces a consolidated notice document,
architecture-specific CycloneDX SBOMs, an input snapshot and checksums under `generated/`.
The inventory starts with each shipped Linux GPL binary's `-buildconf` output, then
traces transitive static libraries, embedded headers and generated loaders through
the pinned recipes. It is not a list of every build script or of external GPU drivers.

[`notices/manifest`](notices/manifest) binds that review to the release, source revision
and both archive digests. Offline validation and the buildpack reject a mismatch.
Neither the lock resolver nor the notice generator marks notices as reviewed: they never
write `notices/manifest`. Tests check notice contents against the inventory and verify their
inclusion in fresh and cached layers. Full license texts and attribution remain in
the image, not just links to them.

`bin/review-release` carries a review forward only when it can show that the inventory is
unchanged. It compares the reviewed and locked upstream revisions and requires the locked
revision to descend from the reviewed one, the change list to be complete, and every changed
path to lie outside the inventory: Jellyfin's changelog and patch series, the package
version, and files used only by macOS or Windows builds. It then rebinds `notices/manifest`,
the `ffmpeg` entry of `notices/sources.json` and `SOURCE.txt` to the locked release. The
buildpack supplies the rest of the evidence by rejecting a binary whose `-buildconf` differs
from the reviewed capture. A dependency recipe, toolchain image, licence text or FFmpeg
source file that upstream changes directly instead exits with status 3, names the paths and
changes nothing. Then review both binaries and their dependencies, update the notices and
source instructions, and update the manifest by hand.

Jellyfin's quilt patches under `debian/patches/` are judged by what they do, not by their
path: upstream's Linux build applies them to the FFmpeg tree before compiling, so a patch can
edit `configure`, a licence file, or add third-party source. For every added, modified,
renamed or removed `*.patch`, the script downloads the whole patch at the locked revision,
and at the reviewed revision when it existed there, because the comparison's diff of a diff
hides which files a modified patch touches. It exits with status 3, naming the patch and the
file, when:

- an added patch creates a file, or edits a file named `configure`, `LICENSE*` or `COPYING*`
  in any directory and letter case. A `/dev/null` old name, a hunk whose old range starts at
  line 0, git's `new file mode` and a git `index` line whose old blob is absent or empty all
  make `patch` create the file;
- a modified or renamed patch creates a file that its reviewed version did not, or the lines
  it adds to or removes from those files differ from the reviewed version;
- a removed patch edited one of those files;
- a patch cannot be downloaded or holds anything but unified-diff file headers and hunks,
  because `patch` searches whatever text a reader skips for further diffs: a description,
  indented or nested headers, context, normal and ed diffs, renames, copies, binary patches
  and file sections naming two files all count. So does a NUL byte, because some `awk`
  implementations end the line there while `patch` applies the bytes after it. The same
  applies when its change status is unknown, or any other file changes under
  `debian/patches/`.

A patch that only edits existing FFmpeg source files stays automatic, since that code remains
covered by the `ffmpeg` component's notices. The check selects the changes most likely to
need a new notice; it is not a licence scan and does not read the text a patch adds to a
source file, including a file that its reviewed version already created. The changelog and
patch series are outside the inventory by decision, not because they are harmless: quilt
applies whatever the series names with the options it gives, so an entry could apply the
changelog as a patch or reverse a patch with `-R`.

`bin/vendor-notices.mjs` regenerates the reviewed inputs from upstream when the inventory did
change. For the locked revision it reads the pin of every component from its recorded evidence:
the `SCRIPT_REPO`/`SCRIPT_COMMIT` pair of a `builder/scripts.d` recipe matched by repository, a
parent's `DEPS` file or submodule link, or the toolchain images' `ct-ng-config`. A moved pin has
its notice URLs rewritten and each text fetched again. The text is taken with the first known
view (whole file, LF line endings, leading comment, licence comment blocks, text before
`/** @file`) that reproduces the reviewed bytes from the reviewed origin, and with that view
only: an excerpt is re-extracted the way it was reviewed, and a text counts as unchanged only
when the view it was reviewed with still yields the reviewed bytes. A licence reviewed as the
whole file that gains terms after a comment or marker, or changes its line endings, is a changed
text. Whatever else describes the pin moves with it: a notice that records `upstreamSha256`, the
checksum of an origin whose bytes differ from the vendored text, gets the checksum of the new
origin, and the role of a toolchain component names the new version. Every other role and
`version_note` is a person's wording and stays as reviewed. A recipe that swaps a dependency's
mirror is followed to the new repository. A
recipe that upstream renames or regroups is followed by repository, to the recipe that pins it
first or else to the only one that pins it, and reported as moved: the reviewed entry keeps
everything but its recipe path, and the `DEPS` entries and submodules resolved through it stay.
The tool fails when several recipes qualify, or when a recipe moves and swaps its repository at
once. A dropped recipe removes its component and the components resolved through it, and notice
files that nothing references are deleted.

Every pin of a recipe that is in the binaries must be claimed by a component, matched by
repository across the whole inventory, or the tool proposes it as a new component. That covers
a pin an inventoried recipe gains, as `20-libiconv.sh` gained gnulib. A recipe is in the
binaries when a component is already built from it, when the reviewed build configurations
enable one of the `--enable-*` flags it echoes, or when it has none and its path is new since
the review. A recipe that a review left out is therefore proposed once a refreshed capture
shows its flag, while one without flags stays out until upstream gives it a new path. Only a
plain `echo --enable-...` line counts as a flag, and an unclaimed pin of such a recipe is
proposed again on every run until a component records it: a dependency that is only a build
input is recorded with the `build-input` distribution. A proposed component gets the
architectures that rule names and its licence files come from the repository listing. Its id
comes from the recipe name, or from the repository name for a later pin, and also names its
notice directory: the tool fails rather than propose an id that the generator would refuse or
that a reviewed or another proposed component holds. New `DEPS` entries and submodules are not
discovered: they are followed only for components the inventory already records.

Notices whose texts were byte-identical at review share one file, within a component (OpenMPT's
two licence files, ffnvcodec's header excerpts) or across components. When some of them change,
every distinct text of the group ends in one file. The file keeps the text of the notice it is
named after, or else of the first notice in its component's directory; when neither remains, it
keeps the reviewed text. Every other text, including a reviewed text that is still in use, moves to
the file named after the first notice that carries it. The tool changes nothing if a planned file
would not hold the text recorded for every notice that references it.

The tool writes `notices/sources.json`, the notice files and `SOURCE.txt`, whose component
index is generated from the inventory. It never writes `notices/manifest`, changes nothing when
it cannot follow a pin, and ends by stating whether inventory content changed. Pins, paths and
transfer diagnostics in the report come from upstream, so control characters and line breaks in
them are printed as `\uXXXX` escapes: only the tool's own closing line of a successful run starts
with `Inventory content`, and a failed run has no such line. The comparison is with
`notices/sources.json` in the working tree, which is the reviewed inventory only while its `ffmpeg`
entry names the revision that `notices/manifest` binds. The tool's own output moves that entry, so
a further run on a regenerated inventory, `--dry-run` included, states that the content was not
compared with the review instead of calling it unchanged: review the regenerated inputs against
the reviewed commit. Generated roles,
`LicenseRef-<component>` fallbacks and discovered licence files are proposals for the reviewer.
Refresh the buildconf captures first when the binaries' configuration changed. `--dry-run`
reports without writing.

The [tooling pin](.nvmrc) selects Node.js 24 LTS as the tested toolchain. CI selects
that exact version; local tooling accepts the same major. The generator itself
does not require Node 24, but Maven enforces native test-coverage thresholds whose
[CLI flags were introduced in Node 22.8](https://nodejs.org/docs/latest-v24.x/api/cli.html#--test-coverage-linesthreshold).
That API floor is not a claim that we test every subsequent Node release.
Node is developer/CI tooling only, not a requirement inside the FFmpeg buildpack
or the worker image. Keep the pin here, not at the application root:
[Paketo Node Engine](https://github.com/paketo-buildpacks/node-engine/blob/v8.5.2/detect.go)
self-requires Node for a root `.nvmrc` or `.node-version`.

```bash
nvm install "$(cat buildpacks/ffmpeg/.nvmrc)"
nvm use "$(cat buildpacks/ffmpeg/.nvmrc)"
buildpacks/ffmpeg/bin/update-lock --check
buildpacks/ffmpeg/bin/prepare
# Save generated/review-inputs.json before changing the reviewed inputs.
buildpacks/ffmpeg/bin/prepare --compare /path/to/previous-review-inputs.json
buildpacks/ffmpeg/bin/prepare --check
node --test buildpacks/ffmpeg/test/generate-notices.test.mjs
```

Commit the reviewed inputs, not the reproducible outputs: `generated/` is Git-ignored.
The shared CI preparation action validates those inputs and generates artifacts before
each image build and host-runtime installation. A direct local `bin/build` invocation
needs `bin/prepare` first; checksum validation requires `sha256sum` or `shasum`.
Generation makes no network calls and does not fetch, classify or approve new dependencies.
`--compare` reports changes
to component metadata (including recipes), the lock and input checksums, including
both build configurations. It does not replace reviewing actual linked dependencies
and upstream license changes. To recover a prior snapshot, check out that Git revision
in a separate worktree and run the current generator with `--root` pointing at that
worktree's `buildpacks/ffmpeg` directory. Then compare its locally generated snapshot.
Do not substitute regeneration for human review of `notices/manifest`.

The generator's `--check` verifies prepared artifacts without modifying them.
The normal Maven build proves clean-input generation and reproducibility, runs the
generator contract tests, and enforces at least 90% line/function and 85% branch coverage.
The shell-only buildpack requires the checksum list to cover every notice/input and
expected output exactly once, then verifies every listed digest before use. This
detects incomplete or stale preparation; it is not a signature or substitute for
reviewing the source inventory and upstream release.
The notice directory must contain only inventoried inputs: stray files such as
macOS `.DS_Store` also cause an inventory mismatch and must be removed before packaging.

`licenseExpression` records SPDX identifiers/expressions where the inventoried terms
can be represented accurately. A `LicenseRef-<component>` refers to that component's
preserved notice bundle, not a new license or a compatibility conclusion. Unique
texts (including FDK, glslang, FreeType and patent notices) are not paraphrased.
Only byte-identical texts are shared. Current inputs already use canonical document
suffixes; the generator also normalizes redundant suffixes such as `.md.txt` in
display names if a future imported notice has one. Source-code excerpts retain `.h.txt` or
`.c.txt` to distinguish the excerpt from a compilable source file.

SBOMs include runtime and embedded inputs for their architecture; build-only inputs
are separate in `formulation`. Source revisions are recorded as provenance rather
than invented release versions. Implib's revision is explicitly notice-source-only.
The layer ships `THIRD-PARTY-NOTICES.txt`, `SOURCE.txt`, GPLv3 text, the inventory,
review manifest and both buildconf captures. FDK's standalone notices are also kept.
Other raw text inputs remain in the repository; their verbatim content is consolidated
in the installed document. The matching SBOM is contributed through the CNB SBOM path.

Source links use upstream hosting; Streamarr remains responsible for keeping the
corresponding source available with its binary distributions. Check source access
before publishing; do not publish a binary whose required source is unavailable.

## Renovate synchronization

Renovate tracks `jellyfin/jellyfin-ffmpeg` with the `github-releases` datasource and
[regex versioning](https://docs.renovatebot.com/modules/versioning/regex/). The fourth numeric
component uses the `build` capture group: `-10` sorts after `-9`, and packaging-only fixes
are stable patch updates, not SemVer prereleases. Updates have their own PR and are not
automerged. Major-version updates additionally require Dependency Dashboard approval.
Renovate still proposes eligible minor and packaging updates automatically; their
notice review must complete before CI and image packaging can pass. The synchronization
workflow completes it when `bin/review-release` confirms an unchanged inventory; otherwise
it annotates the run with the paths and patches that need a person and leaves the review
inputs alone.

`.github/workflows/sync-ffmpeg-lock.yml` uses `pull_request_target` only for same-repository
Renovate PRs. It executes resolver and review code from the trusted PR base, reads the proposed
release from a Git object as data, and generates the lock and any carried-forward review
entirely in the trusted checkout. It never executes proposed code with write credentials, and
it never runs the downloaded binaries: ordinary CI checks their build configuration. The review
quotes upstream file names, which the runner would read for workflow commands, so the step
pauses command processing behind a random token while the review prints, escapes the text it
repeats as the annotation, and fences it in the job summary. Only after detecting a change and
verifying the head SHA does it mint a short-lived GitHub App token.

GitHub's `createCommitOnBranch` API creates a signed commit containing only the generated lock
and, after a confirmed review on a run started by Renovate's own push, `notices/manifest`,
`notices/sources.json` and `SOURCE.txt`. Renovate rebuilds its branch from the base, so only
then are the head's copies of those hand-maintained files known to be untouched. Every other
push, such as a maintainer's correction or a reverted manifest, still synchronizes the lock but
keeps those three files as pushed; the run warns when they differ from the carried-forward
review. Its `expectedHeadOid` check rejects a moved branch atomically. The App token triggers
normal PR checks after the commit; the default Actions token would suppress those runs.

Install a GitHub App on this repository with repository contents read/write access and configure
these Actions secrets:

- `FFMPEG_LOCK_APP_CLIENT_ID`
- `FFMPEG_LOCK_APP_PRIVATE_KEY`

Renovate's `gitIgnoredAuthors` includes the App's API-authored noreply email
(`315986519+streamarr-ffmpeg-lock[bot]@users.noreply.github.com`) and its historical Git-authored
email. Update these entries if the installed App identity changes so Renovate can continue
rebasing its own PR after the generated lock commit.

## CI and availability

Every CI run validates the lock offline. Live upstream metadata verification runs only when
the release, lock, resolver, or shared resolver libraries change. Ordinary read-only
`pull_request` CI tests the proposed resolver too, allowing resolver changes to be tested;
this is distinct from the privileged synchronization workflow's trusted-base boundary.
Release builds use offline metadata validation and verify the binary checksum at download time.

The `SmokeTest` group (including `HlsStreamingSmokeTest`) uses the locked runtime in
the amd64 application job. Packaging changes also run that group on an arm64 host;
the packaging matrix does not repeat the amd64 host run. Separately, both native
images are built and verified in-container for runtime identity, H.264/AAC fMP4 HLS
and AV1 encoding. The required `build` status aggregates all applicable checks.

Numbered Jellyfin releases avoid BtbN's rolling daily-build expiry. They are still upstream
assets, not a guarantee of immutable or permanent storage: a missing asset fails the build,
and a replaced asset fails checksum verification. CI caches are accelerators, not dependency
storage. Jellyfin also publishes the versioned binaries at its
[FFmpeg mirror](https://repo.jellyfin.org/files/ffmpeg/linux/), using paths such as
`8.x/8.1.2-4/amd64/<asset>`. This is an available second upstream source, not yet an
automatic buildpack fallback. Neither channel provides portable-tarball signatures
or a permanent-retention promise. A Streamarr-owned archive would be needed for
rebuilds independent of both upstream channels; that is not part of this migration.
