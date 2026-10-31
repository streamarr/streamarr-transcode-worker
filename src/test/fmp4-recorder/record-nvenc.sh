#!/usr/bin/env bash
# Records how h264_nvenc and hevc_nvenc handle the worker's FFmpeg recipe, for
# streamarr/streamarr-transcode-worker#56. The real run needs an NVIDIA GPU host: Linux, an NVIDIA
# GPU, its driver and the NVIDIA Container Toolkit. Anywhere else the preflight names the missing
# piece and the script exits with status 3 without recording anything.
#
# For each NVENC encoder and each rung of the default ladder it encodes a synthetic source with the
# worker's own arguments, from the start and as a replacement attempt that seeks, under the GOP the
# worker passes today and under the longer GOP a keyframe-verified encoder gets. One more recording
# leaves a forced keyframe time out. nvenc-recordings.mjs then derives, from the recordings alone:
#   - whether every forced keyframe is a sync sample
#   - whether every forced keyframe is an IDR picture with no leading pictures (a closed GOP)
#   - whether the GOP count restarts at a forced keyframe
#   - the keyframes the encoder adds itself
#   - whether a replacement attempt's initialization segment is byte-identical to the first attempt's
#   - whether each initialization segment passes the codec, profile and level check (worker #25)
#
#   src/test/fmp4-recorder/record-nvenc.sh                     # record into src/test/resources/nvenc
#   src/test/fmp4-recorder/record-nvenc.sh --print-commands    # print the planned FFmpeg commands; needs no Docker
#   src/test/fmp4-recorder/record-nvenc.sh --help
#
# Safety: every FFmpeg run happens inside the pinned worker image, started through its launcher as
# the worker is, with no network, no capabilities, a read-only root filesystem and only the work
# directory mounted. The source is synthesized there with lavfi; no media file from the host is
# read. Everything the script writes stays inside the results directory: the work directory is a
# subdirectory of it, and it is removed on every exit. The whole run stops at --time-limit.
set -euo pipefail

