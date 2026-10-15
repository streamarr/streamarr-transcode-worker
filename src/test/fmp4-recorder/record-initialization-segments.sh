#!/usr/bin/env bash
# Records the initialization segments that CodecConfigurationReaderTest reads.
#
# Every FFmpeg run happens inside the pinned worker image, as record-fixtures.sh does. Each
# recording is the worker's pipe recipe run over a short lavfi source, cut after its ftyp and moov
# boxes. The host needs only Docker and Python 3.
#
#   src/test/fmp4-recorder/record-initialization-segments.sh
#   WORKER_IMAGE=streamarr-worker:local src/test/fmp4-recorder/record-initialization-segments.sh
#
# It writes the recordings into src/test/resources/initialization-segments, wherever it is run from.
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c4f192e5e359cd7ead44ca05c7d0c720d491c3d5b4a6becfffcd61caa}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SEGMENTS="$(cd "$HERE/../resources/initialization-segments" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/record.sh" <<'CONTAINER'
set -euo pipefail
cd /work
mkdir -p src out

FF=(ffmpeg -hide_banner -nostdin -loglevel error -y)
PIPE=(-map 0:v:0 -map 0:a:0 -map -0:s -map_metadata -1 -map_chapters -1 -copyts
  -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128)
AAC=(-c:a aac -ac 2 -b:a 128k)
OUT=(-threads 1 -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont
  -frag_duration 1000000)

# A 1 s source of the given size, rate and pixel format with stereo AAC, as Matroska.
source() {  # NAME SIZE RATE
  "${FF[@]}" -f lavfi -i "testsrc2=size=$2:rate=$3:duration=1" \
    -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000:duration=1" \
    -c:v ffv1 -c:a pcm_s16le "src/$1.mkv"
}

# The pipe recipe over SRC into out/NAME.mp4, with the codec arguments after the --.
record() {  # NAME SRC -- ARGS...
  local name=$1 src=$2; shift 3
  "${FF[@]}" -i "$src" "${PIPE[@]}" "$@" "${OUT[@]}" "out/$name.mp4"
}

source 1080p60 1920x1080 60
source 1080p30 1920x1080 30
source 64x36 64x36 24

record libx264-1080p60 src/1080p60.mkv -- -c:v libx264 -profile:v high -level:v 42 \
  -vf format=yuv420p -b:v 5000000 -maxrate 5000000 -bufsize 10000000 "${AAC[@]}"
record libx264-1080p30 src/1080p30.mkv -- -c:v libx264 -profile:v high -level:v 40 \
  -vf format=yuv420p -b:v 5000000 -maxrate 5000000 -bufsize 10000000 "${AAC[@]}"
record libsvtav1-1080p60 src/1080p60.mkv -- -c:v libsvtav1 -level:v 41 \
  -vf format=yuv420p -crf 35 -maxrate 5000000 -svtav1-params mbr-overshoot-pct=0:lp=1 "${AAC[@]}"
record libsvtav1-1080p30 src/1080p30.mkv -- -c:v libsvtav1 -level:v 40 \
  -vf format=yuv420p -crf 35 -maxrate 5000000 -svtav1-params mbr-overshoot-pct=0:lp=1 "${AAC[@]}"

# 10-bit and 4:4:4 output, which no variant carries, for the bit depth and chroma subsampling.
record libx264-high10 src/64x36.mkv -- -c:v libx264 -vf format=yuv420p10le "${AAC[@]}"
record libx264-high444 src/64x36.mkv -- -c:v libx264 -vf format=yuv444p "${AAC[@]}"
record libsvtav1-10bit src/64x36.mkv -- -c:v libsvtav1 -vf format=yuv420p10le \
  -svtav1-params lp=1 "${AAC[@]}"

# HEVC copies without the worker's tag rule, so that the reader meets both sample entries: an MP4
# source keeps its hvc1 sample entry, and a Matroska source becomes hev1.
X265=(-c:v libx265 -profile:v main -x265-params level-idc=40:high-tier=0:pools=none:log-level=error
  -vf format=yuv420p -b:v 5000000)
"${FF[@]}" -i src/1080p30.mkv "${X265[@]}" -tag:v hvc1 "${AAC[@]}" src/hevc.mp4
"${FF[@]}" -i src/1080p30.mkv "${X265[@]}" "${AAC[@]}" src/hevc.mkv
record copy-hvc1 src/hevc.mp4 -- -c:v copy -c:a copy -b:a 0
record copy-hev1 src/hevc.mkv -- -c:v copy -c:a copy -b:a 0
CONTAINER

# The buildpack launcher prints JVM memory lines on its own stdout, so FFmpeg writes only to files.
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
  -v "$WORK":/work "$IMAGE" bash /work/record.sh > "$WORK/launcher.log" 2>&1 \
  || { cat "$WORK/launcher.log"; exit 1; }

# Keeps each recording's ftyp and moov, the initialization segment, and drops its fragments.
python3 -I - "$WORK/out" "$SEGMENTS" <<'PYTHON'
import pathlib, struct, sys

source, target = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for recording in sorted(source.glob("*.mp4")):
    data = recording.read_bytes()
    offset = 0
    for expected in (b"ftyp", b"moov"):
        size, box = struct.unpack(">I4s", data[offset:offset + 8])
        if box != expected:
            sys.exit(f"{recording.name}: {box!r} where {expected!r} belongs")
        offset += size
    (target / recording.name).write_bytes(data[:offset])
    print(f"{recording.name}: {offset} bytes")
PYTHON
