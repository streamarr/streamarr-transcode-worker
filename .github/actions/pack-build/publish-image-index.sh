#!/usr/bin/env bash
set -euo pipefail

if [[ ${GITHUB_EVENT_NAME:-} != push || ${GITHUB_REF:-} != refs/heads/main \
  || ${GITHUB_REPOSITORY:-} != streamarr/streamarr-transcode-worker ]]; then
  echo 'Image publication requires a reviewed main push.' >&2
  exit 1
fi
if [[ ! ${GITHUB_SHA:-} =~ ^[a-f0-9]{40}$ ]]; then
  echo 'Image publication requires a full source revision.' >&2
  exit 1
fi

image="streamarr/streamarr-transcode-worker:sha-$GITHUB_SHA"
references=()
for architecture in amd64 arm64; do
  if ! reference=$(jq -er --arg source "$GITHUB_SHA" --arg architecture "$architecture" \
    'select(.sourceRevision == $source and .architecture == $architecture) | .image |
    select(test("^streamarr/streamarr-transcode-worker@sha256:[a-f0-9]{64}$"))' "$architecture-image.json"); then
    echo "Invalid native image receipt for $architecture." >&2
    exit 1
  fi
  references+=("$reference")
done
amd64=${references[0]}
arm64=${references[1]}
docker buildx imagetools create --metadata-file image-metadata.json --tag "$image" "$amd64" "$arm64"
digest=$(jq -er '."containerimage.descriptor".digest' image-metadata.json)
if [[ ! $digest =~ ^sha256:[a-f0-9]{64}$ ]]; then
  echo 'The registry did not return a valid manifest digest.' >&2
  exit 1
fi
docker buildx imagetools inspect --raw "streamarr/streamarr-transcode-worker@$digest" > image-index.json
jq -e '[.manifests[].platform | .os + "/" + .architecture] | sort == ["linux/amd64", "linux/arm64"]' image-index.json
contract=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").find("{*}properties/{*}buf.sdk.version").text)')
jq -n --arg source "$GITHUB_SHA" --arg contract "$contract" \
  --arg image "streamarr/streamarr-transcode-worker@$digest" --arg amd64 "$amd64" --arg arm64 "$arm64" \
  '{sourceRevision: $source, contractRevision: $contract, image: $image, nativeImages: {amd64: $amd64, arm64: $arm64}}' > worker-image.json
cat worker-image.json >> "$GITHUB_STEP_SUMMARY"