SCRIPT_NAME=${0##*/}
HERE="$(cd "$(dirname "$0")" && pwd)"
REPOSITORY="$(cd "$HERE/../../.." && pwd)"

DEFAULT_IMAGE='streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:a11de4631250ce3bb120a734998ecb84bcd9d507a43a5aceb236e892908be481'
DEFAULT_TIME_LIMIT_S=1200
GPU_RUN_ARGS=(--gpus all -e "NVIDIA_DRIVER_CAPABILITIES=compute,video,utility")

# The job every recording runs: 6 s segments, the worker's default 250 ms fragmentation target, a
# 24000/1001 output frame rate and a 35 s source, which the server advertises as 6 media segments.
PERIOD=6
FRAME_RATE=24000/1001
FRAGMENTATION_TARGET_US=250000
SOURCE_SECONDS=35
MEDIA_SEGMENT_COUNT=6
# A replacement attempt for segment 3 seeks one period early, to 12 s, as the worker's does.
REPLACEMENT_START=3
# The forced keyframe time that the missed-keyframe recording leaves out.
SKIPPED_FORCED_TIME=18
# floor(P x fps), the GOP the worker passes an encoder that is not keyframe-verified, and
# ceil(P x fps) + 1, the GOP a keyframe-verified encoder gets (ADR 0037 as amended).
GOP_FLOORED=143
GOP_EXTENDED=145

# The default ladder (QualityLadderService in streamarr-server) for a 1920x1080 source at
# 23.976 fps: rung, height, bitrate, then the level and codec string of an H.264 High and an HEVC
# Main encode. The H.264 levels are the server's (H264Level). The server encodes no HEVC yet, so the
# HEVC levels are the smallest Main tier levels that fit, written as libx265 writes them.
LADDER="1080p 1080 5000000 40 avc1.640028 40 hvc1.1.6.L120.90
720p 720 3000000 31 avc1.64001F 31 hvc1.1.6.L93.90
480p 480 1500000 30 avc1.64001E 30 hvc1.1.6.L90.90
360p 360 800000 30 avc1.64001E 21 hvc1.1.6.L63.90"
ALL_ENCODERS="h264_nvenc hevc_nvenc"
ALL_RUNGS="1080p 720p 480p 360p"

usage() {
  cat <<EOF
Usage: $SCRIPT_NAME [flags]

Records h264_nvenc and hevc_nvenc under the worker's FFmpeg recipe on an NVIDIA GPU host and
writes recordings.json and one initialization segment per recording into the results directory.
Commit them to src/test/resources/nvenc; CI then derives every verdict again without FFmpeg.

Needs: Linux, an NVIDIA GPU with its driver, the NVIDIA Container Toolkit, Docker, and Node
$(node_major_wanted) (the major in buildpacks/ffmpeg/.nvmrc). Without them the preflight names the
missing piece and exits with status 3.

Flags:
  --results-dir DIR    Where the results go (default src/test/resources/nvenc). Old results there
                       are replaced only after a complete recording.
  --image IMAGE        Worker image (default the pinned image; WORKER_IMAGE also sets it).
  --encoders LIST      h264_nvenc,hevc_nvenc (default both).
  --rungs LIST         1080p,720p,480p,360p (default all).
  --time-limit SECONDS Stop the whole run after this long (default $DEFAULT_TIME_LIMIT_S).
  --print-commands     Print every planned recording's job and FFmpeg command, then exit.
                       Needs no Docker and no GPU.
  -h, --help           Show this help.

Exit status: 0 recorded, 2 usage error, 3 preflight failed (nothing recorded), 4 a recording
failed, 124 time limit reached.
EOF
}

die() {
  printf '%s: error: %s\n' "$SCRIPT_NAME" "$*" >&2
  exit 2
}

node_major_wanted() {
  local version
  version=$(cat "$REPOSITORY/buildpacks/ffmpeg/.nvmrc")
  printf '%s' "${version%%.*}"
}

IMAGE="${WORKER_IMAGE:-$DEFAULT_IMAGE}"
RESULTS_DIR="$REPOSITORY/src/test/resources/nvenc"
ENCODERS="$ALL_ENCODERS"
RUNGS="$ALL_RUNGS"
TIME_LIMIT_S=$DEFAULT_TIME_LIMIT_S
PRINT_COMMANDS=0

need_value() {
  if [[ $# -lt 2 || -z "$2" ]]; then
    die "$1 needs a value"
  fi
}

# only_from <allowed> <list>: the comma-separated list as words, each one of the allowed words.
only_from() {
  local word words=""
  for word in ${2//,/ }; do
    case " $1 " in
      *" $word "*) words="${words:+$words }$word" ;;
      *) die "unknown value $word (choose from: $1)" ;;
    esac
  done
  [[ -n "$words" ]] || die "an empty list"
  printf '%s' "$words"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --results-dir) need_value "$@"; RESULTS_DIR=$2; shift ;;
    --image) need_value "$@"; IMAGE=$2; shift ;;
    --encoders) need_value "$@"; ENCODERS=$(only_from "$ALL_ENCODERS" "$2"); shift ;;
    --rungs) need_value "$@"; RUNGS=$(only_from "$ALL_RUNGS" "$2"); shift ;;
    --time-limit)
      need_value "$@"
      [[ "$2" =~ ^[0-9]+$ && "$2" -ge 60 ]] || die "--time-limit needs whole seconds, at least 60"
      TIME_LIMIT_S=$2
      shift
      ;;
    --print-commands) PRINT_COMMANDS=1 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown flag $1 (see --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------------------------------------
# The plan: the job and the worker's FFmpeg command of every recording
# ---------------------------------------------------------------------------------------------

# rung_field <rung> <field number>: one field of the rung's LADDER line.
rung_field() {
  printf '%s\n' "$LADDER" | awk -v rung="$1" -v field="$2" '$1 == rung { print $field }'
}

# forced_times <first segment> [skipped time]: the forced keyframe times the worker passes, every
# boundary from the attempt's first segment to the last advertised one, without the skipped time.
forced_times() {
  local segment times=""
  for ((segment = $1; segment < MEDIA_SEGMENT_COUNT; segment++)); do
    if [[ "$((segment * PERIOD))" != "${2:-}" ]]; then
      times="${times:+$times,}$((segment * PERIOD))"
    fi
  done
  printf '%s' "$times"
}

# The worker's level argument for NVENC: the level by name, such as 4.0.
level_name() {
  printf '%s.%s' "$(($1 / 10))" "$(($1 % 10))"
}

# plan_recording <encoder> <rung> <variant>: sets NAME, META (the job, one key=value per line) and
# COMMAND (the worker's FFmpeg command for that job, with the quiet, non-interactive logging that
# only the recording adds). The variants: first and replacement follow the worker's recipe;
# extended-first, extended-replacement and extended-missed take the extended GOP instead, and
# extended-missed also leaves SKIPPED_FORCED_TIME out of the forced keyframe times.
plan_recording() {
  local encoder=$1 rung=$2 variant=$3
  local height bitrate level codec_string tag profile tier=() start=0 gop=$GOP_FLOORED recipe=worker
  local skipped="" replaces="" seek=()
  height=$(rung_field "$rung" 2)
  bitrate=$(rung_field "$rung" 3)
  case "$encoder" in
    h264_nvenc) level=$(rung_field "$rung" 4); codec_string=$(rung_field "$rung" 5); tag=avc1; profile=high ;;
    hevc_nvenc) level=$(rung_field "$rung" 6); codec_string=$(rung_field "$rung" 7); tag=hvc1; profile=main; tier=(-tier:v main) ;;
  esac
  case "$variant" in
    extended-*) gop=$GOP_EXTENDED; recipe=extended ;;
  esac
  case "$variant" in
    *replacement) start=$REPLACEMENT_START; replaces="$encoder-$rung-${variant%replacement}first" ;;
    extended-missed) skipped=$SKIPPED_FORCED_TIME ;;
  esac
  if ((start > 0)); then
    seek=(-ss "$(((start - 1) * PERIOD))")
  fi
  local times
  times=$(forced_times "$start" "$skipped")
  NAME="$encoder-$rung-$variant"
  META="encoder=$encoder
