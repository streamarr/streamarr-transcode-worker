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
and checks H.264/AAC HLS and AV1 encoding inside the resulting image. OCI labels record the
source commit. The `org.streamarr.contract.version` label records the pinned Buf SDK version.
Validation checks these labels against the expected values. These commands do not publish the image.

`WorkerImageIT` starts the actual Boot image beside a scripted control plane in the same network
namespace. It leaves the worker endpoint unset to exercise `127.0.0.1:9090`. It verifies:

- Readiness is down before the control plane accepts registration and up afterward. Liveness is up.
- The image probes a real media file mounted read-only.
- Remuxing and transcoding upload segments that decode successfully and have the expected dimensions.
- A mounted executable can inject a typed transcode failure without making the worker unready.
- Container termination sends the media process its graceful quit command and closes the session.

The existing JVM smoke tests remain separate. The image tests carry the `ImageTest` tag and are
excluded from ordinary Maven runs. CI runs them explicitly against native amd64 and arm64 images.
The aggregate `build` check includes both image jobs.

After human review and merge, a push to `main` publishes each tested native image to Docker Hub
`streamarr/streamarr-transcode-worker`. The publishing steps reuse the tested image without rebuilding.
Pull requests and manual runs do not authenticate or publish. The workflow uses the existing
`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` organization secrets and a read-only GitHub token.

Each native job records its registry digest. After both jobs pass, the final job combines those exact
digests into a multi-architecture index. It records the created index digest, source commit, Buf SDK
version, and native digests in the `worker-image-<source SHA>` artifact. Tags have the form
`sha-<full source SHA>` and `sha-<full source SHA>-<architecture>`. Consumers pin the recorded
`streamarr/streamarr-transcode-worker@sha256:...` reference because tags can be replaced.

Publication tests use a local registry fake that preserves image contents through tagging and push.
They verify rejected events and revisions, exact tested-image promotion, native receipt validation,
and digest records even if an image tag changes. They never authenticate or contact a registry.

Hardware encoder support and redistribution materials remain in the moved buildpack. Ordinary
CI runners prove CPU operation. They do not claim to exercise GPU devices or drivers.

Before server cutover, worker issue #4 also requires an immutable published digest and the real
Istio checks using that image. The server's scripted mesh test alone does not prove the final
worker image. Server issue #352 owns consumer migration and source removal after that validation.
