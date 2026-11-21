#!/usr/bin/env bash
# Several singleplayer worlds in one game session (docs/RELEASE_CRITERIA.md B9): Chunk Express must
# start fresh for each world (decisions, counters, status) and nothing may leak from one to the next.
set -u
cd "$(dirname "$0")/.."
LOG=build/sessions-test.log
mkdir -p build
./gradlew runIceRoadChunkExpress -Poptimizations=on -Psessions=3 "-Ppregen=minecraft:overworld@6;minecraft:the_nether@4" \
    --console=plain > "$LOG" 2>&1
fail=0
count() { grep -c -E "$1" "$LOG"; }
expect() { # description, actual, wanted
    if [ "$2" -eq "$3" ]; then echo "  ok   $1 ($2)"; else echo "  FAIL $1 ($2, expected $3)"; fail=1; fi
}
expect "worlds created" "$(count 'Ice road: world [0-9] of 3 in this session')" 3
expect "worlds fully generated" "$(count 'Pregen done: 81 chunks in minecraft:the_nether')" 3
expect "surface decision made afresh for each world's overworld" "$(count 'surface stage for minecraft:overworld runs on worker threads')" 3
expect "carvers decision made afresh for each world's nether" "$(count 'carvers stage for minecraft:the_nether runs on worker threads')" 3
expect "Chunk Express errors" "$(grep -E '/(ERROR|FATAL)\]' "$LOG" | grep -c -i -E 'chunkexpress|chunk express')" 0
expect "crash reports" "$(grep -c -E 'Preparing crash report' "$LOG")" 0
expect "session summaries (one per world)" "$(count 'Chunk Express session summary|\[summary\] Mixins')" 3
[ $fail -eq 0 ] && echo PASS || { echo "FAIL (log: $LOG)"; exit 1; }