rung=$rung
recipe=$recipe
height=$height
bitrate=$bitrate
level=$level
tier=${tier[1]:-}
expectedCodecString=$codec_string
startSequenceNumber=$start
mediaSegmentCount=$MEDIA_SEGMENT_COUNT
gopFrames=$gop
forcedKeyframeTimes=$times
skippedForcedTime=$skipped
replaces=$replaces"
  COMMAND=(ffmpeg -hide_banner -nostdin -loglevel error -y ${seek[@]+"${seek[@]}"} -i /work/source.mp4
    -map 0:0 -map -0:s -map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled
    -start_at_zero -max_muxing_queue_size 128 -max_interleave_delta 10000000
    -c:v "$encoder" -tag:v "$tag" -profile:v "$profile" -level:v "$(level_name "$level")" ${tier[@]+"${tier[@]}"}
    -vf "scale=-2:$height:out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709,format=yuv420p"
    -b:v "$bitrate" -maxrate "$bitrate" -bufsize "$((bitrate * 2))"
    -r:v:0 "$FRAME_RATE" -forced-idr 1 -force_key_frames:0 "$times" -g:v:0 "$gop" -keyint_min:v:0 "$gop"
    -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont
    -frag_duration "$FRAGMENTATION_TARGET_US" pipe:1)
}

VARIANTS="first replacement extended-first extended-replacement extended-missed"

# for_each_recording <function>: calls the function after plan_recording for every recording.
for_each_recording() {
  local encoder rung variant
  for encoder in $ENCODERS; do
    for rung in $RUNGS; do
      for variant in $VARIANTS; do
        plan_recording "$encoder" "$rung" "$variant"
        "$1"
      done
    done
  done
}

print_recording() {
  printf '# %s\n%s\n' "$NAME" "$META" | sed '2,$s/^/# /'
  printf '%s\n' "${COMMAND[@]}"
  printf '\n'
}

if ((PRINT_COMMANDS)); then
  for_each_recording print_recording
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# Preflight: names the missing piece and exits with status 3
# ---------------------------------------------------------------------------------------------

skip() {
  printf '%s: skipped, nothing recorded: %s\n' "$SCRIPT_NAME" "$*" >&2
  exit 3
}

# gpu_hint <output>: the piece most likely missing, from a failed GPU container's output.
gpu_hint() {
  case "$1" in
    *"could not select device driver"* | *"unknown or invalid runtime"* | *"nvidia-container-cli"* | *"unresolvable CDI devices"* | *"GPU vendor"*)
      printf 'the NVIDIA Container Toolkit: Docker cannot give a container the GPU (install it, run nvidia-ctk runtime configure --runtime=docker, restart Docker)' ;;
    *"driver not loaded"* | *"couldn't communicate with the NVIDIA driver"* | *"NVIDIA-SMI has failed"*)
      printf 'the host NVIDIA driver: it is not loaded (run nvidia-smi on the host)' ;;
    *"executable file not found"* | *"nvidia-smi: not found"*)
      printf 'the utility driver capability: the toolkit mounted no nvidia-smi' ;;
    *libnvidia-encode* | *"Cannot load nvEncodeAPI"*)
      printf 'the video driver capability: the toolkit mounted no NVENC library (libnvidia-encode)' ;;
    *libcuda* | *"Cannot load cuda"*)
      printf 'the compute driver capability: the toolkit mounted no libcuda' ;;
    *"required nvenc API version"* | *"minimum required Nvidia driver"*)
      printf 'a newer host NVIDIA driver: this FFmpeg needs a newer NVENC API than the driver has' ;;
    *OpenEncodeSessionEx* | *"out of memory"*)
      printf 'a free NVENC session: the GPU refused one, so another process may hold every session the driver allows' ;;
    *"No capable devices"* | *"No NVENC capable devices"*)
      printf 'an NVENC-capable GPU for this codec' ;;
    *) printf 'unknown; see the output above' ;;
  esac
}

