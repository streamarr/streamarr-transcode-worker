<p align="center">

<br />

<img src="https://github.com/streamarr/streamarr-ux/blob/main/branding/assets/streamarr-mark-and-text-concept-d.svg" width="425" alt="Streamarr logo">

<br />
<br />
<br />

<a href="LICENSE">
<img alt="GPL 3.0 License" src="https://img.shields.io/badge/license-GPL--3.0-orange.svg"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-transcode-worker">
<img alt="Code Coverage" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-transcode-worker&metric=coverage"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-transcode-worker">
<img alt="Security Rating" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-transcode-worker&metric=security_rating"/>
</a>

</p>

# Streamarr Transcode Worker

Streamarr Transcode Worker handles media processing for [Streamarr](https://github.com/streamarr/streamarr-server). It inspects video files, remuxes compatible streams, and transcodes video for HLS playback. The server assigns the work and serves the resulting video to your devices.

**Status: Active development.**

## Getting Started

To run Streamarr with a worker, see the server's [Distributed Transcoding](https://github.com/streamarr/streamarr-server/blob/main/docs/distributed-transcoding.adoc) guide. It covers Docker Compose, Kubernetes, shared media storage, and hardware transcoding.

To build and run the worker from source, see [Developer Setup](docs/dev-setup.md).

## Capacity

Each worker grants the server a separate capacity for each kind of work, as [ADR 0032](https://github.com/streamarr/streamarr-adr/blob/main/adr/0032-media-file-probe-results-are-persisted.adoc#probe-capacity) decides. The server never sends work beyond a granted capacity.

| Capacity | Work | Value |
| --- | --- | --- |
| Playback slots | Job attempts | `TRANSCODE_WORKER_SLOTS`, 1 by default |
| Playback probe capacity | Probes that a playback request waits for | 2 |
| Background probe capacity | Other probes, such as a library scan's | Adapts from 1 to the cores available to the JVM, starting at 4. `TRANSCODE_WORKER_PROBE_CAPACITY` fixes it instead |
| Keyframe packet scan capacity | Keyframe packet scans | 0 while any job attempt runs, and 1 once no job attempt has run for 30 s |

Probes never take a playback slot, and job attempts never take a probe capacity. A playback probe can also use a free unit of the background probe capacity, so it never waits behind a scan.

The background probe capacity grows by 1 when a probe ends while at least half of it is in use. A brake halves it when a job attempt starts, and when a job attempt produces below 2.10× real time over 15 s. A brake also halves it when the server cancels a background or playback probe at the server's probe timeout, so a busy disk lowers it while nothing plays; only a probe that started after the latest brake applies that brake, and a probe that ends at the timeout never grows the capacity. A brake for a slow job attempt or a timed-out probe waits until 15 s have passed since the previous brake. After a brake, the capacity does not grow for 30 s. Each media segment's production time leaves out the time the worker waited for an earlier upload, so the brake measures the encoder.

`TRANSCODE_WORKER_SLOTS` decides how much playback the worker runs. `TRANSCODE_WORKER_PROBE_CAPACITY` only fixes the background probe capacity and turns the adaptation off. Leave it unset unless the host's disks cannot take as many probes as its cores.

Background probes and keyframe packet scans run at nice 19, and playback probes at the worker's own priority. While probes run, the worker logs a summary each minute, with each capacity and the brakes since the previous summary. [Developer Setup](docs/dev-setup.md#configuration) lists every setting.

## Architecture

The worker runs as a separate Java service. It connects to the server over gRPC, reads files from a shared media library, reads FFmpeg's fragmented MP4 output from a pipe, and uploads each HLS media segment back to the server. Workers can run alongside the server or on other machines.

The server owns the worker protocol; this repository consumes its published Java SDK. See the [Architecture Decision Records](https://github.com/streamarr/streamarr-adr) for the design rationale.

## Contributing

See [Developer Setup](docs/dev-setup.md#contributing) for testing, formatting, and commit conventions. [Image Validation](docs/image-validation.md) covers testing the container locally, and [Releases](docs/releases.md) explains how versions and images are published.

## Attribution

Worker images include Jellyfin builds of FFmpeg and ffprobe. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for licenses, attribution, and corresponding source information.
