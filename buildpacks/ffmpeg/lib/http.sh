#!/bin/bash

ffmpeg_curl() {
  local attempt=0
  local status
  while true; do
    status=0
    curl "$@" || status=$?
    if (( status == 0 )); then
      return
    fi
    case "${status}" in
      5 | 6 | 7 | 18 | 35 | 52 | 55 | 56 | 92) ;;
      *) return "${status}" ;;
    esac
    ((attempt += 1))
    if (( attempt > 3 )); then
      return "${status}"
    fi
    sleep "${attempt}"
  done
}

ffmpeg_github_api_get() {
  ffmpeg_github_api_request 'application/vnd.github+json' "$@"
}

ffmpeg_github_api_request() {
  local media_type="$1"
  local url="$2"
  local destination="$3"
  local arguments=(
    --fail
    --location
    --proto '=https'
    --proto-redir '=https'
    --retry 3
    --silent
    --show-error
    --header "Accept: ${media_type}"
    --header 'X-GitHub-Api-Version: 2022-11-28'
    --user-agent 'streamarr-ffmpeg-lock-updater'
  )
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    arguments+=(--header "Authorization: Bearer ${GITHUB_TOKEN}")
  fi
  ffmpeg_curl "${arguments[@]}" "${url}" --output "${destination}"
}
