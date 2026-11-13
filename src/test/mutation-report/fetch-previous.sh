#!/usr/bin/env bash
# fetch-previous.sh <directory>
# Downloads the summary of the latest successful mutation run on main into <directory>. A night
# without one is reported without a comparison, so nothing here fails the run.
set -euo pipefail

directory="$1"

if ! run_id="$(gh run list --repo "$GITHUB_REPOSITORY" --workflow mutation-testing.yml --branch main \
  --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')"; then
  echo "Could not list earlier runs; reporting without a comparison"
  exit 0
fi

if [[ -z "$run_id" ]]; then
  echo "No earlier successful run on main; reporting without a comparison"
  exit 0
fi

if ! gh run download "$run_id" --repo "$GITHUB_REPOSITORY" --name mutation-summary --dir "$directory"; then
  echo "Run $run_id kept no summary; reporting without a comparison"
fi
