#!/usr/bin/env bash
set -euo pipefail

image=${1:?Supply the local image name}
repository=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$repository"
revision=$(git rev-parse HEAD)
contract=$(./mvnw -q help:evaluate -Dexpression=buf.sdk.version -DforceStdout)
buildpacks/ffmpeg/bin/prepare

pack build "$image" \
  --builder paketobuildpacks/ubuntu-noble-builder:0.0.190 \
  --buildpack paketo-buildpacks/java \
  --buildpack ./buildpacks/ffmpeg \
  --env BP_JVM_VERSION=25 \
  --env "BP_OCI_SOURCE=https://github.com/streamarr/streamarr-transcode-worker" \
  --env "BP_OCI_REVISION=$revision" \
  --env "BP_OCI_VERSION=$revision" \
  --env "BP_IMAGE_LABELS=org.streamarr.contract.version=$contract" \
  --env 'BPE_APPEND_JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED' \
  --env 'BPE_DELIM_JAVA_TOOL_OPTIONS= ' \
  --path .

.github/actions/pack-build/verify-ffmpeg-image.sh "$image" "$revision" \
  https://github.com/streamarr/streamarr-transcode-worker "$revision"
