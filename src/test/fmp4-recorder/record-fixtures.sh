#!/usr/bin/env bash
# Regenerates the fragmented-MP4 grouping fixtures (ADR 0037 "Tests substitute the process").
#
# Every FFmpeg and ffprobe run happens inside the pinned worker image, so every recorded byte comes
# from the worker's own FFmpeg 8.1.2-Jellyfin. The host needs only Docker and Node (the major in
# buildpacks/ffmpeg/.nvmrc, no packages): analyze.mjs reads the recorded boxes itself, groups the
# fragments by the ADR rules, measures the HLS muxer's cut points for the same sources, and writes
# expected.json.
#
# It writes the recordings and expected.json into src/test/resources/fmp4, wherever it is run from:
#
#   src/test/fmp4-recorder/record-fixtures.sh                  # re-record in place
#   WORK=/some/dir src/test/fmp4-recorder/record-fixtures.sh   # keep the sources, logs and HLS muxer outputs
#   WORKER_IMAGE=streamarr-worker:local src/test/fmp4-recorder/record-fixtures.sh   # record with another image
#
# Sources are synthesized with lavfi inside the container; nothing is downloaded.
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c4f192e5e359cd7ead44ca05c7d0c720d491c3d5b4a6becfffcd61caa}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$(cd "$HERE/../resources/fmp4" && pwd)"
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
# Java double. Every encoded source below probes as 24000/1001 (analyze.mjs checks it).
FPS=23.976023976023978
GOP_VERIFIED=145                      # ceil(P * FPS) + 1: ADR 0037's backstop for an encoder verified to honour forced keyframes
GOP_FLOOR=143                         # floor(P * FPS): ADR 0037's GOP for an encoder not verified to (libx265 until worker #23, hardware until worker #14)
GOP_CEIL=144                          # ceil(P * FPS): the HLS recipe's frame-count GOP
FORCED_EVERY_PERIOD="expr:gte(t,n_forced*$P)"
# Forces 0, 6, 12, 24, 30 ... s: the forced keyframe for 18 s never comes, so the GOP backstop must
# place interval 3's keyframe.
FORCED_EXCEPT_18="expr:gte(t,(n_forced+gte(n_forced,3))*$P)"
# Fixture-only additions: single-threaded encoders (-threads 1, and lp=1 for SVT-AV1, whose output
# otherwise differs on every run), so that a re-recording reproduces the same bytes. For libx264 and
# libx265 threading changes rate-control decisions, never where a keyframe is placed or where the
# muxer cuts. For SVT-AV1 lp=1 also excludes upstream bug #2385 (worker #42), which under the
# worker's threading can reorder packets and crowd keyframes, so no recording shows that bug.
DET=(-threads 1)

X264=(-c:v libx264 -vf scale=-2:36 -b:v 6000 -maxrate 6000 -bufsize 12000)
SVT=(-c:v libsvtav1 -vf scale=-2:36 -crf 35 -maxrate 6000 -svtav1-params mbr-overshoot-pct=0:lp=1)
# The worker's libx265 arguments (open GOP and x265's scene cut left at their defaults; worker #23
# owns the closed-GOP flags), single-threaded for reproducible bytes.
X265=(-c:v libx265 -vf scale=-2:36 -b:v 6000 -maxrate 6000 -bufsize 12000 -x265-params pools=none:frame-threads=1:log-level=error)
AAC=(-c:a aac -ac 1 -b:a 8k)
COPY_PIPE=(-c:v copy -c:a copy -bsf:a aac_adtstoasc)
COPY_HLS=(-c:v copy -c:a copy)
COMMON_HLS=(-map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled -max_muxing_queue_size 128 -max_delay 5000000)
COMMON_PIPE=(-map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128)

