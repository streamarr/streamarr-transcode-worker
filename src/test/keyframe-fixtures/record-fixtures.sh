#!/usr/bin/env bash
# Regenerates the keyframe-position fixtures in src/test/resources/keyframes.
#
# Every FFmpeg run happens inside the pinned worker image, so the fixtures come from the worker's
# own FFmpeg. Sources are synthesized with lavfi: 4 s of 64x36 testsrc2 at 24 fps with keyframes
# forced at frames 0, 17, 50 and 70, and a 440 Hz tone. Nothing is downloaded.
#
#   src/test/keyframe-fixtures/record-fixtures.sh
#   WORKER_IMAGE=streamarr-worker:local src/test/keyframe-fixtures/record-fixtures.sh
#   FATE_SUITE=/path/to/fate-suite src/test/keyframe-fixtures/record-fixtures.sh
#
# A truncated fixture keeps its index and loses the bytes of its last indexed keyframe.
#
# The packet-scan recordings in scans/ keep no source: only the probe's ffprobe output and the
# framecrc lines and diagnostics of the probe's own packet scan. They cover avi.avi, an AVI with
# B-frames made here, and, when FATE_SUITE names a local copy of FFmpeg's FATE samples
# (rsync://fate-suite.ffmpeg.org/fate-suite/), raw H.264 and HEVC streams and an H.264 AVI from it.
# Without FATE_SUITE, the script keeps the recordings of the FATE samples as they are.
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

# An H.264 AVI with B-frames, whose packets the AVI demuxer gives no presentation time.
"${FF[@]}" "${SOURCE[@]}" "${X264[@]}" "${BFRAMES[@]}" -map 0:v "${OUT[@]}" avi-bframes.avi

# The probe's own ffprobe run and packet scan of the first stream, as FfprobeExecutor and
# KeyframePacketScan run them, without the source's path.
mkdir -p scans
scan() {
  local source="$1" name="$2"
  ffprobe -v quiet -print_format json -analyzeduration 200M -probesize 1G -scan_all_pmts 0 \
    -show_streams -show_format -show_chapters -show_error "$source" \
    | sed '/"filename":/d' > "scans/$name.json"
  # FFmpeg names each logging context by its address, which changes from run to run.
  ffmpeg -nostdin -hide_banner -nostats -v error -copyts -i "$source" -map 0:0 -c copy \
    -f framecrc - > "scans/$name.framecrc" 2> "scans/$name.log"
  sed -E 's/ @ 0x[0-9a-f]+\]/]/' "scans/$name.log" > "scans/$name.diagnostics"
  rm "scans/$name.log"
  if [ ! -s "scans/$name.diagnostics" ]; then
    rm "scans/$name.diagnostics"
  fi
}
scan avi.avi avi.avi
scan avi-bframes.avi avi-bframes.avi
if [ -d /fate ]; then
  scan /fate/h264/sei-1.h264 h264-sei-1.h264
  scan /fate/h264/ref_10.avi h264-ref_10.avi
  scan /fate/hevc/food.hevc hevc-food.hevc
  scan /fate/hevc/pir.hevc hevc-pir.hevc
fi
CONTAINER

fate_mount=()
if [ -n "${FATE_SUITE:-}" ]; then
  fate_mount=(-v "$FATE_SUITE":/fate:ro)
fi

# The buildpack launcher prints JVM lines on its own stdout, so FFmpeg writes only to files.
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
  -v "$WORK":/work ${fate_mount[@]+"${fate_mount[@]}"} "$IMAGE" bash /work/record.sh \
  > "$WORK/launcher.log" 2>&1 || { cat "$WORK/launcher.log"; exit 1; }

for fixture in mp4-bframes-editlist.mp4 mp4-truncated.mp4 mp4-fragmented.mp4 mkv-libavformat.mkv \
  mkv-pipe.mkv mkv-truncated-cues-front.mkv mpegts.ts avi.avi ffmpeg-version.txt; do
  cp "$WORK/$fixture" "$FIXTURES/$fixture"
done
mkdir -p "$FIXTURES/scans"
cp "$WORK"/scans/* "$FIXTURES/scans/"
echo "recorded into $FIXTURES with $IMAGE"
