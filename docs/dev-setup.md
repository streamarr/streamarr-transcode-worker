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
| `TRANSCODE_WORKER_SLOTS` | `1` | Number of playback slots advertised to the server: the job attempts that encode the video that the worker runs at once. Probes never take a playback slot, and neither does a job attempt that copies the video, with or without an audio encode. The worker refuses an encode while every slot holds an active encode. It holds at most 256 MiB of FFmpeg's output per slot, two segments at the server's 128 MiB cap, and runs as many copies as its maximum heap holds at 256 MiB each after the slots' memory and a 512 MiB reserve; it refuses a copy beyond them with `JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED`. It also holds a 64 KiB read buffer for each FFmpeg process. FFmpeg's own memory, the kernel's buffer for each FFmpeg process's output pipe and gRPC's flow-control window for each upload come on top |
| `TRANSCODE_WORKER_PROBE_CAPACITY` | unset: adaptive | Fixes the background probe capacity at this positive integer and turns its adaptation off. Unset, the worker adapts the capacity from 1 to the cores available to the JVM, which follow container CPU limits and `-XX:ActiveProcessorCount`. The playback probe capacity stays 2, and keyframe packet scans keep their own capacity. A probe that arrives while its capacity has no free unit fails with `PROBE_FAILURE_NO_FREE_CAPACITY` before ffprobe starts, and the server keeps it queued |
| `TRANSCODE_WORKER_FFMPEG_PATH` | `ffmpeg` | FFmpeg executable |
| `TRANSCODE_WORKER_FFPROBE_PATH` | `ffprobe` | ffprobe executable |
| `TRANSCODE_WORKER_FRAGMENTATION_TARGET` | `250ms` | Fragmentation target: the media duration after which FFmpeg starts a new fragment at the next packet: a whole number with a unit from `ns` to `h`, such as `1s` or `500ms`. Keep it well below the segment period. A shorter target lets the worker close a segment sooner after its boundary |
| `TRANSCODE_WORKER_ENCODER_STALL_TIMEOUT` | `30s` | How long FFmpeg may write nothing to its output while the worker reads it. The worker then fails the job attempt and asks FFmpeg to terminate, destroying it once the grace period a stop allows has passed, because a hung FFmpeg can ignore the request. Time spent waiting for the server does not count. Keep it above the time FFmpeg needs to write its first fragment. A positive duration in the same format |
| `TRANSCODE_WORKER_UPLOAD_READINESS_TIMEOUT` | `30s` | How long a segment upload waits for the server to accept its next message before the job attempt fails. A positive duration in the same format |
| `TRANSCODE_WORKER_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT` | `60s` | How long a segment upload may take, counted from its first message, until the server acknowledges the segment, before the job attempt fails. A segment that waits for one of the upload calls that the server accepts at once waits at most this long too. A positive duration in the same format |
| `TRANSCODE_WORKER_AUDIO_STREAM_READ_TIMEOUT` | `30s` | How long ffprobe may take to read the audio stream that a job attempt encodes in a named layout, before the attempt starts FFmpeg. The worker then fails the attempt at startup and asks ffprobe to die, without waiting for a process that a hung mount keeps alive. A stop for the attempt ends the read at once. A positive duration in the same format |
| `TRANSCODE_WORKER_SESSION_ACCEPTANCE_TIMEOUT` | `5m` | How long the worker waits at startup for the server to accept its session. The wait covers a server that is still starting, so the worker can start first. The worker then exits and names the server it waited for. Keep it above the time the server needs to start, including its database migrations. A positive duration in the same format |
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

`/actuator/metrics/streamarr.worker.copy_fullness` reports the share of the copies that the worker's heap holds that run now, from 0 to 1, and 1 when the heap holds none. An autoscaler can read it, for example KEDA's Metrics API scaler with `valueLocation: measurements.0.value`, to add workers before copies are refused.

When the server accepts the worker session, it states how many upload calls it accepts from the worker at once. A segment beyond them waits, in the order the segments became ready, for a call to end; the job attempt fails when none ends within the upload acknowledgement timeout, and a stop or the session's end cancels the wait. A server that states no limit lets every segment open its call at once. The server frees the place of a call that the worker cancelled only once it has stored that call's segment, so it can refuse a call that the worker counted as free. It marks that refusal with `SegmentUploadLimitReached`, and the segment then waits again in its place in that order, ahead of the segments that became ready after it. It opens its call again as soon as another of the worker's calls ends, each end reopening one refused segment, and otherwise after 10 ms, a delay that doubles with each refusal up to 1 s. The job attempt fails when the server still refuses it one upload acknowledgement timeout after its first refusal. Any refusal without the marker comes from the server's fleet-wide limits and fails the job attempt. `/actuator/metrics/streamarr.worker.queued_segment_uploads` reports the segments that wait, including a refused one.

At startup the worker waits for the server to accept its session, so the two can start in either order. Losing the server session stops active work and exits the worker process. A deployment supervisor should restart it. During shutdown, the worker asks each FFmpeg process to quit and waits for it to exit.

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