# Two recipes: HLS = the HLS muxer recipe that ADR 0037 replaces, PIPE = ADR 0037's pipe recipe.
KEY_X264_HLS=(-forced-idr 1 -force_key_frames:0 "$FORCED_EVERY_PERIOD" -sc_threshold:v:0 0)
KEY_X264_PIPE=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EVERY_PERIOD" -g:v:0 "$GOP_VERIFIED" -sc_threshold:v:0 0)
KEY_X264_PIPE_FLOORED=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EVERY_PERIOD" -g:v:0 "$GOP_FLOOR" -sc_threshold:v:0 0)
KEY_X264_PIPE_MISSED=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EXCEPT_18" -g:v:0 "$GOP_VERIFIED" -sc_threshold:v:0 0)
KEY_SVT_HLS=(-forced-idr 1 -g:v:0 "$GOP_CEIL" -keyint_min:v:0 "$GOP_CEIL")
KEY_SVT_PIPE=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EVERY_PERIOD" -g:v:0 "$GOP_VERIFIED" -keyint_min:v:0 "$GOP_VERIFIED")
KEY_SVT_PIPE_MISSED=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EXCEPT_18" -g:v:0 "$GOP_VERIFIED" -keyint_min:v:0 "$GOP_VERIFIED")
KEY_X265_PIPE=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EVERY_PERIOD" -g:v:0 "$GOP_VERIFIED")
KEY_X265_PIPE_MISSED=(-r:v:0 "$FPS" -forced-idr 1 -force_key_frames:0 "$FORCED_EXCEPT_18" -g:v:0 "$GOP_VERIFIED")

# run KIND NAME [start=N] [seek=S] [duration=D] [hls-recipe] [video] SRC -- CODEC/KEYFRAME ARGS...
#   KIND pipe: ADR 0037's recipe to pipe:1, recorded as out/NAME.fmp4
#   KIND hls:  the HLS muxer (fMP4 segments) into hls/NAME/; "hls-recipe" swaps in the HLS recipe's common
#              flags (no -start_at_zero, -max_delay), "video" maps the video stream only.
#   duration=D reads only the source's first D seconds.
run() {
  local kind=$1 name=$2; shift 2
  local start=0 seek=() common=("${COMMON_PIPE[@]}") maps=(-map 0:v:0 -map 0:a:0)
  local duration=()
  while [ "$1" != "--" ] && [ $# -gt 1 ]; do
    case "$1" in
      start=*) start=${1#start=} ;;
      seek=*) seek=(-ss "${1#seek=}") ;;
      duration=*) duration=(-t "${1#duration=}") ;;
      hls-recipe) common=("${COMMON_HLS[@]}") ;;
      video) maps=(-map 0:v:0) ;;
      *) break ;;
    esac
    shift
  done
  local src=$1; shift 2
  if [ "$kind" = pipe ]; then
    "${FF[@]}" -y "${seek[@]}" "${duration[@]}" -i "$src" "${maps[@]}" "${common[@]}" "$@" "${DET[@]}" \
      -f mp4 -movflags "$MOVFLAGS" -frag_duration "$FRAG_US" pipe:1 > "out/$name.fmp4" 2> "logs/$name.log"
    return
  fi
  local number=(); [ "$start" -gt 0 ] && number=(-start_number "$start")
  mkdir -p "hls/$name"
  local status=0
  "${FF[@]}" -y "${seek[@]}" "${duration[@]}" -i "$src" "${maps[@]}" "${common[@]}" "$@" "${DET[@]}" \
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
run pipe 01-encode-cfr src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
run pipe 01-encode-cfr-seek30 seek=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
run hls 01-encode-cfr.hls-recipe hls-recipe src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_HLS[@]}"
run hls 01-encode-cfr-seek30.hls-recipe hls-recipe start=5 seek=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_HLS[@]}"
run hls 01-encode-cfr.video-only video src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_PIPE[@]}"
run hls 01-encode-cfr-seek30.video-only video start=5 seek=30 src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_PIPE[@]}"
run hls 01-encode-cfr.pipe-keyframes-with-audio src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"

# ------------------------------------------------------------------ 2. stream copy, irregular keyframes
run pipe 02-copy-irregular-keyframes src/irregular.mp4 -- "${COPY_PIPE[@]}"
run hls 02-copy-irregular-keyframes.hls-recipe hls-recipe src/irregular.mp4 -- "${COPY_HLS[@]}"
run hls 02-copy-irregular-keyframes.video-only video src/irregular.mp4 -- "${COPY_PIPE[@]}"

