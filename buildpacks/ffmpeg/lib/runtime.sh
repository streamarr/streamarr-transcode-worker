#!/bin/bash

ffmpeg_runtime_architecture() {
  case "$1" in
    amd64 | x86_64) printf amd64 ;;
    arm64 | aarch64) printf arm64 ;;
    *)
      echo "Unsupported FFmpeg target architecture: $1" >&2
      return 1
      ;;
  esac
}

# Downloads the locked archive, verifies its checksum and extracts the named binaries.
ffmpeg_runtime_extract() (
  set -euo pipefail
  runtime_lock_file="$1"
  runtime_architecture="$2"
  runtime_destination="$3"
  shift 3
  runtime_release="$(ffmpeg_lock_value "${runtime_lock_file}" release)"
  runtime_asset="$(ffmpeg_lock_value "${runtime_lock_file}" "${runtime_architecture}_asset")"
  runtime_checksum="$(ffmpeg_lock_value "${runtime_lock_file}" "${runtime_architecture}_sha256")"
  runtime_download_dir="$(mktemp -d)"
  trap 'rm -rf "${runtime_download_dir}"' EXIT
  runtime_archive="${runtime_download_dir}/${runtime_asset}"

  ffmpeg_curl \
    --fail \
    --location \
    --proto '=https' \
    --proto-redir '=https' \
    --retry 3 \
    --silent \
    --show-error \
    "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/${runtime_release}/${runtime_asset}" \
    --output "${runtime_archive}"
  printf '%s  %s\n' "${runtime_checksum}" "${runtime_archive}" | ffmpeg_sha256_check --check --strict

  tar \
    --extract \
    --xz \
    --file "${runtime_archive}" \
    --directory "${runtime_destination}" \
    "$@"
)

# The mp4 muxer options the worker needs to write fragmented MP4 to a pipe, one per line.
ffmpeg_runtime_mp4_muxer_options() {
  printf '%s\n' -frag_duration cmaf delay_moov skip_trailer frag_keyframe frag_discont
}

# Fails, naming the first required option that FFmpeg's mp4 muxer help lacks.
ffmpeg_runtime_require_mp4_muxer_options() {
  local option
  while read -r option; do
    if ! grep -Fq -- "${option}" <<<"$1"; then
      echo "FFmpeg's mp4 muxer lacks ${option}" >&2
      return 1
    fi
  done < <(ffmpeg_runtime_mp4_muxer_options)
}

# Loader diagnostics can precede the banner; the reviewed capture starts at the banner.
ffmpeg_runtime_buildconf() {
  "$1" -buildconf 2>&1 | sed -n '/^ffmpeg version /,$p'
}
