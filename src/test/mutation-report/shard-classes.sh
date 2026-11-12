#!/usr/bin/env bash
# shard-classes.sh <shard> <shards>
# Prints PIT's targetClasses for one shard of a mutation run split into <shards>: every
# <shards>-th production class in name order, from the <shard>-th (counted from 0), each with its
# nested classes. Run from the repository root; together the shards name every class once.
set -euo pipefail

usage() {
  echo "usage: shard-classes.sh <shard> <shards>" >&2
  exit 2
}

(( $# == 2 )) || usage
[[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[1-9][0-9]*$ ]] || usage
(( $1 < $2 )) || usage

cd src/main/java
find . -name '*.java' | sed 's|^\./||' | LC_ALL=C sort \
  | awk -v shard="$1" -v shards="$2" '
      (NR - 1) % shards == shard {
        sub(/\.java$/, "")
        gsub("/", ".")
        printf "%s%s,%s$*", (listed++ ? "," : ""), $0, $0
      }
      END { print "" }'