# ------------------------------------------------------------------ 3. variable-frame-rate libx264 encode
run pipe 03-encode-vfr src/vfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
run hls 03-encode-vfr.hls-recipe hls-recipe src/vfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_HLS[@]}"
run hls 03-encode-vfr.video-only video src/vfr.mp4 -- "${X264[@]}" "${KEY_X264_PIPE[@]}"

# ------------------------------------------------------------------ 4. variable-frame-rate stream copy with B-frames
run pipe 04-copy-vfr-bframes src/vfr.mp4 -- "${COPY_PIPE[@]}"
run hls 04-copy-vfr-bframes.hls-recipe hls-recipe src/vfr.mp4 -- "${COPY_HLS[@]}"
run hls 04-copy-vfr-bframes.video-only video src/vfr.mp4 -- "${COPY_PIPE[@]}"

# ------------------------------------------------------------------ 5. timestamps begin at 12 s (MPEG-TS source), encode and copy
run pipe 05-encode-late-start src/late.ts -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
run pipe 05-copy-late-start src/late.ts -- "${COPY_PIPE[@]}"
run hls 05-encode-late-start.hls-recipe hls-recipe src/late.ts -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_HLS[@]}"
run hls 05-encode-late-start.video-only video src/late.ts -- "${X264[@]}" "${KEY_X264_PIPE[@]}"
# The HLS recipe's exact copy fails on ADTS audio in fMP4 (kept to record the exit status) ...
run hls 05-copy-late-start.hls-recipe hls-recipe src/late.ts -- "${COPY_HLS[@]}"
# ... so the copy's HLS comparison adds the bitstream filter.
run hls 05-copy-late-start.hls-recipe-adtstoasc hls-recipe src/late.ts -- "${COPY_PIPE[@]}"
run hls 05-copy-late-start.video-only video src/late.ts -- "${COPY_PIPE[@]}"

# ------------------------------------------------------------------ 6. audio outlasts video
run pipe 06-encode-audio-tail src/tail.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
run hls 06-encode-audio-tail.hls-recipe hls-recipe src/tail.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_HLS[@]}"
run hls 06-encode-audio-tail.video-only video src/tail.mp4 -- "${X264[@]}" "${KEY_X264_PIPE[@]}"

# ------------------------------------------------------------------ 7 (and 8). stream-copy replacement attempt
run pipe 07-copy-start0 src/cfr.mp4 -- "${COPY_PIPE[@]}"
run pipe 07-copy-seek30 seek=30 src/cfr.mp4 -- "${COPY_PIPE[@]}"
run hls 07-copy-start0.hls-recipe hls-recipe src/cfr.mp4 -- "${COPY_HLS[@]}"
run hls 07-copy-seek30.hls-recipe hls-recipe start=5 seek=30 src/cfr.mp4 -- "${COPY_HLS[@]}"
run hls 07-copy-start0.video-only video src/cfr.mp4 -- "${COPY_PIPE[@]}"

# ------------------------------------------------------------------ 9. irregular VFR source through SVT-AV1, with and without a seek
run pipe 09-svtav1-vfr src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_PIPE[@]}"
run pipe 09-svtav1-vfr-seek30 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_PIPE[@]}"
run hls 09-svtav1-vfr.hls-recipe hls-recipe src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_HLS[@]}"
run hls 09-svtav1-vfr-seek30.hls-recipe hls-recipe start=5 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_HLS[@]}"
run hls 09-svtav1-vfr.video-only video src/vfr.mp4 -- "${SVT[@]}" "${KEY_SVT_PIPE[@]}"
run hls 09-svtav1-vfr-seek30.video-only video start=5 seek=30 src/vfr.mp4 -- "${SVT[@]}" "${KEY_SVT_PIPE[@]}"
run hls 09-svtav1-vfr.pipe-keyframes-with-audio src/vfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_PIPE[@]}"

