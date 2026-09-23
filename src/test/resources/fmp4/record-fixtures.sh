#!/usr/bin/env bash
# Regenerates the fragmented-MP4 grouping fixtures (ADR 0037 "Tests substitute the process").
#
# Every FFmpeg and ffprobe run happens inside the pinned worker image, so every recorded byte comes
# from the worker's own FFmpeg 8.1.2-Jellyfin. The host needs only Docker and python3 (standard
# library): analyze.py reads the recorded boxes itself, groups the fragments by the ADR rules,
# measures the HLS muxer's cut points for the same sources, and writes expected.json.
#
# It writes the recordings and expected.json into its own directory, wherever it is run from:
#
#   src/test/resources/fmp4/record-fixtures.sh                  # re-record in place
#   WORK=/some/dir src/test/resources/fmp4/record-fixtures.sh   # keep the sources, logs and HLS oracle outputs
#   WORKER_IMAGE=streamarr-worker:local src/test/resources/fmp4/record-fixtures.sh   # record with another image
#
# Sources are synthesized with lavfi inside the container; nothing is downloaded.
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c4f192e5e359cd7ead44ca05c7d0c720d491c3d5b4a6becfffcd61caa}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"

cat > "$WORK/record.sh" <<'CONTAINER'
set -euo pipefail
cd /work
rm -rf src out hls logs && mkdir -p src out hls logs

FF=(ffmpeg -hide_banner -nostdin -loglevel error)
P=6                                   # segment period in seconds
FRAG_US=1000000                       # fragmentation target: 1 s
MOVFLAGS=cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont
# The probed frame rate exactly as the worker passes it: ffprobe r_frame_rate 24000/1001 as a
# Java double. Every encoded source below probes as 24000/1001 (analyze.py checks it).
FPS=23.976023976023978
GOP_FLOOR=143                         # floor(P * FPS): ADR 0037's recipe
GOP_CEIL=144                          # ceil(P * FPS): today's FfmpegCommandBuilder
# Fixture-only additions: single-threaded encoders (-threads 1, and lp=1 for SVT-AV1, whose output
# otherwise differs on every run), so that a re-recording reproduces the same bytes. Threading
# changes rate-control decisions, never where a keyframe is placed or where the muxer cuts.
DET=(-threads 1)

X264=(-c:v libx264 -vf scale=-2:36 -b:v 6000 -maxrate 6000 -bufsize 12000)
SVT=(-c:v libsvtav1 -vf scale=-2:36 -crf 35 -maxrate 6000 -svtav1-params mbr-overshoot-pct=0:lp=1)
AAC=(-c:a aac -ac 1 -b:a 8k)
COPY_NEW=(-c:v copy -c:a copy -bsf:a aac_adtstoasc)
COPY_TODAY=(-c:v copy -c:a copy)
COMMON_TODAY=(-map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled -max_muxing_queue_size 128 -max_delay 5000000)
COMMON_NEW=(-map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128)

# Keyframe arguments: "today" = the current FfmpegCommandBuilder, "new" = ADR 0037's recipe.
KEY_X264_TODAY=(-forced-idr 1 -force_key_frames:0 "expr:gte(t,n_forced*$P)" -sc_threshold:v:0 0)
KEY_X264_NEW=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "expr:gte(t,n_forced*$P)" -g:v:0 "$GOP_FLOOR" -sc_threshold:v:0 0)
KEY_SVT_TODAY=(-forced-idr 1 -g:v:0 "$GOP_CEIL" -keyint_min:v:0 "$GOP_CEIL")
KEY_SVT_NEW=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "expr:gte(t,n_forced*$P)" -g:v:0 "$GOP_FLOOR" -keyint_min:v:0 "$GOP_FLOOR")

