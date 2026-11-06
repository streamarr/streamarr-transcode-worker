#!/usr/bin/env bash
# Regenerates src/test/resources/sample-entries/dolby-vision-5-dvh1.mp4, the Dolby Vision profile 5
# source of the HDR encode smoke tests, with the images that record-fixtures.sh uses, and its first
# frame, src/main/resources/com/streamarr/transcode/engine/dolby-vision-5-test-frame.mp4, the input
# of the worker's own test encode of the Dolby Vision reshape.
#
# The source is 6 s of 64x64 testsrc2 video at 24 fps with a keyframe every second, from FFmpeg's
# lavfi test sources. Nothing comes from a media library. dovi_tool's profile 5 generator writes one
# RPU per frame, x265 places each RPU after its frame and signals profile 5, and GPAC muxes the
# stream under dvh1 with a dvcC record. The base layer holds the test pattern's own samples, so its
# colours mean nothing in IPT; the tests check the plumbing of the reshape, not its colours.
#
#   src/test/sample-entry-fixtures/record-dolby-vision-5-fixture.sh
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:0a27081ce19e529fd6758346f403691c26455d5916bffc7b79e6a81e5dc139ee}"
ALPINE="alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8"
GPAC="gpac/ubuntu@sha256:9a70c9ec3fe4383b49deb3416f59cb169db267d6c95c827874732bf2b8506ac3"
DOVI_TOOL_VERSION=2.3.4
DOVI_TOOL_SHA256_aarch64=b4f22a7db56954efe4ed8d02276d0f991799e84602f3584711be6c9df950cb15
DOVI_TOOL_SHA256_x86_64=1844258e13c26607b32224bf1fa82b595d3b35949f5467405fda560daad32b3f
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$(cd "$HERE/../resources/sample-entries" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/raw.sh" <<'CONTAINER'
set -euo pipefail
cd /work
ffmpeg -hide_banner -nostdin -loglevel error -y -f lavfi -i testsrc2=size=64x64:rate=24:duration=6 \
  -pix_fmt yuv420p10le -strict -1 profile-5.y4m
CONTAINER

cat > "$WORK/dolby-vision.sh" <<'CONTAINER'
set -euo pipefail
apk add --no-cache x265 curl > /dev/null
arch="$(uname -m)"
expected="DOVI_TOOL_SHA256_${arch}"
curl -sSfL -o /tmp/dovi_tool.tar.gz \
  "https://github.com/quietvoid/dovi_tool/releases/download/${DOVI_TOOL_VERSION}/dovi_tool-${DOVI_TOOL_VERSION}-${arch}-unknown-linux-musl.tar.gz"
echo "${!expected}  /tmp/dovi_tool.tar.gz" | sha256sum -c - > /dev/null
tar -xzf /tmp/dovi_tool.tar.gz -C /tmp
cd /work
echo '{"cm_version": "V40", "length": 144}' > rpu.json
/tmp/dovi_tool generate -p 5 -j rpu.json -o rpu.bin > /dev/null
x265 --input profile-5.y4m --output profile-5.hevc --profile main10 \
  --dolby-vision-profile 5 --dolby-vision-rpu rpu.bin --keyint 24 --min-keyint 24 --no-scenecut \
  --no-open-gop --vbv-bufsize 1000 --vbv-maxrate 1000 --pools none --frame-threads 1 \
  --log-level error --no-progress
CONTAINER

cat > "$WORK/mux.sh" <<'CONTAINER'
set -euo pipefail
cd /work
MP4Box -add profile-5.hevc:dvp=f5:fps=24 -new dolby-vision-5-dvh1.mp4 > /dev/null 2>&1
CONTAINER

cat > "$WORK/test-frame.sh" <<'CONTAINER'
set -euo pipefail
cd /work
ffmpeg -hide_banner -nostdin -loglevel error -y -i dolby-vision-5-dvh1.mp4 -frames:v 1 -c copy \
  -strict unofficial -tag:v dvh1 -f mp4 dolby-vision-5-test-frame.mp4
CONTAINER

# The buildpack launcher prints JVM lines on its own stdout, so every tool writes only to files.
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
  -v "$WORK":/work "$IMAGE" bash /work/raw.sh > "$WORK/launcher.log" 2>&1 \
  || { cat "$WORK/launcher.log"; exit 1; }
docker run --rm -v "$WORK":/work -e DOVI_TOOL_VERSION="$DOVI_TOOL_VERSION" \
  -e DOVI_TOOL_SHA256_aarch64="$DOVI_TOOL_SHA256_aarch64" \
  -e DOVI_TOOL_SHA256_x86_64="$DOVI_TOOL_SHA256_x86_64" \
  "$ALPINE" sh -c 'apk add --no-cache bash > /dev/null && bash /work/dolby-vision.sh'
docker run --rm --platform linux/amd64 -v "$WORK":/work "$GPAC" bash /work/mux.sh
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
  -v "$WORK":/work "$IMAGE" bash /work/test-frame.sh > "$WORK/launcher.log" 2>&1 \
  || { cat "$WORK/launcher.log"; exit 1; }

cp "$WORK/dolby-vision-5-dvh1.mp4" "$FIXTURES/dolby-vision-5-dvh1.mp4"
cp "$WORK/dolby-vision-5-test-frame.mp4" \
  "$HERE/../../main/resources/com/streamarr/transcode/engine/dolby-vision-5-test-frame.mp4"
echo "recorded into $FIXTURES"