# ------------------------------------------------------------------ 10. keyframe gap wider than the period
run pipe 10-copy-gop-exceeds-period src/gop10.mp4 -- "${COPY_PIPE[@]}"
run hls 10-copy-gop-exceeds-period.hls-recipe hls-recipe src/gop10.mp4 -- "${COPY_HLS[@]}"

# ------------------------------------------------------------------ 11. the floored GOP of an encoder not verified to honour forced keyframes
run pipe 11-encode-cfr-floored-gop duration=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE_FLOORED[@]}"
run hls 11-encode-cfr-floored-gop.video-only video duration=30 src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_PIPE_FLOORED[@]}"

# ------------------------------------------------------------------ 12. a forced keyframe that never comes: the GOP backstop places it
run pipe 12-encode-cfr-missed-forced-keyframe duration=30 src/cfr.mp4 -- "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE_MISSED[@]}"
run hls 12-encode-cfr-missed-forced-keyframe.video-only video duration=30 src/cfr.mp4 -- "${X264[@]}" "${KEY_X264_PIPE_MISSED[@]}"
run pipe 12-svtav1-cfr-missed-forced-keyframe duration=30 src/cfr.mp4 -- "${SVT[@]}" "${AAC[@]}" "${KEY_SVT_PIPE_MISSED[@]}"
run hls 12-svtav1-cfr-missed-forced-keyframe.video-only video duration=30 src/cfr.mp4 -- "${SVT[@]}" "${KEY_SVT_PIPE_MISSED[@]}"

# ------------------------------------------------------------------ 13. libx265 under the verified-encoder GOP, with and without a missed forced keyframe
run pipe 13-x265-cfr duration=30 src/cfr.mp4 -- "${X265[@]}" "${AAC[@]}" "${KEY_X265_PIPE[@]}"
run hls 13-x265-cfr.video-only video duration=30 src/cfr.mp4 -- "${X265[@]}" "${KEY_X265_PIPE[@]}"
run pipe 13-x265-cfr-missed-forced-keyframe duration=30 src/cfr.mp4 -- "${X265[@]}" "${AAC[@]}" "${KEY_X265_PIPE_MISSED[@]}"
run hls 13-x265-cfr-missed-forced-keyframe.video-only video duration=30 src/cfr.mp4 -- "${X265[@]}" "${KEY_X265_PIPE_MISSED[@]}"

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
claim ts-copy-without-adtstoasc -i src/late.ts "${AV[@]}" "${COMMON_PIPE[@]}" "${COPY_HLS[@]}"
# ... and the filter leaves a copy from an MP4 source byte-identical (compare with out/07-copy-start0.fmp4).
claim mp4-copy-without-adtstoasc -i src/cfr.mp4 "${AV[@]}" "${COMMON_PIPE[@]}" "${COPY_HLS[@]}"
# -max_delay 5000000 changes nothing in mp4 output (compare with out/01-encode-cfr.fmp4).
claim encode-with-max-delay -i src/cfr.mp4 "${AV[@]}" "${COMMON_PIPE[@]}" -max_delay 5000000 "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}"
# An explicit -fps_mode cfr after a seek pads from time zero (compare with out/01-encode-cfr-seek30.fmp4).
claim encode-seek30-fps-mode-cfr -ss 30 -i src/cfr.mp4 "${AV[@]}" "${COMMON_PIPE[@]}" "${X264[@]}" "${AAC[@]}" "${KEY_X264_PIPE[@]}" -fps_mode:v:0 cfr

ffmpeg -version | head -1 > ffmpeg-version.txt
CONTAINER

# The buildpack launcher prints JVM memory lines on its own stdout; FFmpeg's pipe:1 goes to files
# inside the container script, so that noise never reaches a recording.
docker run --rm --entrypoint /cnb/lifecycle/launcher -v "$WORK":/work "$IMAGE" bash /work/record.sh \
  > "$WORK/launcher.log" 2>&1 || { cat "$WORK/launcher.log"; exit 1; }

node "$HERE/analyze.mjs" --work "$WORK" --out "$FIXTURES" --image "$IMAGE"
echo "recorded into $FIXTURES (sources, logs and HLS muxer outputs in $WORK)"
