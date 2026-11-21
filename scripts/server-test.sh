#!/usr/bin/env bash
# Dedicated server (docs/RELEASE_CRITERIA.md B10): start a fresh NeoForge dedicated server with Chunk
# Express (run/server), generate a square of new chunks, read /chunkexpress status, save and stop
# cleanly, then check the log and every region file. Requires that you have accepted the Minecraft
# EULA (https://aka.ms/MinecraftEULA): the script writes eula=true for this dev server.
set -u
cd "$(dirname "$0")/.."
DIR=run/server
LOG=build/server-test.log
PORT=25585
PASS=$(python -c "import secrets; print(secrets.token_hex(12))")
mkdir -p "$DIR" build
rm -rf "$DIR/world"
echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<PROPS
level-seed=20261117
online-mode=false
server-ip=127.0.0.1
server-port=25586
enable-rcon=true
rcon.port=$PORT
rcon.password=$PASS
spawn-protection=0
view-distance=10
PROPS

./gradlew runServer --console=plain > "$LOG" 2>&1 &
GRADLE=$!
for i in $(seq 1 300); do
    grep -q 'RCON running on' "$LOG" && break
    kill -0 $GRADLE 2>/dev/null || break
    sleep 1
done
if ! grep -q 'RCON running on' "$LOG"; then echo "FAIL: server did not start (log: $LOG)"; kill $GRADLE 2>/dev/null; exit 1; fi

RC="python scripts/rcon.py $PORT $PASS"
$RC "chunkexpress status" > build/server-test-rcon.txt
# 13 x 13 chunks of new terrain 16,000 blocks out, kept loaded until generated
$RC "forceload add 16000 16000 16199 16199" >> build/server-test-rcon.txt
for i in $(seq 1 120); do
    n=$($RC "forceload query" | grep -o -E '[0-9]+ force loaded' | grep -o -E '^[0-9]+' || echo 0)
    sleep 2
    $RC "chunkexpress status" | grep -q -E 'surface stage: [1-9][0-9]* chunks on worker' && [ $i -gt 10 ] && break
done
$RC "chunkexpress status" "forceload remove all" "save-all flush" >> build/server-test-rcon.txt
$RC "stop" >> build/server-test-rcon.txt 2>&1
wait $GRADLE

./gradlew regionCheck -Pworld="$DIR/world" --console=plain -q > build/server-test-regioncheck.log 2>&1; CHECK=$?
fail=0
check() { if grep -q -E "$2" "$3"; then echo "  ok   $1"; else echo "  FAIL $1"; fail=1; fi; }
check "surface stage on worker threads" "surface stage for minecraft:overworld runs on worker threads" "$LOG"
check "carvers stage on worker threads" "carvers stage for minecraft:overworld runs on worker threads" "$LOG"
check "status answered over RCON" "surface stage: [0-9]+ chunks on worker threads" build/server-test-rcon.txt
check "saved on stop" "All dimensions are saved|Saving worlds" "$LOG"
errors=$(grep -E '/(ERROR|FATAL)\]' "$LOG" | grep -v -i 'rcon' | wc -l)
[ "$errors" -eq 0 ] && echo "  ok   no errors in the server log" || { echo "  FAIL $errors errors in the server log"; fail=1; }
[ $CHECK -eq 0 ] && echo "  ok   region check: $(grep -E 'no problems found|region files' build/server-test-regioncheck.log | tr '\n' ' ')" || { echo "  FAIL region check"; fail=1; }
[ $fail -eq 0 ] && echo PASS || { echo "FAIL (logs: $LOG, build/server-test-rcon.txt)"; exit 1; }
