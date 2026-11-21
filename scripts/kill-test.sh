#!/usr/bin/env bash
# Hard-kill test: does a world survive the game being killed mid-generation?
#
#   scripts/kill-test.sh [run task] [kill after N blocks]
#   scripts/kill-test.sh runIceRoadChunkExpress 2000     # with Chunk Express (default)
#   scripts/kill-test.sh runIceRoad 2000                 # vanilla control
#   WITH=a.jar,b.jar scripts/kill-test.sh ...            # with other mods loaded (-Pwith)
#
# 1. Starts an ice-road ride in a new world (thousands of chunks generated and saved as they unload).
# 2. Once the ride passes N blocks (plus a random 0-3 s), kills the game process outright:
#    taskkill /F on Windows, kill -9 elsewhere. No shutdown hooks, no save - like a JVM crash.
# 3. Checks every region file offline (./gradlew regionCheck): headers, every entry decodes,
#    positions match, a status is present.
# 4. Reopens the same world and rides the road again, through the terrain that was being written
#    when the game died, then searches that run's log for any chunk loading error.
#
# What this does not cover: an operating-system crash or power loss (the OS still flushes what it
# was given). That needs a virtual machine that can be cut off; see docs/CRASH_TESTS.md.
#
# Exit code 0 only if the region check and the reopened ride are both clean.
set -u
cd "$(dirname "$0")/.."

TASK=${1:-runIceRoadChunkExpress}
WITH_ARG=()
if [ -n "${WITH:-}" ]; then WITH_ARG=("-Pwith=$WITH"); fi
KILL_AT=${2:-2000}
OUT=build/kill-test
mkdir -p "$OUT"
STAMP=$(date +%Y%m%d-%H%M%S)
TAG="$STAMP-$TASK"
RIDE_LOG="$OUT/$TAG-1-ride.log"
CHECK_LOG="$OUT/$TAG-2-regioncheck.log"
REOPEN_LOG="$OUT/$TAG-3-reopen.log"
SUMMARY="$OUT/$TAG-summary.txt"
PID_FILE=run/bench/chunkexpress-bench/game.pid
WORLD=run/bench/saves/chunkexpress_iceroad

rm -f "$PID_FILE"
echo "[$TAG] ride started; will kill after $KILL_AT blocks"
./gradlew "$TASK" -Plength=5000 "${WITH_ARG[@]}" --console=plain > "$RIDE_LOG" 2>&1 &
GRADLE_PID=$!

deadline=$((SECONDS + 900))
while :; do
    blocks=$(grep -o 'Ice road: [0-9]* blocks' "$RIDE_LOG" 2>/dev/null | tail -1 | grep -o '[0-9]*' | head -1)
    if [ -n "$blocks" ] && [ "$blocks" -ge "$KILL_AT" ]; then break; fi
    if grep -q 'Ice road done' "$RIDE_LOG" 2>/dev/null; then echo "ride finished before the kill point"; exit 2; fi
    if [ $SECONDS -gt $deadline ]; then echo "timed out waiting for the ride"; exit 2; fi
    sleep 1
done
sleep $((RANDOM % 4))

GAME_PID=$(cat "$PID_FILE")
KILLED_AT=$(grep -o 'Ice road: [0-9]* blocks' "$RIDE_LOG" | tail -1)
if command -v taskkill >/dev/null 2>&1; then
    taskkill //F //PID "$GAME_PID" > /dev/null
else
    kill -9 "$GAME_PID"
fi
echo "[$TAG] killed game process $GAME_PID (last progress: $KILLED_AT)"
wait "$GRADLE_PID" 2>/dev/null

./gradlew regionCheck -Pworld="$WORLD" --console=plain -q > "$CHECK_LOG" 2>&1
CHECK_EXIT=$?
echo "[$TAG] region check: $(grep -E 'region files|problems' "$CHECK_LOG" | tr '\n' ' ')"

./gradlew "$TASK" -Plength=5000 -Presume=true "${WITH_ARG[@]}" --console=plain > "$REOPEN_LOG" 2>&1
RIDE_DONE=$(grep -c 'Ice road done' "$REOPEN_LOG")
# Vanilla's and Chunk Express' messages for chunks or region data that could not be read.
ERRORS=$(grep -E -i 'failed to (load|read) chunk|is allocated, but stream is missing|header is truncated|stream is truncated|has an? invalid sector|has truncated header|missing level data|chunk file at .* is in the wrong location|exception loading chunk|failed to load entities|Chunk Express: .*(could not be read|is damaged|discarded|no "Status" tag)' "$REOPEN_LOG")
ERROR_COUNT=$(printf '%s' "$ERRORS" | grep -c . )

{
    echo "Hard-kill test $TAG"
    echo "  killed at: $KILLED_AT (game process $GAME_PID)"
    echo "  region check: $(grep -E 'region files' "$CHECK_LOG")"
    echo "  region check result: $(grep -E 'no problems found|problems:' "$CHECK_LOG")"
    echo "  reopened world, ride completed: $([ "$RIDE_DONE" -gt 0 ] && echo yes || echo NO)"
    echo "  chunk/region errors while reopening: $ERROR_COUNT"
    if [ "$ERROR_COUNT" -gt 0 ]; then printf '%s\n' "$ERRORS" | head -20 | sed 's/^/    /'; fi
} | tee "$SUMMARY"

if [ "$CHECK_EXIT" -eq 0 ] && [ "$RIDE_DONE" -gt 0 ] && [ "$ERROR_COUNT" -eq 0 ]; then
    echo "PASS"; exit 0
else
    echo "FAIL (logs in $OUT/$TAG-*)"; exit 1
fi
