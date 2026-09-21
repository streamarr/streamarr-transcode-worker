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
buildpacks/ffmpeg/bin/review-release --approved
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

[`notices/manifest`](notices/manifest) binds that review to the release, source revision,
both archive digests and the reviewed content itself: `inventory_sha256` digests every file
under `notices/` except the manifest, together with `SOURCE.txt`. Offline validation and the
buildpack reject a mismatch, so an inventory, notice text, build configuration or source
offer edited after the binding no longer inherits it and needs an approval of its own.
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
changes nothing. Then review both binaries and their dependencies, regenerate the notices and
source instructions with `bin/vendor-notices.mjs` and the captures with `bin/capture-buildconf`,
review the diff, and bind `notices/manifest` with an approving review of the labelled Renovate
pull request (see [Renovate synchronization](#renovate-synchronization)), or with
`bin/review-release --approved` for a change Renovate did not propose. Never edit
`notices/manifest` by hand.

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
its notice URLs rewritten and each text fetched again. A notice without `"excerpt": true` in the
inventory was reviewed as the whole file and is read with the first view, the whole file or the
whole file with LF line endings, that reproduces it from the reviewed origin: a licence file that
gains terms after a comment or marker, or whose vendored bytes change their line endings, is a
changed text. The tool fails when neither view reproduces it, as when a moved tag serves more than
the review read. Several excerpt views (leading comment, licence comment blocks, text before
`/** @file`) can reproduce an excerpt from the reviewed origin; nothing records which one the
review used, so the new text is read with every one that does. It is unchanged only while each of them still
yields the reviewed bytes. When they find one new text, that text is vendored even if another
view still yields the reviewed bytes; when they find different new texts, the tool fails. Licence
files are never decoded: a file that is not UTF-8, or that starts with a byte order
mark, is hashed, excerpted and written as upstream's bytes. Whatever else describes the pin moves with it: a notice that records `upstreamSha256`, the
checksum of an origin whose bytes differ from the vendored text, gets the checksum of the new
origin, and a toolchain component's role, like the prose of `SOURCE.txt` for any pin, names the
new version or revision where it named the old one as a whole token. A version inside a longer
dotted one, such as `2.28` in `4.2.28`, is left alone, but a file extension does not make a token
longer: `<revision>.tar.gz` names the new revision. The role
of every moved toolchain component is listed as a toolchain role to review, because a person can
name the version in any wording, such as `GCC 15`, `v15.2.0` or `gcc15`, that no rewrite finds.
Every other role and `version_note` is a person's wording and stays as reviewed. A recipe that swaps a dependency's
mirror is followed to the new repository. A
recipe that upstream renames or regroups is followed by repository, to the recipe that pins it
first or else to the only one that pins it, and reported as moved: the reviewed entry keeps
everything but its recipe path, and the `DEPS` entries and submodules resolved through it stay.
A recipe that another reviewed component of the same repository is still built from is not
followed to: it builds that component's library from another branch, as `50-rkmpp.sh` and
`50-rkrga.sh` both pin `rk-mirrors`.
A component that no recipe pins any more moves with the components built with it when they all
moved to one recipe, and a repository swapped there is followed as it is in place. The tool fails
when several recipes qualify. Any other component whose recipe is gone counts as dropped: it is
removed, and notice files that nothing references are deleted. A recipe renamed together with a
swap of its repository looks the same, so it is reported as a removed and an added component,
and the tool fails when the added id is a reviewed one or when `DEPS` entries or submodules are
resolved through the removed component, rather than drop dependencies that are still linked.

Every pin of a recipe that is in the binaries must be claimed by a component, matched by
repository and revision across the whole inventory, or the tool proposes it as a new component.
The revision counts because one repository can hold several libraries on its branches, as
`rk-mirrors` holds rkmpp and rkrga. The architectures count because a recipe puts what it builds
in every binary it is in: a component claims a pin only when it covers every architecture the
recipe is built for, so a component built for one binary, as amf and libvpl are for amd64 and
libne10, rkmpp and rkrga for arm64, does not account for that source in the other. A component
the review built from this recipe claims its own pin whatever architectures it records, because
the review read that recipe. That covers a pin an inventoried recipe gains, as
`20-libiconv.sh` gained gnulib, and a pin that moves away from the revision a component of
another recipe records. A recipe is in the
binaries when a component is already built from it, when the reviewed build configurations
enable one of the `--enable-*` flags it echoes, or when it has none and its path is new since
the review. A recipe that a review left out is therefore proposed once a refreshed capture
shows its flag, while one without flags stays out until upstream gives it a new path. A recipe's
flags are the `--enable-*` words of every line that runs `echo` or `printf`, wherever the command
stands on the line and whatever else it prints, as in `[[ $TARGET == linux* ]] && echo --enable-vaapi`
or `echo --disable-w32threads --enable-pthreads`. A line continued with a backslash counts as one
line, a comment is not read, and options a recipe passes to its own build are not flags. An unclaimed pin of a recipe in the binaries is proposed again on every run until
a component records it: a dependency that is only a build input is recorded with the
`build-input` distribution. A proposed component gets the
architectures that rule names and its licence files come from the repository listing. Its id
comes from the recipe name, or from the repository name for a later pin, and also names its
notice directory: the tool fails rather than propose an id that the generator would refuse or
that a reviewed or another proposed component holds, or a pin that names a branch instead of a
commit and so names no source to read a licence text from. New `DEPS` entries and submodules are not
discovered: they are followed only for components the inventory already records.

Notices whose texts were byte-identical at review share one file, within a component (OpenMPT's
two licence files, ffnvcodec's header excerpts) or across components. When some of them change,
every distinct text of the group ends in one file. The file keeps the text of the notice it is
named after, or else of the first notice in its component's directory; when neither remains, it
keeps the reviewed text. Every other text, including a reviewed text that is still in use, moves to
the file named after the first notice that carries it. The tool changes nothing if a planned file
would not hold the text recorded for every notice that references it, or if two paths of the
planned tree, including those of a proposed component, or a path and one `notices/` already
holds, differ only by letter case: a checkout on a case-insensitive filesystem, as on macOS,
holds two spellings of a file, and of a directory above it, as one, so a text written under the
second spelling would land in the file that holds the first and be deleted with it, because
nothing references the spelling that file is read under.

The tool writes `notices/sources.json`, the notice files and `SOURCE.txt`, whose component
index is generated from the inventory. It never writes `notices/manifest`, changes nothing when
it cannot follow a pin, and ends by stating whether inventory content changed. Pins, paths and
transfer diagnostics in the report come from upstream, so control characters and line breaks in
them are printed as `\uXXXX` escapes: only the tool's own closing line of a successful run starts
with `Inventory content`. The report is printed only after the inputs are written, so a failed
run, including one that fails while writing, has no such line. The comparison is with
`notices/sources.json` in the working tree, which is the reviewed inventory only while its `ffmpeg`
entry names the revision that `notices/manifest` binds. The tool writes only for a release the
manifest does not bind, and its output moves that entry, so a further run on a regenerated
inventory, `--dry-run` included, states that the content was not compared with the review instead
of calling it unchanged: review the regenerated inputs against the reviewed commit. At the release
the manifest binds, a run only reports, even without `--dry-run`, because a rewritten inventory
there would still name the bound revision and pass for the reviewed one: a person records a change
it reports, or restores the reviewed inputs when the lock returns to that release. Generated roles,
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
notice review must complete before CI and image packaging can pass.

The synchronization workflow does the mechanical work and leaves the judgement to a person:

1. An unprivileged job per architecture downloads the locked archive, verifies it and captures
   its `-buildconf` with `bin/capture-buildconf`. It is the only job that runs upstream's binary;
   it holds no secret and no write permission, and hands over a text artifact.
2. The synchronization job adopts those captures as data: each is a regular file of at most
   64 KiB that starts with the banner and holds nothing but printable bytes and newlines, so an
   adopted capture stays a text the review reads as a diff. It then regenerates the notice inputs
   with `bin/vendor-notices.mjs`, and runs `bin/review-release` when nothing in the inventory or
   the build configurations changed.
3. An unchanged inventory is bound and committed with the lock. A changed one is committed
   without a binding: an approval covers only the content it was submitted on, so a commit that
   carries anything else restores the base's `notices/manifest` in that same commit. That
   manifest names the previous release, so CI stays red until an approval of the new head binds
   it; the pull request gets the `ffmpeg-notices-review` label and a comment listing what
   changed. A commit carries only what the run produced: a pin the tool cannot follow degrades
   to the lock, the captures this run adopted and that withdrawal, leaving
   `notices/sources.json` and `SOURCE.txt` as the head holds them and deleting none, and its
   comment asks for a regeneration pushed to the branch first, because an approval binds only
   inputs that describe the locked release; a capture that failed leaves that architecture's
   `buildconf` alone.
   Only a run started by Renovate's own push replaces anything but the lock: every other push
   may carry a correction, so its files stay as pushed and the run warns where they differ.
4. `.github/workflows/approve-ffmpeg-notices.yml` binds the manifest when someone whose
   permission on this repository is `admin` or `maintain` submits an **approving review** of
   the labelled pull request's current head. Its first step asks the API for that role, because
   a review's `author_association` reports organization membership, which carries no permission
   here. `review-release --approved` writes only the manifest, and only when the
   approved inputs already describe the locked release. A later synchronization that finds
   nothing to commit keeps that approval and does not ask again; one that produces anything
   the approval did not cover withdraws the binding along with it.

Renovate rebuilds its branch when it rebases, which discards the bot's commits; the workflow
then regenerates them, and a changed inventory needs a fresh approval.

`.github/workflows/sync-ffmpeg-lock.yml` uses `pull_request_target` only for same-repository
Renovate PRs. It executes resolver, capture, regeneration and review code from the trusted PR
base, reads the proposed release from a Git object as data, and generates everything in the
trusted checkout. It never executes proposed code, and the job that mints credentials never
runs the downloaded binaries. The regeneration and the review quote upstream file names, which
the runner would read for workflow commands, so each step pauses command processing behind a
random token while its output prints and escapes the text it repeats as the annotation. The
report reaches people as one code block, fenced by more backticks than any run inside it and
cut off at 16 KiB, so no upstream line can close it and a flooded report still posts; the job
summary and the review request carry that same block. Only after detecting a change and
verifying the head SHA does it mint a short-lived
GitHub App token. Labels and comments use the workflow token, which cannot
trigger further workflows. The approval workflow runs on `pull_request_review`, which has no
base-only variant, so it likewise checks out and executes only the base revision's scripts.

GitHub's `createCommitOnBranch` API creates a signed commit limited to the lock, `SOURCE.txt`
and `notices/`; `notices/manifest` is bound only by a confirmed or approved review, and an
unreviewed commit may only withdraw it.
Its `expectedHeadOid` check rejects a moved branch atomically. The App token triggers normal
PR checks after the commit; the default Actions token would suppress those runs.

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
