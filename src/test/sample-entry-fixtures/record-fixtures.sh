#!/usr/bin/env bash
# Regenerates the sample-entry fixtures in src/test/resources/sample-entries.
#
# Each source is 2 s of 64x64 testsrc2 video at 24 fps with a keyframe every second, from FFmpeg's
# lavfi test sources. Nothing comes from a media library. Three images run the steps:
#
# - The pinned worker image makes the raw video with its own FFmpeg.
# - Alpine runs x265 and dovi_tool, which write the Dolby Vision profile 8.2 stream. FFmpeg's test
#   sources cannot generate Dolby Vision video (ADR 0029).
# - GPAC muxes each stream into MP4. GPAC can write the sample entries that FFmpeg's mp4 muxer does
#   not: dvh1, and hev1 or avc3 whose record holds no parameter sets.
#
#   src/test/sample-entry-fixtures/record-fixtures.sh
#   WORKER_IMAGE=streamarr-worker:local src/test/sample-entry-fixtures/record-fixtures.sh
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
FF=(ffmpeg -hide_banner -nostdin -loglevel error -y)
SOURCE=(-f lavfi -i testsrc2=size=64x64:rate=24:duration=2)
"${FF[@]}" "${SOURCE[@]}" -pix_fmt yuv420p10le -strict -1 dolby-vision.y4m
"${FF[@]}" "${SOURCE[@]}" -c:v libx265 -preset ultrafast -pix_fmt yuv420p \
  -x265-params keyint=24:min-keyint=24:scenecut=0:pools=none:frame-threads=1:log-level=error hevc.hevc
"${FF[@]}" "${SOURCE[@]}" -c:v libx264 -preset ultrafast -threads 1 -pix_fmt yuv420p \
  -g 24 -keyint_min 24 -sc_threshold 0 h264.h264
ffmpeg -version | head -1 > ffmpeg-version.txt
CONTAINER

# dovi_tool writes one RPU per frame from its profile 8.1 generator; x265 places each RPU after its
# frame and signals profile 8.2, whose compatible base is BT.709 SDR.
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
echo '{"cm_version": "V40", "length": 48}' > rpu.json
/tmp/dovi_tool generate -p 8.1 -j rpu.json -o rpu.bin > /dev/null
x265 --input dolby-vision.y4m --output dolby-vision.hevc --profile main10 \
  --dolby-vision-profile 8.2 --dolby-vision-rpu rpu.bin --keyint 24 --min-keyint 24 --no-scenecut \
  --vbv-bufsize 1000 --vbv-maxrate 1000 --pools none --frame-threads 1 --log-level error --no-progress
x265 --version 2>&1 | head -1 > x265-version.txt
/tmp/dovi_tool --version > dovi_tool-version.txt
CONTAINER

# xps_inband=all moves every parameter set into the samples and leaves none in the record.
cat > "$WORK/mux.sh" <<'CONTAINER'
set -euo pipefail
cd /work
MP4Box -add dolby-vision.hevc:dvp=f8.2:fps=24 -new dolby-vision-8.2-dvh1.mp4 > /dev/null 2>&1
MP4Box -add hevc.hevc:fps=24 -new hevc.mp4 > /dev/null 2>&1
MP4Box -add h264.h264:fps=24 -new h264.mp4 > /dev/null 2>&1
gpac -logs=all@error -i hevc.mp4 -o hev1-parameter-sets-in-samples.mp4:xps_inband=all
gpac -logs=all@error -i h264.mp4 -o avc3-parameter-sets-in-samples.mp4:xps_inband=all
MP4Box -version 2>&1 | head -1 > gpac-version.txt
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

for fixture in dolby-vision-8.2-dvh1.mp4 hev1-parameter-sets-in-samples.mp4 \
  avc3-parameter-sets-in-samples.mp4; do
  cp "$WORK/$fixture" "$FIXTURES/$fixture"
done
cat "$WORK/ffmpeg-version.txt" "$WORK/x265-version.txt" "$WORK/dovi_tool-version.txt" \
  "$WORK/gpac-version.txt" > "$FIXTURES/versions.txt"
echo "recorded into $FIXTURES"
