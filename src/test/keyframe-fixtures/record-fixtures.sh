#!/usr/bin/env bash
# Regenerates the keyframe-position fixtures in src/test/resources/keyframes.
#
# Every FFmpeg run happens inside the pinned worker image, so the fixtures come from the worker's
# own FFmpeg. Sources are synthesized with lavfi: 4 s of 64x36 testsrc2 at 24 fps with keyframes
# forced at frames 0, 17, 50 and 70, and a 440 Hz tone. Nothing is downloaded.
#
#   src/test/keyframe-fixtures/record-fixtures.sh
#   WORKER_IMAGE=streamarr-worker:local src/test/keyframe-fixtures/record-fixtures.sh
#
# A truncated fixture keeps its index and loses the bytes of its last indexed keyframe.
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:0a27081ce19e529fd6758346f403691c26455d5916bffc7b79e6a81e5dc139ee}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$(cd "$HERE/../resources/keyframes" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/record.sh" <<'CONTAINER'
set -euo pipefail
cd /work
FF=(ffmpeg -hide_banner -nostdin -loglevel error -y)
SOURCE=(-f lavfi -i testsrc2=size=64x36:rate=24:duration=4 -f lavfi -i sine=frequency=440:duration=4)
KEYFRAMES=(-force_key_frames "expr:eq(n,0)+eq(n,17)+eq(n,50)+eq(n,70)" -g 1000 -sc_threshold 0)
X264=(-c:v libx264 -preset ultrafast -threads 1 -pix_fmt yuv420p -flags:v +bitexact "${KEYFRAMES[@]}")
BFRAMES=(-bf 2)
AAC=(-c:a aac -b:a 32k -flags:a +bitexact)
OUT=(-map_metadata -1 -shortest -fflags +bitexact)

"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" mp4-bframes-editlist.mp4
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" -movflags +faststart mp4-truncated.mp4
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" -movflags +frag_keyframe+empty_moov mp4-fragmented.mp4
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" mkv-libavformat.mkv
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" -f matroska pipe:1 > mkv-pipe.mkv
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" -cues_to_front 1 mkv-truncated-cues-front.mkv
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" "${AAC[@]}" "${OUT[@]}" mpegts.ts
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" -bf 0 -c:a mp3 -b:a 32k "${OUT[@]}" avi.avi

# Cut each truncated fixture just before its last keyframe's packet, which the index still lists.
truncate_before_last_keyframe() {
  local file="$1" position
  position="$(ffprobe -v quiet -select_streams v:0 -show_entries packet=pos,flags -of csv=p=0 "$file" \
    | awk -F, '$2 ~ /^K/ { last = $1 } END { print last }')"
  truncate -s "$position" "$file"
}
truncate_before_last_keyframe mp4-truncated.mp4
truncate_before_last_keyframe mkv-truncated-cues-front.mkv

ffmpeg -version | head -1 > ffmpeg-version.txt
CONTAINER

# The buildpack launcher prints JVM lines on its own stdout, so FFmpeg writes only to files.
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
  -v "$WORK":/work "$IMAGE" bash /work/record.sh > "$WORK/launcher.log" 2>&1 \
  || { cat "$WORK/launcher.log"; exit 1; }

for fixture in mp4-bframes-editlist.mp4 mp4-truncated.mp4 mp4-fragmented.mp4 mkv-libavformat.mkv \
  mkv-pipe.mkv mkv-truncated-cues-front.mkv mpegts.ts avi.avi ffmpeg-version.txt; do
  cp "$WORK/$fixture" "$FIXTURES/$fixture"
done
echo "recorded into $FIXTURES with $IMAGE"
