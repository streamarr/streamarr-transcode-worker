# Worker image validation

The image uses the existing Paketo Java build with Java 25 and the locked FFmpeg buildpack.
The generated Spring Boot process is the default command. No server process or separate launch
wrapper is included. The image runs as a non-root user.

Build and validate a local image with Java 25, the Node version in
`buildpacks/ffmpeg/.nvmrc`, Docker, and `pack`:

```sh
./mvnw verify
.github/actions/pack-build/build-worker-image.sh streamarr-worker:local
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=WorkerImageIT -Dfailsafe.excludedGroups=SmokeTest \
  -Dworker.image=streamarr-worker:local -Djacoco.skip=true
```

The build validates FFmpeg notices and checksums, preserves the license and source materials,
and checks, inside the resulting image, that FFmpeg writes H.264/AAC fragmented MP4 to its
standard output and encodes AV1. OCI labels record the
source commit. The OCI version label defaults to the Maven project version. An optional second argument to
`build-worker-image.sh` supplies the release version. The `org.streamarr.contract.version` label records the pinned Buf SDK version.
Validation checks these labels against the expected values. These commands do not publish the image.

`WorkerImageIT` starts the actual Boot image beside a scripted control plane in the same network
namespace. It leaves the worker endpoint unset to exercise `127.0.0.1:9090`. It verifies:

- Readiness is down before the control plane accepts registration and up afterward. Liveness is up.
- The image probes a real media file mounted read-only.
- Remuxing and transcoding upload segments that decode successfully and have the expected dimensions.
- A mounted executable can inject a typed transcode failure without making the worker unready.
- Container termination sends the media process its graceful quit command and closes the session.
- Stopping a job whose segment upload awaits acknowledgement lets FFmpeg flush and exit on its own,
  without a forced kill, before the worker reports the stop.

The existing JVM smoke tests remain separate. The image tests carry the `ImageTest` tag and are
excluded from ordinary Maven runs. CI runs them explicitly against native amd64 and arm64 images.
The aggregate `build` check includes both image jobs.

After human review and merge, a push to `main` publishes each tested native image to Docker Hub
`streamarr/streamarr-transcode-worker`. The publishing steps reuse the tested image without rebuilding.
Pull requests and manual CI runs do not authenticate or publish. The workflow uses the existing
`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` organization secrets and a read-only GitHub token.

Each native job records its registry digest. After both jobs pass, the shared publication workflow
combines those exact digests into a multi-architecture index. The `published-image-<source SHA>`
artifact contains `image.json`, the registry index and metadata, and both native receipts.
`image.json` records the immutable image reference, source commit, Maven version, and native digests.
The Buf SDK version remains in the image's `org.streamarr.contract.version` label. Tags have the form
`sha-<full source SHA>` and `sha-<full source SHA>-<architecture>`. When the Maven version is
`X.Y.Z-SNAPSHOT`, the current `main` build also publishes that exact snapshot tag from the same
verified native digests. Publication is serialized, and stale reruns leave the snapshot tag unchanged.
Stable version tags remain owned by Release Publisher. Consumers pin a tag and digest, such as
`streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:...`, because tags can be replaced.

Local publication tests use a registry fake to verify that native publication promotes the tested
image. Tests in `streamarr/streamarr-workflows` cover receipt validation, multi-architecture publication,
and tag promotion. These tests never authenticate or contact a registry.

Published GitHub releases also trigger Release Publisher, which rebuilds the tagged revision on
both native runners. It runs the same media checks and `WorkerImageIT` command before Docker Hub
authentication, then publishes native commit tags and a multi-architecture version tag.
Only GitHub's latest release updates `latest`. See [releases](releases.md) for validation and retry behavior.

Hardware encoder support and redistribution materials remain in the moved buildpack. Ordinary
CI runners prove CPU operation. They do not claim to exercise GPU devices or drivers.

Before server cutover, worker issue #4 also requires an immutable published digest and the real
Istio checks using that image. The server's scripted mesh test alone does not prove the final
worker image. Server issue #352 owns consumer migration and source removal after that validation.
