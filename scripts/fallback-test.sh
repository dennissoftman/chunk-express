#!/usr/bin/env bash
# Fallback in game (docs/RELEASE_CRITERIA.md B5): mod-added types and a modded generator keep the
# surface and carvers stages on the worldgen lane, per dimension, with the reason naming them.
# Uses the test-only fixtures in src/gametest (FallbackFixtures and the fixture dimensions).
set -u
cd "$(dirname "$0")/.."
LOG=build/fallback-test.log
mkdir -p build
./gradlew runIceRoadChunkExpress -Poptimizations=on \
    "-Ppregen=chunkexpress:fixture_rules@3;chunkexpress:fixture_generator@3;minecraft:the_nether@3" \
    --console=plain > "$LOG" 2>&1
fail=0
check() { # description, pattern
    if grep -q -E "$2" "$LOG"; then echo "  ok   $1"; else echo "  FAIL $1"; fail=1; fi
}
check "fixture_rules surface stays vanilla, names test_rule" "surface stage for chunkexpress:fixture_rules runs on the worldgen lane.*chunkexpress:test_rule"
check "fixture_rules carvers stay vanilla, name test_carver" "carvers stage for chunkexpress:fixture_rules runs on the worldgen lane.*chunkexpress:test_carver"
check "fixture_generator surface stays vanilla, names the generator" "surface stage for chunkexpress:fixture_generator runs on the worldgen lane.*TestGenerator"
check "fixture_generator carvers stay vanilla, name the generator" "carvers stage for chunkexpress:fixture_generator runs on the worldgen lane.*TestGenerator"
check "the Nether is unaffected (surface on workers)" "surface stage for minecraft:the_nether runs on worker threads"
check "the Nether is unaffected (carvers on workers)" "carvers stage for minecraft:the_nether runs on worker threads"
check "all chunks generated" "Pregen done: 49 chunks in minecraft:the_nether"
[ $fail -eq 0 ] && echo PASS || { echo "FAIL (log: $LOG)"; exit 1; }