# run KIND NAME [start=N] [seek=S] [today] [video] SRC -- CODEC/KEYFRAME ARGS...
#   KIND pipe: ADR 0037's recipe to pipe:1, recorded as out/NAME.fmp4
#   KIND hls:  the HLS muxer (fMP4 segments) into hls/NAME/; "today" swaps in today's common
#              flags (no -start_at_zero, -max_delay), "video" maps the video stream only.
run() {
  local kind=$1 name=$2; shift 2
  local start=0 seek=() common=("${COMMON_NEW[@]}") maps=(-map 0:v:0 -map 0:a:0)
  while [ "$1" != "--" ] && [ $# -gt 1 ]; do
    case "$1" in
      start=*) start=${1#start=} ;;
      seek=*) seek=(-ss "${1#seek=}") ;;
      today) common=("${COMMON_TODAY[@]}") ;;
      video) maps=(-map 0:v:0) ;;
      *) break ;;
    esac
    shift
  done
  local src=$1; shift 2
  if [ "$kind" = pipe ]; then
    "${FF[@]}" -y "${seek[@]}" -i "$src" "${maps[@]}" "${common[@]}" "$@" "${DET[@]}" \
      -f mp4 -movflags "$MOVFLAGS" -frag_duration "$FRAG_US" pipe:1 > "out/$name.fmp4" 2> "logs/$name.log"
    return
  fi
  local number=(); [ "$start" -gt 0 ] && number=(-start_number "$start")
  mkdir -p "hls/$name"
  local status=0
  "${FF[@]}" -y "${seek[@]}" -i "$src" "${maps[@]}" "${common[@]}" "$@" "${DET[@]}" \
    -f hls -hls_time "$P" -hls_list_size 0 -hls_flags temp_file "${number[@]}" \
    -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_options movflags=+frag_discont \
    -hls_segment_filename "hls/$name/segment%d.m4s" "hls/$name/stream.m3u8" 2> "logs/hls-$name.log" || status=$?
  echo "$status" > "hls/$name/exit-status"
}

# ------------------------------------------------------------------ sources (not delivered)
SILENCE="anullsrc=channel_layout=mono:sample_rate=48000"
SRC_X264=(-c:v libx264 -threads 1 -b:v 8k -sc_threshold 0)    # medium preset: B-frames on
SRC_AAC=(-c:a aac -ac 1 -b:a 8k)
lavfi() { echo "testsrc=size=64x36:rate=$1:duration=$2"; }

# Constant 23.976 fps, a keyframe every 48 frames (2.002 s), 66 s.
"${FF[@]}" -f lavfi -i "$(lavfi 24000/1001 66)" -f lavfi -i "$SILENCE:duration=66" -map 0:v -map 1:a \
  "${SRC_X264[@]}" -g 48 -keyint_min 48 "${SRC_AAC[@]}" -fflags +bitexact src/cfr.mp4

# 24 fps (exact frame times), keyframes only at 0, 6.5, 12, 18, 24.5, 30, 36.5, 42, 48, 54.5, 60 s; 66 s.
"${FF[@]}" -f lavfi -i "$(lavfi 24 66)" -f lavfi -i "$SILENCE:duration=66" -map 0:v -map 1:a \
  "${SRC_X264[@]}" -g 10000 -force_key_frames 0,6.5,12,18,24.5,30,36.5,42,48,54.5,60 \
  "${SRC_AAC[@]}" -fflags +bitexact src/irregular.mp4

# Variable frame rate on the 24000/1001 grid: 20 % irregular drops, then half rate, then 46 %
# irregular drops, then every fifth frame dropped. Irregular keyframes, one or two per 6 s
# interval, several within 100 ms before a boundary. B-frames; 66 s.
VFR_SELECT="select='if(lt(n,300),gte(mod(n*7919,97),20),if(lt(n,600),not(mod(n,2)),if(lt(n,1000),gte(mod(n*7919,97),45),not(eq(mod(n,5),2)))))'"
"${FF[@]}" -f lavfi -i "$(lavfi 24000/1001 66)" -f lavfi -i "$SILENCE:duration=66" -map 0:v -map 1:a \
  -vf "$VFR_SELECT" -fps_mode passthrough "${SRC_X264[@]}" -g 10000 \
  -force_key_frames 0,2.5,5.9,8.2,12.3,17.95,19,24.1,29.95,31,36.2,40.5,42.1,47.9,49,53.3,54.1,59.9,61,65 \
  "${SRC_AAC[@]}" -fflags +bitexact src/vfr.mp4

# MPEG-TS whose timestamps begin at 12 s (no mux delay), a keyframe every 2.002 s, 30 s.
"${FF[@]}" -f lavfi -i "$(lavfi 24000/1001 30)" -f lavfi -i "$SILENCE:duration=30" -map 0:v -map 1:a \
  "${SRC_X264[@]}" -g 48 -keyint_min 48 "${SRC_AAC[@]}" \
  -output_ts_offset 12 -muxdelay 0 -muxpreload 0 -fflags +bitexact -f mpegts src/late.ts

