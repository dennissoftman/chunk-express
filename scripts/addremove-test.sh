#!/usr/bin/env bash
# Add to and remove from an existing world (docs/RELEASE_CRITERIA.md B7):
#  1. vanilla makes a world (1,500 blocks of ice road)
#  2. the same world reopened with Chunk Express, ridden further into new terrain (3,000 blocks)
#  3. Chunk Express removed, the world reopened in vanilla and ridden again (3,500 blocks)
# Every region file is checked offline after each step; the logs must show no chunk errors.
set -u
cd "$(dirname "$0")/.."
OUT=build/addremove-test
mkdir -p "$OUT"
WORLD=run/bench/saves/chunkexpress_iceroad
ERR='failed to (load|read) chunk|is allocated, but stream is missing|truncated|invalid sector|missing level data|wrong location|exception loading chunk|failed to load entities|Chunk Express: .*(could not be read|is damaged|discarded|no "Status" tag)'
fail=0
step() { # name, task, length, resume
    ./gradlew "$2" -Plength=$3 -Presume=$4 --console=plain > "$OUT/$1.log" 2>&1
    done=$(grep -c 'Ice road done' "$OUT/$1.log"); errs=$(grep -c -i -E "$ERR" "$OUT/$1.log")
    ./gradlew regionCheck -Pworld="$WORLD" --console=plain -q > "$OUT/$1-check.log" 2>&1; check=$?
    echo "  $1: ride done $done, chunk errors $errs, region check $(grep -E 'no problems found|problems:' "$OUT/$1-check.log" | head -1)"
    [ "$done" -ge 1 ] && [ "$errs" -eq 0 ] && [ $check -eq 0 ] || fail=1
}
step 1-vanilla runIceRoad 1500 false
step 2-with-chunkexpress runIceRoadChunkExpress 3000 true
step 3-removed runIceRoad 3500 true
[ $fail -eq 0 ] && echo PASS || { echo "FAIL (logs in $OUT)"; exit 1; }
