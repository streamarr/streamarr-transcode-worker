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

# Loader diagnostics can precede the banner; the reviewed capture starts at the banner.
ffmpeg_runtime_buildconf() {
  "$1" -buildconf 2>&1 | sed -n '/^ffmpeg version /,$p'
}