gpu_failed() {
  printf '%s: %s failed. Last lines of its output:\n' "$SCRIPT_NAME" "$1" >&2
  printf '%s\n' "$2" | tail -n 5 | sed 's/^/    /' >&2
  skip "the likely missing piece is $(gpu_hint "$2")"
}

[[ "$(uname -s)" == Linux ]] || skip "this host runs $(uname -s); the recordings need Linux with an NVIDIA GPU"
command -v docker >/dev/null 2>&1 || skip "Docker is not on PATH"
docker info >/dev/null 2>&1 || skip "cannot reach the Docker daemon (try a user in the docker group)"
command -v node >/dev/null 2>&1 || skip "Node $(node_major_wanted) is not on PATH (the analysis runs on the host)"
NODE_MAJOR=$(node --version | sed 's/^v//; s/\..*//')
[[ "$NODE_MAJOR" == "$(node_major_wanted)" ]] || skip "Node $(node_major_wanted) is needed, and node is version $NODE_MAJOR"

RUN_ID="nvenc-$(date +%Y%m%d%H%M%S)-$$"
# Every container: no network, no capabilities, no privilege gain, a read-only root filesystem
# with a small /tmp, the GPU, the host user's ids so the files it writes stay the user's, and a
# label so that cleanup finds exactly this run's containers.
DOCKER_RUN=(run --rm --network none --cap-drop ALL --security-opt no-new-privileges
  --read-only --tmpfs "/tmp:rw,noexec,nosuid,size=64m" --label "streamarr.nvenc-recorder=$RUN_ID"
  --user "$(id -u):$(id -g)" "${GPU_RUN_ARGS[@]}")

docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE" >/dev/null ||
  skip "cannot pull $IMAGE"

if ! GPU_INFO=$(docker "${DOCKER_RUN[@]}" --entrypoint nvidia-smi "$IMAGE" \
  --query-gpu=driver_version,compute_cap --format=csv,noheader 2>&1); then
  gpu_failed "nvidia-smi in a container with the GPU" "$GPU_INFO"
fi
GPU_INFO=$(printf '%s\n' "$GPU_INFO" | head -n 1)
for encoder in $ENCODERS; do
  if ! out=$(docker "${DOCKER_RUN[@]}" --entrypoint /cnb/lifecycle/launcher "$IMAGE" \
    ffmpeg -nostdin -hide_banner -v error -f lavfi -i color=size=320x240:rate=24 -frames:v 1 \
    -c:v "$encoder" -f null - 2>&1); then
    gpu_failed "a one-frame $encoder encode" "$out"
  fi
done
printf 'preflight: GPU driver %s, compute capability %s; %s encode one frame\n' \
  "${GPU_INFO%%,*}" "${GPU_INFO#*, }" "${ENCODERS// /, }"

# ---------------------------------------------------------------------------------------------
# Work directory, time limit and cleanup
# ---------------------------------------------------------------------------------------------

mkdir -p "$RESULTS_DIR"
RESULTS_DIR="$(cd "$RESULTS_DIR" && pwd)"
WORK="$RESULTS_DIR/.$RUN_ID"
mkdir "$WORK" "$WORK/out" "$WORK/logs"
CHILD=""
WATCHDOG=""

