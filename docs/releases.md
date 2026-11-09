# Releases

Release Please manages the Maven version, generated changelog, release tag, and draft GitHub releases using the `streamarr-release` GitHub App. Stable release PRs require human review and merge; a maintainer then curates and publishes the draft release separately. Only generated snapshot-version PRs are queued for automatic squash merge after required checks pass. Release maintenance, tag validation, and multi-architecture publication use the pinned workflows in `streamarr/streamarr-workflows`.

The release automation is recorded in [ADR 0034](https://github.com/streamarr/streamarr-adr/blob/main/adr/0034-release-please-owns-maven-release-versions.adoc); [ADR 0058](https://github.com/streamarr/streamarr-adr/blob/main/adr/0058-releases-stay-draft-until-their-notes-are-curated.adoc) adds the draft-publication gate for Streamarr repositories using Release Please.

The first release is `0.1.0`. Its changelog starts after the worker baseline at `a1ef061ef706bb5b5c59cb95417145d66636b8a7`, excluding imported server history. The manifest starts empty because the worker has no previous release. Keep release PR titles, bodies, and labels intact so Release Please can recognize merged releases.

On each push to `main`, the workflow processes any merged release before preparing the next version PR. A merged release that still has `autorelease: pending` stops the workflow with recovery instructions. After creating a stable draft release, the Maven strategy prepares the next `-SNAPSHOT` version, even while its notes are being curated.

Use the repository's `behavioral:` and `structural:` commit subjects. Both appear in release notes alongside conventional feature, fix, performance, dependency, build, and revert entries. Release notes omit commit-author attribution. Generated version PRs use `chore(main): release ...` subjects and an empty squash commit body.

## Cut and publish a release

Review the maintained release PR's version, included changes, and required checks. Squash-merge it with its generated `chore(main): release X` title and an empty commit body. Release Please tags the merged revision and creates a draft GitHub release with generated notes.

The Maven package enables Release Please's native `draft` and `force-tag-creation` settings. The tag is visible immediately, giving subsequent version calculations a fixed baseline while the GitHub release and stable image tags remain unpublished. See [Release Please's configuration documentation](https://github.com/googleapis/release-please/blob/v17.6.0/docs/manifest-releaser.md).

Wait for the **Release Please** workflow to finish successfully, then open the draft on GitHub's **Releases** page and curate its description. Normal Release Please runs have already processed the merged PR and leave the draft's edited notes intact. Edit the draft GitHub release; the release PR and `CHANGELOG.md` remain generated records, and curated notes are not copied back into them.

Keep the draft's generated tag and stable-release classification. When its notes are ready, click **Publish release**. The stable release's `released` event starts **Release Publisher**. Creating the tag or draft and editing its notes do not start stable image publication. Later commits and snapshot preparation do not move the release tag or change the draft's summary.

If release maintenance fails, rerun **Release Please** with `workflow_dispatch` before editing the draft. A pending merged release must be processed before another release PR is prepared; restore its original release title and structured body if the workflow reports that they cannot be recognized.

## Repository setup

- Include this repository in the `streamarr-release` App installation. The App needs Contents, Pull requests, and Issues write permissions.
- Make `ORG_STREAMARR_RELEASE_CLIENT_ID` and `ORG_STREAMARR_RELEASE_PRIVATE_KEY` available as organization Actions secrets with access to this repository. The latter must contain the App's complete private-key PEM.
- Enable repository auto-merge and require the aggregate `build` check on `main`. Keep the release App outside ruleset bypass lists. The aggregate check includes verification, SonarCloud, smoke tests, and both native image jobs.

The App's Client ID is on its settings page. Generate a private key there and save the complete downloaded PEM as the private-key secret. Do not use a client secret in its place.

## Images

CI publishes the exact tested amd64 and arm64 images after merges to `main`, with commit tags and recorded immutable digests. Snapshot versions also publish the Maven `X.Y.Z-SNAPSHOT` tag for Renovate consumers. Stable release image tags wait for the draft GitHub release to be published.

The Release Publisher follows the server's release workflow. A published GitHub release triggers native amd64 and arm64 rebuilds from its validated tag. The tag must be stable `vX.Y.Z`, match the Maven version, and identify a commit already on `main`. Each rebuilt image passes media validation and `WorkerImageIT` before Docker Hub authentication and publication. Native tags use `sha-<full source SHA>-<architecture>`. Their digest receipts feed the shared publisher, which publishes the multi-architecture `X.Y.Z` tag. It updates `latest` only when GitHub identifies that release as latest.

To retry publication, manually run Release Publisher with the existing published release tag. A tag belonging to an unpublished draft is rejected. Retries rebuild the release and can replace its image tags. Consumers needing an immutable reference should pin the image digest. See [image validation](image-validation.md) for local validation and CI image records.
