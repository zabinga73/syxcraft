#!/usr/bin/env bash
# Dev test server helper: ./devserver.sh start [fresh] | cmd "<command>" | wait | stop | log
cd "$(dirname "$0")"
# Minecraft 26.3 needs Java 25+; default to Prism Launcher's bundled runtime when JAVA_HOME isn't set
PRISM_JAVA="$HOME/.local/share/PrismLauncher/java/java-runtime-epsilon"
[[ -z "${JAVA_HOME:-}" && -d "$PRISM_JAVA" ]] && export JAVA_HOME="$PRISM_JAVA"
case "$1" in
  start)
    [[ "$2" == fresh ]] && rm -rf run/syxtest
    # an empty 26.x server pauses after 60 s, which would freeze long placements
    sed -i 's/^pause-when-empty-seconds=.*/pause-when-empty-seconds=-1/' run/server.properties
    rm -f run/in; mkfifo run/in
    nohup bash -c 'tail -f run/in | ./gradlew --no-daemon runServer' > run/server.out 2>&1 &
    echo $! > run/server.pid
    until grep -qE "Done \(|FAILED" run/server.out 2>/dev/null; do sleep 2; done
    grep -E "Done \(|FAILED" run/server.out ;;
  cmd)  echo "$2" > run/in ;;
  stop)
    # nothing running: writing to the fifo would block forever with no reader
    pid=$(cat run/server.pid 2>/dev/null)
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then echo "not running"; exit 0; fi
    echo stop > run/in
    until grep -q "BUILD SUCCESSFUL\|BUILD FAILED" run/server.out; do sleep 2; done
    pid=$(cat run/server.pid 2>/dev/null); [[ -n "$pid" ]] && pkill -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null; echo stopped ;;
  wait) # until the placement finishes (only the mod's own messages count, vanilla logs say "Failed" too)
    until grep -qE "Syx: done|Syx: placement failed|Syx: cancelled" run/server.out; do sleep 2; done
    grep -E "Syx: done|Syx: placement failed|Syx: cancelled" run/server.out | head -1 ;;
  log)  grep -E "Syx|ERROR|Exception|bad block" run/server.out | tail -${2:-20} ;;
esac
