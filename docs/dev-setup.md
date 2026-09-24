# Developer Setup

## Prerequisites

- **JDK 25**
- **Node.js 24**, using the version in [`buildpacks/ffmpeg/.nvmrc`](../buildpacks/ffmpeg/.nvmrc)
- **Bash** for the build tooling
- **FFmpeg and ffprobe** to run the worker or test with real media

The normal build uses scripted media processes and control planes. It does not need FFmpeg, Docker, a running Streamarr server, or a database.

## Build

From the repository root, select the Node version and build with the Maven wrapper. If you use nvm:

```sh
nvm install "$(cat buildpacks/ffmpeg/.nvmrc)"
nvm use "$(cat buildpacks/ffmpeg/.nvmrc)"
./mvnw verify
```

`verify` runs unit and integration tests, Checkstyle, Spotless, and coverage reporting. The FFmpeg tooling tests also require Node 24 when running `./mvnw test`. Node is only a development dependency; the worker runs on Java.

## Run Locally

Start the server using its [Developer Setup](https://github.com/streamarr/streamarr-server/blob/main/docs/dev-setup.adoc) guide. Keep its worker listener bound to loopback (`127.0.0.1:9090`). In the server's environment, set `STREAMING_REMOTE_SOURCE_ROOT` to your media directory and `STREAMING_REMOTE_SOURCE_NAMESPACE_ID` to the UUID used below.

In a separate terminal, from this repository:

```sh
export TRANSCODE_WORKER_ID=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
export TRANSCODE_WORKER_SOURCE_NAMESPACE_ID=cccccccc-cccc-cccc-cccc-cccccccccccc
export TRANSCODE_WORKER_SOURCE_ROOT=/absolute/path/to/media

worker_version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
java -jar "target/transcode-worker-${worker_version}.jar"
```

Replace the media path with your own directory. The server and worker must use the same namespace UUID, with the same files beneath their respective source roots. The root paths may differ. Give each running worker a distinct worker UUID; reusing one replaces that worker's session.

FFmpeg and ffprobe must be on `PATH`, or configured with the executable paths below. The worker checks both before connecting to the server. Keep source media read-only: the worker writes no media files, because it reads FFmpeg's output from a pipe.

### Configuration

Settings are read from the environment at startup. The three variables in the example above are required; these settings are optional:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRANSCODE_WORKER_CONTROL_PLANE_HOST` | `127.0.0.1` | Server's worker listener host |
| `TRANSCODE_WORKER_CONTROL_PLANE_PORT` | `9090` | Server's worker listener port |
| `TRANSCODE_WORKER_SLOTS` | `1` | Number of execution slots advertised to the server |
| `TRANSCODE_WORKER_FFMPEG_PATH` | `ffmpeg` | FFmpeg executable |
| `TRANSCODE_WORKER_FFPROBE_PATH` | `ffprobe` | ffprobe executable |
| `TRANSCODE_WORKER_FRAGMENTATION_TARGET` | `1s` | Fragmentation target: the media duration after which FFmpeg starts a new fragment at the next packet: a whole number with a unit from `ns` to `h`, such as `1s` or `500ms`. Keep it well below the segment period |
| `TRANSCODE_WORKER_ENCODER_STALL_TIMEOUT` | `30s` | How long FFmpeg may write nothing to its output while the worker reads it. The worker then fails the job attempt and asks FFmpeg to terminate, destroying it once the grace period a stop allows has passed, because a hung FFmpeg can ignore the request. Time spent waiting for the server does not count. Keep it above the time FFmpeg needs to write its first fragment. A positive duration in the same format |
| `TRANSCODE_WORKER_UPLOAD_READINESS_TIMEOUT` | `30s` | How long a segment upload waits for the server to accept its next message before the job attempt fails. A positive duration in the same format |
| `TRANSCODE_WORKER_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT` | `60s` | How long a segment upload may take, counted from its first message, until the server acknowledges the segment, before the job attempt fails. A positive duration in the same format |
| `SERVER_PORT` | `9091` | HTTP port for health checks |

### Filename Locale

The worker must run under a UTF-8 locale. Java resolves source keys and passes media paths to FFprobe and FFmpeg using `sun.jnu.encoding`, which follows the process locale rather than `file.encoding`. Under an ASCII locale such as `POSIX`, media with non-ASCII names cannot be opened and probes fail as `PROBE_FAILURE_SOURCE_UNAVAILABLE`.

The published image defaults `LANG` to `C.UTF-8`. `LC_ALL` and `LC_CTYPE` override `LANG`; if you set either, use a UTF-8 value such as `C.UTF-8`. When the effective encoding is not UTF-8, the worker logs a warning at startup that names the variable responsible.

To check a running container's effective encoding:

```shell
docker exec <worker-container> /cnb/lifecycle/launcher java -XshowSettings:properties -version 2>&1 | grep sun.jnu.encoding
```

It should report `UTF-8`. To recover, correct the locale and restart the worker. The worker treats source keys as literal filename text, so do not rename media or rewrite the server's stored library paths; the server retries failed probes against the same files.

### Health and Shutdown

Spring Boot Actuator serves `/actuator/health/liveness` and `/actuator/health/readiness` on port `9091`. Readiness requires an accepted server session; a worker with all slots occupied remains ready.

Losing the server session stops active work and exits the worker process. A deployment supervisor should restart it. During shutdown, the worker asks each FFmpeg process to quit and waits for it to exit.

### Deploying Beyond Local Development

The worker uses plaintext gRPC. Colocated Docker Compose deployments share the server's network namespace and keep the worker listener on loopback. Distributed deployments require mesh-enforced mTLS and authorization for the worker ServiceAccount. Worker UUIDs identify instances within that shared trust boundary; they are not credentials.

Use the server's [Distributed Transcoding](https://github.com/streamarr/streamarr-server/blob/main/docs/distributed-transcoding.adoc) guide for the deployment examples and mesh configuration.

## Running Tests

```sh
# Unit tests
./mvnw test

# Unit and integration tests, formatting checks, and coverage
./mvnw verify
```

Smoke tests use real FFmpeg and ffprobe to exercise probing, corrupt-media handling, remuxing, transcoding, and segment uploads through the pipe, including runs of more than a thousand segments through libx264, libx265 and libsvtav1. With both executables on `PATH`, run:

```sh
./mvnw test -Dtest=WorkerMediaSmokeTest -Dsurefire.excludedGroups=
```

Smoke and image tests are excluded from normal builds. See [Image Validation](image-validation.md) to build and test the container with Docker and `pack`. CI runs media tests with the [locked FFmpeg runtime](../buildpacks/ffmpeg/README.md).

## Contributing

Start features and bug fixes with a failing test. Keep structural changes separate from behavior changes, and run `./mvnw verify` before submitting a pull request.

Google Java Format is enforced by Spotless. Format Java changes with:

```sh
./mvnw spotless:apply
```

Use signed commits (`git commit -S`) with a `structural:` or `behavioral:` subject prefix. The repository's [project guidelines](../AGENTS.md) cover architecture, testing, and code conventions in more detail.

The server owns the protocol definitions. Change the contract there, then update `buf.sdk.version` in this repository's `pom.xml` to the published SDK version. See [Releases](releases.md) for version and publication workflows.
