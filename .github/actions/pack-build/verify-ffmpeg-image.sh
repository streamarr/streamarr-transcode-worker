#!/bin/bash
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

image="${1:?Usage: verify-ffmpeg-image.sh <image> <version> <source> <revision> <contract>}"
expected_version="${2:?Usage: verify-ffmpeg-image.sh <image> <version> <source> <revision> <contract>}"
expected_source="${3:?Usage: verify-ffmpeg-image.sh <image> <version> <source> <revision> <contract>}"
expected_revision="${4:?Usage: verify-ffmpeg-image.sh <image> <version> <source> <revision> <contract>}"
expected_contract="${5:?Usage: verify-ffmpeg-image.sh <image> <version> <source> <revision> <contract>}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../../.." && pwd)"
lock_file="${repository_root}/buildpacks/ffmpeg/ffmpeg.lock"
# shellcheck source=../../../buildpacks/ffmpeg/lib/lock.sh
. "${repository_root}/buildpacks/ffmpeg/lib/lock.sh"
# shellcheck source=../../../buildpacks/ffmpeg/lib/runtime.sh
. "${repository_root}/buildpacks/ffmpeg/lib/runtime.sh"

ffmpeg_lock_validate "${lock_file}"
expected_ffmpeg_version="$(ffmpeg_lock_value "${lock_file}" version)"
expected_ffmpeg_version="${expected_ffmpeg_version%-*}-Jellyfin"

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  docker pull "${image}" >/dev/null
fi

verify_label() {
  local label="$1"
  local expected="$2"
  local actual
  actual="$(docker image inspect --format "{{ index .Config.Labels \"${label}\" }}" "${image}")"
  if [[ "${actual}" == "${expected}" ]]; then
    return
  fi

  echo "Expected ${label}=${expected} but found ${actual}" >&2
  exit 1
}

verify_label org.opencontainers.image.version "${expected_version}"
verify_label org.opencontainers.image.source "${expected_source}"
verify_label org.opencontainers.image.revision "${expected_revision}"
verify_label org.streamarr.contract.version "${expected_contract}"

EXPECTED_FFMPEG_VERSION="${expected_ffmpeg_version}" \
  REQUIRED_MP4_MUXER_OPTIONS="$(ffmpeg_runtime_mp4_muxer_options)" \
  docker run --rm --interactive --env EXPECTED_FFMPEG_VERSION --env REQUIRED_MP4_MUXER_OPTIONS \
  --entrypoint /cnb/lifecycle/launcher "${image}" \
  /bin/bash -euo pipefail -s <<'SCRIPT'
  ffmpeg="$(command -v ffmpeg)"
  ffprobe="$(command -v ffprobe)"

  version_output="$(${ffmpeg} -version 2>&1)"
  grep -F "ffmpeg version ${EXPECTED_FFMPEG_VERSION} " <<<"${version_output}" >/dev/null
  grep -F -- "--enable-gpl" <<<"${version_output}" >/dev/null
  if grep -F -- "--enable-nonfree" <<<"${version_output}"; then
    echo "FFmpeg must not include nonfree components" >&2
    exit 1
  fi

  mp4_muxer_help="$("${ffmpeg}" -hide_banner -h muxer=mp4 2>&1)"
  for option in ${REQUIRED_MP4_MUXER_OPTIONS}; do
    if ! grep -Fq -- "${option}" <<<"${mp4_muxer_help}"; then
      echo "FFmpeg's mp4 muxer lacks ${option}" >&2
      exit 1
    fi
  done

  # The worker reads fragmented MP4 from FFmpeg's standard output and forces its keyframes from a
  # list of media times.
  output_dir="$(mktemp -d)"
  "${ffmpeg}" \
    -nostdin \
    -hide_banner \
    -loglevel error \
    -f lavfi \
    -i testsrc2=size=320x180:rate=30 \
    -f lavfi \
    -i sine=frequency=1000:sample_rate=48000 \
    -t 3 \
    -c:v libx264 \
    -pix_fmt yuv420p \
    -force_key_frames:0 0,1,2 \
    -c:a aac \
    -f mp4 \
    -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont \
    -frag_duration 1000000 \
    pipe:1 >"${output_dir}/fragmented.mp4"

  grep -aF moof "${output_dir}/fragmented.mp4" >/dev/null
  "${ffprobe}" \
    -v error \
    -show_entries format=format_name \
    -of default=noprint_wrappers=1 \
    "${output_dir}/fragmented.mp4" \
    | grep -F "format_name=mov,mp4"

  "${ffmpeg}" -nostdin -hide_banner -loglevel error \
    -f lavfi -i testsrc2=size=160x90:rate=10 -t 1 \
    -c:v libsvtav1 -preset 9 -svtav1-params lp=2 \
    "${output_dir}/av1.mp4"
  "${ffprobe}" -v error -select_streams v:0 \
    -show_entries stream=codec_name -of default=noprint_wrappers=1 \
    "${output_dir}/av1.mp4" | grep -Fx 'codec_name=av1'
SCRIPT

docker run --rm --entrypoint /cnb/lifecycle/launcher "${image}" \
  ffmpeg -version >/dev/null
