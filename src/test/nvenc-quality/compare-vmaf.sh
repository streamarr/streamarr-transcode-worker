#!/usr/bin/env bash
# Compares the picture quality per bit of h264_nvenc and hevc_nvenc with libx264 at `faster`
# (streamarr/streamarr-transcode-worker#56). Each encode runs the worker's own FFmpeg command,
# which PrintWorkerCommand prints from FfmpegCommandBuilder, at each default-ladder rung's bitrate.
# Encodes run on the pinned FFmpeg; VMAF (vmaf_v0.6.1) runs on an FFmpeg built with libvmaf. Each
# encode is upscaled with bicubic to 1920x1080 and scored against the prepared 1080p source.
#
# Needs an NVIDIA GPU host and, in WORK_DIR/sources, the open-license sources that
# src/test/resources/nvenc/vmaf/README.md lists. Run from the repository root after
# ./mvnw test-compile and ./mvnw dependency:build-classpath -Dmdep.outputFile=target/cp.txt
# -Dmdep.includeScope=test:
#
#   PINNED_FFMPEG=... VMAF_FFMPEG=ffmpeg WORK_DIR=... src/test/nvenc-quality/compare-vmaf.sh
set -euo pipefail
REPOSITORY="$(cd "$(dirname "$0")/../../.." && pwd)"
PINNED=${PINNED_FFMPEG:?set PINNED_FFMPEG to the pinned worker FFmpeg}
VMAF_FFMPEG=${VMAF_FFMPEG:-ffmpeg}
cd "${WORK_DIR:?set WORK_DIR to a directory holding sources/}"
mkdir -p pwc prepared out
CP="pwc:$REPOSITORY/target/classes:$REPOSITORY/target/test-classes:$(cat "$REPOSITORY/target/cp.txt")"
"$JAVA_HOME/bin/javac" -d pwc -cp "$CP" "$REPOSITORY/src/test/nvenc-quality/PrintWorkerCommand.java"
RESULTS=ladder.tsv
printf 'source\tencoder\trung\tbitrateFactor\tladderBitrate\tdeliveredBitrate\tvmafMean\tencodeSeconds\n' > "$RESULTS.tmp"

# Each source becomes an FFV1 intermediate at 1920x1080, 8-bit 4:2:0, at 24 or 30 fps.
prepare() {
  local name=$1 src=$2 filter=$3
  [[ -s prepared/$name.mkv ]] && return
  "$PINNED" -hide_banner -nostdin -loglevel error -y -i "sources/$src" -an -vf "$filter" \
    -c:v ffv1 -level 3 -g 1 "prepared/$name.mkv"
}
prepare sintel sintel_trailer_2k_1080p24.y4m "format=yuv420p"
prepare driving Netflix_DrivingPOV_4096x2160_60fps_10bit_420.webm "crop=3840:2160,scale=1920:1080:flags=lanczos,fps=30,format=yuv420p"
prepare rollercoaster Netflix_RollerCoaster_4096x2160_60fps_10bit_420.webm "crop=3840:2160,scale=1920:1080:flags=lanczos,fps=30,format=yuv420p"
prepare bar Netflix_BarScene_4096x2160_60fps_10bit_420.webm "crop=3840:2160,scale=1920:1080:flags=lanczos,fps=30,format=yuv420p"

rate_of() { case $1 in sintel) echo "24 1" ;; *) echo "30 1" ;; esac; }
seconds_of() { case $1 in sintel) echo 52 ;; *) echo 20 ;; esac; }

# rung height bitrate h264-level hevc-level
LADDER="1080p 1080 5000000 40 40
720p 720 3000000 31 31
480p 480 1500000 30 30
360p 360 800000 30 21"

encode_and_score() {
  local src=$1 encoder=$2 rung=$3 height=$4 bitrate=$5 level=$6 tag=$7
  local rate seconds segments out
  read -r num den <<<"$(rate_of "$src")"
  seconds=$(seconds_of "$src")
  segments=$(((seconds + 5) / 6))
  out=out/$src-$encoder-$rung-$tag
  # 854x480 at 30 fps is 48,600 macroblocks a second, past H.264 level 3.0's 40,500, so the
  # server's H264Level sends 3.1 there; at 24 fps 3.0 fits.
  if [[ $encoder != hevc_nvenc && $rung == 480p && $num -ge 30 ]]; then level=31; fi
  mapfile -t cmd < <("$JAVA_HOME/bin/java" -cp "$CP" com.streamarr.transcode.engine.PrintWorkerCommand \
    "$encoder" "$height" "$bitrate" "$level" "$num" "$den" "$segments" "prepared/$src.mkv")
  # Keep the worker's first output; write it to a file instead of the pipe, and drop the framecrc.
  local args=() i
  for ((i = 1; i < ${#cmd[@]}; i++)); do
    if [[ ${cmd[i]} == pipe:1 ]]; then args+=("$out.mp4"); break; fi
    args+=("${cmd[i]}")
  done
  local start end
  start=$(date +%s.%N)
  "$PINNED" -hide_banner -nostdin -loglevel error "${args[@]}"
  end=$(date +%s.%N)
  local size
  size=$(stat -c %s "$out.mp4")
  "$VMAF_FFMPEG" -hide_banner -nostdin -loglevel error -i "$out.mp4" -i "prepared/$src.mkv" -lavfi \
    "[0:v]setpts=PTS-STARTPTS,scale=1920:1080:flags=bicubic,format=yuv420p[d];[1:v]setpts=PTS-STARTPTS[r];[d][r]libvmaf=model=version=vmaf_v0.6.1:n_threads=24:log_fmt=json:log_path=$out.vmaf.json" \
    -f null -
  local vmaf
  vmaf=$(python3 -c "import json,sys;d=json.load(open('$out.vmaf.json'));print(round(d['pooled_metrics']['vmaf']['mean'],3))")
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$src" "$encoder" "$rung" "$tag" "$bitrate" \
    "$((size * 8 / seconds))" "$vmaf" "$(python3 -c "print(round(($end-$start),2))")" | tee -a "$RESULTS.tmp"
}

for src in ${SOURCES:-sintel driving rollercoaster bar}; do
  while read -r rung height bitrate h264level hevclevel; do
    encode_and_score "$src" libx264 "$rung" "$height" "$bitrate" "$h264level" x1.00
    encode_and_score "$src" h264_nvenc "$rung" "$height" "$bitrate" "$h264level" x1.00
    encode_and_score "$src" hevc_nvenc "$rung" "$height" "$bitrate" "$hevclevel" x1.00
  done <<<"$LADDER"
done
mv "$RESULTS.tmp" "$RESULTS"
echo finished
