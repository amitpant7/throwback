#!/usr/bin/env bash
# A long feed scroll with memory sampling: restarts Throwback, scrolls the home feed with a swipe every
# 1.6 s, samples the page every 30 s (tools/devtools/memwatch.mjs) and the app's native memory every
# 60 s. Debug build, logged in. Uses $ADB (default "adb"); pick a device with ANDROID_SERIAL.
#   memrun.sh <label> <seconds>     -> $OUT/<label>.page.jsonl and $OUT/<label>.native.txt
# For a baseline without Throwback's page script, first:
#   adb shell "run-as app.throwback touch files/debug_noscript"   (remove it afterwards)
set -u
LABEL=$1; SECS=$2
ADB=${ADB:-adb}
DIR=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-./mem-out}; mkdir -p "$OUT"
PORT=${CDP_PORT:-9333}
$ADB shell am force-stop app.throwback
$ADB shell am start -n app.throwback/.MainActivity >/dev/null
sleep 18
PID=$($ADB shell pidof app.throwback | tr -d '\r')
$ADB forward tcp:$PORT localabstract:webview_devtools_remote_$PID >/dev/null
sleep 1
( end=$((SECONDS + SECS)); while [ $SECONDS -lt $end ]; do $ADB shell input swipe 540 1750 540 450 260; sleep 1.6; done ) &
SCROLL=$!
( end=$((SECONDS + SECS)); while [ $SECONDS -lt $end ]; do
    R=$($ADB shell "ps -A -o PID,NAME" | grep sandboxed_process | awk '{print $1}' | head -1)
    APP=$($ADB shell dumpsys meminfo app.throwback | grep -E "TOTAL PSS|Java Heap|Native Heap" | tr -s ' ' | tr '\n' ' ')
    REN=$([ -n "$R" ] && $ADB shell dumpsys meminfo "$R" | grep -E "TOTAL PSS" | tr -s ' ' | tr '\n' ' ')
    echo "$SECONDS app: $APP | renderer: $REN"
    sleep 60
  done ) > "$OUT/$LABEL.native.txt" &
NATIVE=$!
node "$DIR/../devtools/memwatch.mjs" "$PORT" "$SECS" 30 > "$OUT/$LABEL.page.jsonl" 2>&1
wait $SCROLL $NATIVE
echo "done: $OUT/$LABEL.page.jsonl $OUT/$LABEL.native.txt"
