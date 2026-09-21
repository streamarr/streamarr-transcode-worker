#!/bin/bash

# The digest an approval binds. It covers every reviewed input except the manifest itself, so a
# later edit to a notice, the inventory, a build configuration or the source offer cannot inherit
# the approval that bound the release.
ffmpeg_notices_digest() (
  set -euo pipefail
  cd "$1" || return 1
  local path
  local paths=()
  while IFS= read -r path; do
    paths+=("${path}")
  done < <(find SOURCE.txt notices -type f ! -path notices/manifest -print | LC_ALL=C sort)
  if (( ${#paths[@]} == 0 )); then
    echo "Expected reviewed FFmpeg notice inputs under $1" >&2
    return 1
  fi

  ffmpeg_sha256_check "${paths[@]}" | ffmpeg_sha256_check | cut -d ' ' -f 1
)

ffmpeg_notices_validate() {
  local lock_file="$1"
  local manifest="$2/notices/manifest"
  if [[ ! -f "${manifest}" || ! -r "${manifest}" || -L "${manifest}" ]]; then
    echo "Expected a regular readable FFmpeg notice manifest: ${manifest}" >&2
    return 1
  fi

  local line
  local count=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    ((count += 1))
  done <"${manifest}"
  if (( count != 5 )); then
    echo "FFmpeg notice manifest must contain exactly five entries" >&2
    return 1
  fi

  local key
  local expected
  local actual
  for key in release source_revision amd64_sha256 arm64_sha256; do
    expected="$(ffmpeg_lock_value "${lock_file}" "${key}")" || return
    actual="$(ffmpeg_lock_value "${manifest}" "${key}")" || return
    if [[ "${actual}" != "${expected}" ]]; then
      echo "FFmpeg notice inventory is stale (${key}); review sources and notices for the locked release" >&2
      return 1
    fi
  done
}

# Checked after the inventory's own validations, so a corrupt input is reported as itself.
ffmpeg_notices_bound() {
  local expected
  local actual
  expected="$(ffmpeg_notices_digest "$1")" || return
  actual="$(ffmpeg_lock_value "$1/notices/manifest" inventory_sha256)" || return
  if [[ "${actual}" != "${expected}" ]]; then
    echo "FFmpeg notice inventory changed since it was bound; bind the reviewed inventory with an approving review" >&2
    return 1
  fi
}
