# Streamarr Transcode Worker

Runs media probes and transcodes for Streamarr. The server owns the worker protocol. This repository consumes its published Buf Java SDK.

## Build

Use Java 25 and run `./mvnw verify`. The build runs independent unit and integration tests, Checkstyle, Spotless, and JaCoCo. Tests use scripted control planes and controlled media processes. They do not start the Streamarr server or a database.

FFmpeg tooling uses the Node version in `buildpacks/ffmpeg/.nvmrc`. The FFmpeg buildpack, version lock, redistribution notices, and Node tests are retained together.

[Release automation](docs/releases.md) manages Maven versions and GitHub releases. [Image validation](docs/image-validation.md) covers container tests and publication to Docker Hub.

## Run locally

Start Streamarr with its worker session listener bound to loopback. Mount the same media directory in both processes. Then configure the worker and run the executable jar:

```sh
export TRANSCODE_WORKER_ID=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
export TRANSCODE_WORKER_SOURCE_NAMESPACE_ID=cccccccc-cccc-cccc-cccc-cccccccccccc
export TRANSCODE_WORKER_SOURCE_ROOT=/media
worker_version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
java -jar "target/transcode-worker-${worker_version}.jar"
```

The source namespace must match the server's configuration. FFmpeg and ffprobe default to executables on `PATH`. `TRANSCODE_WORKER_FFMPEG_PATH` and `TRANSCODE_WORKER_FFPROBE_PATH` accept explicit executable paths. Output defaults to a separate temporary directory and can be set with `TRANSCODE_WORKER_SEGMENT_BASE_PATH`.

Actuator listens on port 9091, configurable with Spring Boot's standard `SERVER_PORT`. `/actuator/health/liveness` follows process liveness. `/actuator/health/readiness` requires an accepted worker session. A full worker remains ready. Session loss ends active work and the process exits for its supervisor to restart it.

The worker connects to `127.0.0.1:9090` by default using plaintext gRPC. `TRANSCODE_WORKER_CONTROL_PLANE_HOST` and `TRANSCODE_WORKER_CONTROL_PLANE_PORT` set an explicit endpoint. Colocated Compose shares the server's network namespace and does not publish the worker port.

Distributed Kubernetes deployments must protect the configured endpoint with mesh-enforced mTLS and worker ServiceAccount authorization. The application does not load certificates or select a transport mode. Worker UUIDs identify instances within that shared trust boundary. An authorized worker can claim another worker's UUID and replace its session. Use [the server's Kubernetes deployment](https://github.com/streamarr/streamarr-server/blob/main/deploy/kubernetes/distributed-transcoding.yaml) with the corresponding mesh transport change.

## Real media tests

Normal verification excludes smoke tests. With FFmpeg and ffprobe on `PATH`, run:

```sh
./mvnw test -Dtest=WorkerMediaSmokeTest -Dsurefire.excludedGroups=
```

These tests exercise real probing, corrupt-media classification, remuxing, transcoding, and segment uploads through the worker. CI runs them explicitly with the locked FFmpeg buildpack runtime.

## Extraction

The source is server revision [`3fe7b460`](https://github.com/streamarr/streamarr-server/commit/3fe7b46021cdee200bc8dfbde74bf254b21235f6). The extraction retains 137 relevant commits through `git-filter-repo`. Historical paths are listed in [extraction-paths.txt](docs/extraction-paths.txt). [extraction-commit-map.txt](docs/extraction-commit-map.txt) maps original server revisions to their signed worker revisions. Author identities, author dates, file trees, and parent order are preserved. The replay removes historical co-author trailers and signs the rewritten commits.

**Merge the extraction PR with a merge commit to retain this history.** Squashing it would discard the imported ancestry.

The source-to-worker package mappings are recorded in [worker-code-moves.json](docs/worker-code-moves.json) and [worker-code-copies.json](docs/worker-code-copies.json). Server-owned value copies are not imported into the final worker tree. Worker imports target the published SDK namespace, `build.buf.gen.streamarr.transcode.v1`.

Server-coupled integration tests and the 14 server playback/recovery smoke cases remain in the server until its published-image cutover. [extraction.json](docs/extraction.json) records that test ownership and the exact contract pin. Reconcile subsequent worker changes against the source revision before server cutover.

## Architecture

[ADR 0033](https://github.com/streamarr/streamarr-adr/blob/main/adr/0033-transcode-worker-is-a-separate-service.adoc) is the accepted extraction baseline. [ADR PR 14](https://github.com/streamarr/streamarr-adr/pull/14) proposes Spring Boot, Actuator health endpoints, and mesh protection for distributed worker connections. This transport follow-up depends on the independently reviewable extraction baseline. Server sources remain in place until a verified worker image is available.
