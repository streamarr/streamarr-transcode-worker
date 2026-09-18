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
  local url="$1"
  local destination="$2"
  local arguments=(
    --fail
    --location
    --proto '=https'
    --proto-redir '=https'
    --retry 3
    --silent
    --show-error
    --header 'Accept: application/vnd.github+json'
    --header 'X-GitHub-Api-Version: 2022-11-28'
    --user-agent 'streamarr-ffmpeg-lock-updater'
  )
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    arguments+=(--header "Authorization: Bearer ${GITHUB_TOKEN}")
  fi
  ffmpeg_curl "${arguments[@]}" "${url}" --output "${destination}"
}
