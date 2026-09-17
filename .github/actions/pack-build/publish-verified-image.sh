#!/usr/bin/env bash
set -euo pipefail

image=${1:?Supply the image that passed container tests}
architecture=${2:?Supply the native image architecture}
if [[ ${GITHUB_EVENT_NAME:-} != push || ${GITHUB_REF:-} != refs/heads/main \
  || ${GITHUB_REPOSITORY:-} != streamarr/streamarr-transcode-worker ]]; then
  echo 'Image publication requires a reviewed main push.' >&2
  exit 1
fi
if [[ ! ${GITHUB_SHA:-} =~ ^[a-f0-9]{40}$ ]]; then
  echo 'Image publication requires a full source revision.' >&2
  exit 1
fi
case "$architecture" in
  amd64|arm64) ;;
  *) echo 'Unsupported image architecture.' >&2; exit 1 ;;
esac

revision=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")
if [[ $revision != "$GITHUB_SHA" ]]; then
  echo 'The tested image source revision differs from the main push.' >&2
  exit 1
fi

tag="streamarr/streamarr-transcode-worker:sha-$revision-$architecture"
docker tag "$image" "$tag"
docker push "$tag"
reference=$(docker inspect --format '{{ index .RepoDigests 0 }}' "$tag")
jq -n --arg source "$revision" --arg architecture "$architecture" --arg image "$reference" \
  '{sourceRevision: $source, architecture: $architecture, image: $image}' > "$architecture-image.json"
printf '%s\n' "$reference"
