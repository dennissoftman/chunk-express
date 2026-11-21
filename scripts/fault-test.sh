#!/usr/bin/env bash
# Failure inside an offloaded stage (docs/RELEASE_CRITERIA.md B6): one chunk's surface stage throws on
# a worker thread (debug switch chunkexpress.debug.failStage). Expected: vanilla's crash report and the
# game stops; never a hang. Fails if the game is still running after 5 minutes.
set -u
cd "$(dirname "$0")/.."
LOG=build/fault-test.log
REPORTS=run/bench/crash-reports
mkdir -p build
before=$(ls "$REPORTS" 2>/dev/null | wc -l)
# Chunk (1001, 1001) is inside the first pre-generation square (centre 1000, 1000).
timeout 300 ./gradlew runIceRoadChunkExpress -Poptimizations=on "-Ppregen=minecraft:overworld@3" \
    "-Pjvm=-Dchunkexpress.debug.failStage=surface@1001,1001" --console=plain > "$LOG" 2>&1
code=$?
after=$(ls "$REPORTS" 2>/dev/null | wc -l)
newest=$(ls -t "$REPORTS" 2>/dev/null | head -1)
if [ $code -eq 124 ]; then echo "FAIL: still running after 5 minutes (hang); log: $LOG"; exit 1; fi
if [ "$after" -gt "$before" ] && grep -q "Chunk Express fault injection" "$REPORTS/$newest"; then
    echo "  crash report: $REPORTS/$newest"
    grep -m1 -E '^Description:' "$REPORTS/$newest" | sed 's/^/  /'
    echo PASS
else
    echo "FAIL: no crash report naming the injected fault (exit $code); log: $LOG"; exit 1
fi
