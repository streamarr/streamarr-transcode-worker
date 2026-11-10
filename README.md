<p align="center">

<br />

<img src="https://github.com/streamarr/streamarr-ux/blob/main/branding/assets/streamarr-mark-and-text-concept-d.svg" width="425" alt="Streamarr logo">

<br />
<br />
<br />

<a href="LICENSE">
<img alt="GPL 3.0 License" src="https://img.shields.io/badge/license-GPL--3.0-orange.svg"/>
</a>

<a href="https://github.com/streamarr/streamarr-server/commit/0d33b22cf3dbb7efef179d48c1d9163698a5062e">
<img alt="Streamarr since 2022" src="https://img.shields.io/badge/since-2022-blue"/>
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
| Playback slots | Job attempts that encode the video | `TRANSCODE_WORKER_SLOTS`, 1 by default |
| Playback probe capacity | Probes that a playback request waits for | 2 |
| Background probe capacity | Other probes, such as a library scan's | Adapts from 1 to the cores available to the JVM, starting at 4. `TRANSCODE_WORKER_PROBE_CAPACITY` fixes it instead |
| Keyframe packet scan capacity | Keyframe packet scans and bitrate scan chunks | 0 while any job attempt runs, and 1 once no job attempt has run for 30 s |

A bitrate scan chunk lists the packets of a run of keyframe intervals with ffprobe and reports each video and audio stream's bytes and packets in each interval ([ADR 0054](https://github.com/streamarr/streamarr-adr/blob/main/adr/0054-workers-measure-each-stream-bitrate-and-a-copy-advertises-its-measured-peak.adoc)). The worker advertises the stream measurement version whose chunks it runs, and the server sends chunks only to a worker that lists the server's version.

Probes never take a playback slot, and job attempts never take a probe capacity. A job attempt that copies the video, with or without an audio encode, takes no playback slot either. The worker runs as many copies as its heap holds at up to 256 MiB of FFmpeg's output each, after the encodes' memory and a 512 MiB reserve, and refuses a copy beyond them with `JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED`, so the server can start it on another worker. A worker whose heap holds no copy refuses every copy that way. An encode beyond the slots is refused as a startup failure. The JVM sizes its heap from the container's memory limit, so a worker with more memory runs more copies. A playback probe can also use a free unit of the background probe capacity, so it never waits behind a scan.

The background probe capacity grows by 1 when a probe ends while at least half of it is in use. A brake halves it when a job attempt starts, and when a job attempt produces below 2.10× real time over 15 s. A brake also halves it when the server cancels a background or playback probe at the server's probe timeout, so a busy disk lowers it while nothing plays; only a probe that started after the latest brake applies that brake, and a probe that ends at the timeout never grows the capacity. A brake for a slow job attempt or a timed-out probe waits until 15 s have passed since the previous brake. After a brake, the capacity does not grow for 30 s. A probe that started before a brake that a timed-out probe applied, and then times out too, shows congestion, because the two timed out together. From the capacity at that moment upward, the capacity then grows by 1 only once every probe that ran at its previous growth has ended, and never while a background or playback probe has run for a quarter of the probe timeout, which the worker reads from the age of that timed-out probe. The congestion clears when the capacity grows back to its maximum. Each media segment's production time leaves out the time the worker waited for an earlier upload, so the brake measures the encoder.

`TRANSCODE_WORKER_SLOTS` decides how many encodes the worker runs. `TRANSCODE_WORKER_PROBE_CAPACITY` only fixes the background probe capacity and turns the adaptation off. Leave it unset unless the host's disks cannot take as many probes as its cores.

Background probes, keyframe packet scans and bitrate scan chunks run at nice 19, and playback probes at the worker's own priority. While probes run, the worker logs a summary each minute, with each capacity and the brakes since the previous summary. [Developer Setup](docs/dev-setup.md#configuration) lists every setting.

## Encoders

At startup the worker gives each codec family one encoder backend and reports it when it registers. Every job of that codec family runs that backend. The worker tries each hardware encoder that FFmpeg lists with a one-frame test encode. A hardware encoder becomes usable only if it passes that encode and the worker knows how to force the job's codec profile, tier and level on it. The software encoder (libx264, libx265 or libsvtav1) becomes usable if it passes the same encode.

When a codec family has several usable encoders, the worker always picks them in this order:

1. A hardware encoder that [ADR 0037](https://github.com/streamarr/streamarr-adr/blob/main/adr/0037-worker-delivers-fmp4-segments-from-ffmpeg-stdout.adoc) lists as keyframe-verified
2. Any other hardware encoder
3. The software encoder

Among equally verified hardware encoders, the API decides: NVENC, QSV, AMF, VideoToolbox, then VAAPI. A host therefore always resolves a codec family to the same backend, and a variant's replacement attempts keep the backend of its first job attempt.

The worker also reports the outputs beyond 8-bit SDR that each encoder writes: 10-bit video, PQ, HLG, and Dolby Vision profile 5 or 10.0 reshaped into PQ. libx265, SVT-AV1 and hevc_nvenc run a one-frame test encode of each output at startup, and the worker reports an output only when that encode writes it. A host whose GPU encodes HEVC Main 10 therefore encodes HDR video on hevc_nvenc. The filters, with the Dolby Vision reshape, run on the CPU, and hevc_nvenc takes their frames. h264_nvenc encodes 8-bit SDR only, and the worker reports no such output for any other hardware encoder.

## Architecture

The worker runs as a separate Java service. It connects to the server over gRPC, reads files from a shared media library, reads FFmpeg's fragmented MP4 output from a pipe, and uploads each HLS media segment back to the server. Workers can run alongside the server or on other machines.

The server owns the worker protocol; this repository consumes its published Java SDK. See the [Architecture Decision Records](https://github.com/streamarr/streamarr-adr) for the design rationale.

## Contributing

See [Developer Setup](docs/dev-setup.md#contributing) for testing, formatting, and commit conventions. [Image Validation](docs/image-validation.md) covers testing the container locally, and [Releases](docs/releases.md) explains how versions and images are published.

## Attribution

Worker images include FFmpeg and ffprobe built by [Streamarr's fork](https://github.com/streamarr/streamarr-ffmpeg) of the pinned FFmpeg build. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for licenses, attribution, and corresponding source information.