# Audio outlasts video by 3.5 s: 20 s of video, 23.5 s of audio.
"${FF[@]}" -f lavfi -i "$(lavfi 24000/1001 20)" -f lavfi -i "$SILENCE:duration=23.5" -map 0:v -map 1:a \
  "${SRC_X264[@]}" -g 48 -keyint_min 48 "${SRC_AAC[@]}" -fflags +bitexact src/tail.mp4

# A keyframe every 240 frames (10.01 s): wider than the period, so a segment number is skipped.
"${FF[@]}" -f lavfi -i "$(lavfi 24000/1001 25)" -f lavfi -i "$SILENCE:duration=25" -map 0:v -map 1:a \
  "${SRC_X264[@]}" -g 240 -keyint_min 240 "${SRC_AAC[@]}" -fflags +bitexact src/gop10.mp4

for s in src/*.mp4 src/*.ts; do
  ffprobe -v error -show_entries format=start_time:stream=index,codec_type,codec_name,time_base,start_pts,start_time,r_frame_rate,avg_frame_rate \
    -of json "$s" > "$s.probe.json"
  ffprobe -v error -select_streams v:0 -show_entries packet=pts,flags -of csv=p=0 "$s" | grep K > "$s.keyframes.csv"
done

# ------------------------------------------------------------------ 1. constant 23.976 fps libx264 encode, and an encoded seek
run pipe 01-encode-cfr src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
run pipe 01-encode-cfr-seek30 seek=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
run hls 01-encode-cfr.today today src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_TODAY[@]}"
run hls 01-encode-cfr-seek30.today today start=5 seek=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_TODAY[@]}"
run hls 01-encode-cfr.video-only video src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_NEW[@]}"
run hls 01-encode-cfr-seek30.video-only video start=5 seek=30 src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_NEW[@]}"
run hls 01-encode-cfr.new-keyframes-with-audio src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"

# ------------------------------------------------------------------ 2. stream copy, irregular keyframes
run pipe 02-copy-irregular-keyframes src/irregular.mp4 -- "${COPY_NEW[@]}"
run hls 02-copy-irregular-keyframes.today today src/irregular.mp4 -- "${COPY_TODAY[@]}"
run hls 02-copy-irregular-keyframes.video-only video src/irregular.mp4 -- "${COPY_NEW[@]}"

# ------------------------------------------------------------------ 3. variable-frame-rate libx264 encode
run pipe 03-encode-vfr src/vfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
run hls 03-encode-vfr.today today src/vfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_TODAY[@]}"
run hls 03-encode-vfr.video-only video src/vfr.mp4 -- "${X264[@]}" "${KEY_X264_NEW[@]}"

# ------------------------------------------------------------------ 4. variable-frame-rate stream copy with B-frames
run pipe 04-copy-vfr-bframes src/vfr.mp4 -- "${COPY_NEW[@]}"
run hls 04-copy-vfr-bframes.today today src/vfr.mp4 -- "${COPY_TODAY[@]}"
run hls 04-copy-vfr-bframes.video-only video src/vfr.mp4 -- "${COPY_NEW[@]}"

# ------------------------------------------------------------------ 5. timestamps begin at 12 s (MPEG-TS source), encode and copy
run pipe 05-encode-late-start src/late.ts -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
run pipe 05-copy-late-start src/late.ts -- "${COPY_NEW[@]}"
run hls 05-encode-late-start.today today src/late.ts -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_TODAY[@]}"
run hls 05-encode-late-start.video-only video src/late.ts -- "${X264[@]}" "${KEY_X264_NEW[@]}"
# Today's exact copy recipe fails on ADTS audio in fMP4 (kept to record the exit status) ...
run hls 05-copy-late-start.today today src/late.ts -- "${COPY_TODAY[@]}"
# ... so the copy oracle adds the bitstream filter.
run hls 05-copy-late-start.today-adtstoasc today src/late.ts -- "${COPY_NEW[@]}"
run hls 05-copy-late-start.video-only video src/late.ts -- "${COPY_NEW[@]}"

# ------------------------------------------------------------------ 6. audio outlasts video
run pipe 06-encode-audio-tail src/tail.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
run hls 06-encode-audio-tail.today today src/tail.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_TODAY[@]}"
run hls 06-encode-audio-tail.video-only video src/tail.mp4 -- "${X264[@]}" "${KEY_X264_NEW[@]}"

# ------------------------------------------------------------------ 7 (and 8). stream-copy restart
run pipe 07-copy-start0 src/cfr.mp4 -- "${COPY_NEW[@]}"
run pipe 07-copy-seek30 seek=30 src/cfr.mp4 -- "${COPY_NEW[@]}"
run hls 07-copy-start0.today today src/cfr.mp4 -- "${COPY_TODAY[@]}"
run hls 07-copy-seek30.today today start=5 seek=30 src/cfr.mp4 -- "${COPY_TODAY[@]}"
run hls 07-copy-start0.video-only video src/cfr.mp4 -- "${COPY_NEW[@]}"

# ------------------------------------------------------------------ 9. irregular VFR source through SVT-AV1, with and without a seek
run pipe 09-svtav1-vfr src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_NEW[@]}"
run pipe 09-svtav1-vfr-seek30 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_NEW[@]}"
run hls 09-svtav1-vfr.today today src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_TODAY[@]}"
run hls 09-svtav1-vfr-seek30.today today start=5 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_TODAY[@]}"
run hls 09-svtav1-vfr.video-only video src/vfr.mp4 -- "${SVT[@]}" "${KEY_SVT_NEW[@]}"
run hls 09-svtav1-vfr-seek30.video-only video start=5 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${KEY_SVT_NEW[@]}"
run hls 09-svtav1-vfr.new-keyframes-with-audio src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_NEW[@]}"

# ------------------------------------------------------------------ 10. (extra) keyframe gap wider than the period
run pipe 10-copy-gop-exceeds-period src/gop10.mp4 -- "${COPY_NEW[@]}"
run hls 10-copy-gop-exceeds-period.today today src/gop10.mp4 -- "${COPY_TODAY[@]}"

# ------------------------------------------------------------------ side claims of ADR 0037 (recorded, not delivered)
mkdir -p claims
claim() {
  local name=$1; shift
  local status=0
  "${FF[@]}" -y "$@" "${DET[@]}" -f mp4 -movflags "$MOVFLAGS" -frag_duration "$FRAG_US" pipe:1 \
    > "claims/$name.fmp4" 2> "claims/$name.log" || status=$?
  echo "$status" > "claims/$name.exit-status"
}
AV=(-map 0:v:0 -map 0:a:0)
# delay_moov stops the mp4 muxer inserting aac_adtstoasc itself: an MPEG-TS AAC copy must fail without it ...
claim ts-copy-without-adtstoasc -i src/late.ts "${AV[@]}" "${COMMON_NEW[@]}" "${COPY_TODAY[@]}"
# ... and the filter leaves a copy from an MP4 source byte-identical (compare with out/07-copy-start0.fmp4).
claim mp4-copy-without-adtstoasc -i src/cfr.mp4 "${AV[@]}" "${COMMON_NEW[@]}" "${COPY_TODAY[@]}"
# -max_delay 5000000 changes nothing in mp4 output (compare with out/01-encode-cfr.fmp4).
claim encode-with-max-delay -i src/cfr.mp4 "${AV[@]}" "${COMMON_NEW[@]}" -max_delay 5000000 "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}"
# An explicit -fps_mode cfr after a seek pads from time zero (compare with out/01-encode-cfr-seek30.fmp4).
claim encode-seek30-fps-mode-cfr -ss 30 -i src/cfr.mp4 "${AV[@]}" "${COMMON_NEW[@]}" "${X264[@]}" "${AAC[@]}" "${KEY_X264_NEW[@]}" -fps_mode:v:0 cfr

ffmpeg -version | head -1 > ffmpeg-version.txt
CONTAINER

# The buildpack launcher prints JVM memory lines on its own stdout; FFmpeg's pipe:1 goes to files
# inside the container script, so that noise never reaches a recording.
docker run --rm --entrypoint /cnb/lifecycle/launcher -v "$WORK":/work "$IMAGE" bash /work/record.sh \
  > "$WORK/launcher.log" 2>&1 || { cat "$WORK/launcher.log"; exit 1; }

python3 -B "$HERE/analyze.py" --work "$WORK" --out "$HERE" --image "$IMAGE"
echo "recorded into $HERE (sources, logs and HLS oracle outputs in $WORK)"
