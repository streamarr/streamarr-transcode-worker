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

## Architecture

The worker runs as a separate Java service. It connects to the server over gRPC, reads files from a shared media library, reads FFmpeg's fragmented MP4 output from a pipe, and uploads each HLS media segment back to the server. Workers can run alongside the server or on other machines.

The server owns the worker protocol; this repository consumes its published Java SDK. See the [Architecture Decision Records](https://github.com/streamarr/streamarr-adr) for the design rationale.

## Contributing

See [Developer Setup](docs/dev-setup.md#contributing) for testing, formatting, and commit conventions. [Image Validation](docs/image-validation.md) covers testing the container locally, and [Releases](docs/releases.md) explains how versions and images are published.

## Attribution

Worker images include Jellyfin builds of FFmpeg and ffprobe. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for licenses, attribution, and corresponding source information.
