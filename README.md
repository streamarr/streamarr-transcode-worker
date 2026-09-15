# Streamarr Transcode Worker

Runs media probes and transcodes for Streamarr. The server owns the worker protocol. This repository consumes its published Buf Java SDK.

## Build

Use Java 25 and run `./mvnw verify`. The build runs independent unit and integration tests, Checkstyle, Spotless, and JaCoCo. Tests use scripted control planes and controlled media processes. They do not start the Streamarr server or a database.

FFmpeg tooling uses the Node version in `buildpacks/ffmpeg/.nvmrc`. The FFmpeg buildpack, version lock, redistribution notices, and Node tests are retained together.

## Extraction

The source is server revision [`3fe7b460`](https://github.com/streamarr/streamarr-server/commit/3fe7b46021cdee200bc8dfbde74bf254b21235f6). The extraction retains 137 relevant commits through `git-filter-repo`. Historical paths are listed in [extraction-paths.txt](docs/extraction-paths.txt). [extraction-commit-map.txt](docs/extraction-commit-map.txt) maps original server revisions to their signed worker revisions. Author identities, author dates, file trees, and parent order are preserved. The replay removes historical co-author trailers and signs the rewritten commits.

**Merge the extraction PR with a merge commit to retain this history.** Squashing it would discard the imported ancestry.

The source-to-worker package mappings are recorded in [worker-code-moves.json](docs/worker-code-moves.json) and [worker-code-copies.json](docs/worker-code-copies.json). Server-owned value copies are not imported into the final worker tree. Worker imports target the published SDK namespace, `build.buf.gen.streamarr.transcode.v1`.

Server-coupled integration tests and the 14 server playback/recovery smoke cases remain in the server until its published-image cutover. [extraction.json](docs/extraction.json) records that test ownership and the exact contract pin. Reconcile subsequent worker changes against the source revision before server cutover.

## Architecture

[ADR 0033](https://github.com/streamarr/streamarr-adr/blob/main/adr/0033-transcode-worker-is-a-separate-service.adoc) is the accepted extraction baseline. [ADR PR 14](https://github.com/streamarr/streamarr-adr/pull/14) proposes Spring Boot, Actuator health endpoints, and mesh protection for distributed worker connections. The extraction is delivered before the mesh transport follow-up. Server sources remain in place until a verified worker image is available.