cleanup() {
  local status=$?
  trap '' INT TERM HUP
  set +e
  if [[ -n "$WATCHDOG" ]]; then
    pkill -P "$WATCHDOG" 2>/dev/null
    kill "$WATCHDOG" 2>/dev/null
  fi
  if [[ -n "$CHILD" ]]; then
    kill "$CHILD" 2>/dev/null
  fi
  docker ps -q --filter "label=streamarr.nvenc-recorder=$RUN_ID" | xargs docker rm -f >/dev/null 2>&1
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 124' TERM
trap 'exit 129' HUP

# The watchdog ends the run at the time limit; each long step runs as a background child that the
# shell waits for, so the TERM trap runs at once instead of after the child.
# Its own messages go to /dev/null, so that stopping it after a complete run prints nothing.
exec 3>&2
(sleep "$TIME_LIMIT_S" && printf '%s: the time limit of %s s is reached\n' "$SCRIPT_NAME" "$TIME_LIMIT_S" >&3 &&
  kill -TERM $$) 2>/dev/null &
WATCHDOG=$!

run_child() {
  "$@" &
  CHILD=$!
  wait "$CHILD"
  local status=$?
  CHILD=""
  return "$status"
}

write_recording() {
  printf '%s\n' "${COMMAND[@]}" >"$WORK/out/$NAME.args"
  printf '%s\n' "$META" >"$WORK/out/$NAME.meta"
}
for_each_recording write_recording

cat >"$WORK/record.sh" <<CONTAINER
set -euo pipefail
cd /work
ffmpeg -version | head -n 1 > ffmpeg-version
# The source: 1920x1080 testsrc2 at $FRAME_RATE, $SOURCE_SECONDS s, cut to SMPTE bars from 3.5 s to
# 4.5 s and from 27.5 s to 28.5 s, so that the encoder sees scene cuts inside segments 0 and 4. No
# cut falls between 12 s and the extended GOP after it, where the missed-keyframe recording looks for
# the keyframe the GOP places.
ffmpeg -hide_banner -nostdin -loglevel error -y \\
  -f lavfi -i testsrc2=size=1920x1080:rate=$FRAME_RATE:duration=$SOURCE_SECONDS \\
  -f lavfi -i smptehdbars=size=1920x1080:rate=$FRAME_RATE:duration=$SOURCE_SECONDS \\
  -filter_complex "[0:v][1:v]overlay=enable='gte(t,3.5)*lt(t,4.5)+gte(t,27.5)*lt(t,28.5)',format=yuv420p[v]" -map "[v]" \\
  -c:v libx264 -preset veryfast -crf 18 -g 48 source.mp4
duration=\$(ffprobe -v error -show_entries format=duration -of csv=p=0 source.mp4)
millis=\$(awk -v seconds="\$duration" 'BEGIN { printf "%d", seconds * 1000 }')
count=\$(((millis + $PERIOD * 1000 - 1) / ($PERIOD * 1000)))
if [ "\$count" != $MEDIA_SEGMENT_COUNT ]; then
  echo "the source lasts \$duration s, which the server advertises as \$count media segments, not $MEDIA_SEGMENT_COUNT" >&2
  exit 5
fi
for args in out/*.args; do
  name=\$(basename "\$args" .args)
  mapfile -t command < "\$args"
  "\${command[@]}" > "out/\$name.fmp4" 2> "logs/\$name.log" || { echo "\$name failed:" >&2; tail -n 5 "logs/\$name.log" >&2; exit 4; }
done
CONTAINER

set -- "$WORK"/out/*.args
printf 'recording %s encodes into %s\n' "$#" "$RESULTS_DIR"
# The launcher prints its own lines on standard output; FFmpeg's pipe:1 goes to files inside the
# container script, so that noise never reaches a recording.
run_child docker "${DOCKER_RUN[@]}" -v "$WORK:/work" --entrypoint /cnb/lifecycle/launcher "$IMAGE" \
  bash /work/record.sh >"$WORK/launcher.log" 2>&1 || {
  cat "$WORK/launcher.log" >&2
  exit 4
}

{
  printf 'image=%s\n' "$IMAGE"
  printf 'ffmpegVersion=%s\n' "$(cat "$WORK/ffmpeg-version")"
  printf 'driverVersion=%s\n' "${GPU_INFO%%,*}"
  printf 'computeCapability=%s\n' "${GPU_INFO#*, }"
} >"$WORK/environment"

mkdir "$WORK/results"
run_child node "$HERE/nvenc-recordings.mjs" analyze "$WORK" "$WORK/results"
# The old results go only once every recording and its analysis have succeeded.
find "$RESULTS_DIR" -maxdepth 1 -type f \( -name '*.init.mp4' -o -name 'recordings.json' \) -delete
mv "$WORK/results/"* "$RESULTS_DIR/"
printf 'wrote %s/recordings.json; check it with: node src/test/fmp4-recorder/nvenc-recordings.mjs check %s\n' \
  "$RESULTS_DIR" "$RESULTS_DIR"
