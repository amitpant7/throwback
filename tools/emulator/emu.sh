#!/usr/bin/env bash
# Small emulator/phone helpers over adb (uses $ADB, default "adb"; pick a device with ANDROID_SERIAL).
#   emu.sh shot <name>     screenshot to $OUT/<name>.png (default OUT=./emu-out)
#   emu.sh texts           visible texts and content descriptions on screen
#   emu.sh tap "<text>"    taps the element whose text or content description is exactly <text>
#   emu.sh back            Back key
set -u
ADB=${ADB:-adb}
OUT=${OUT:-./emu-out}
dump() { $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; $ADB shell cat /sdcard/u.xml; }
case "${1:-}" in
  shot) mkdir -p "$OUT" && $ADB exec-out screencap -p > "$OUT/$2.png" && echo "$OUT/$2.png" ;;
  texts) dump | grep -oE '(text|content-desc)="[^"]{2,70}"' | sed 's/^[a-z-]*=//' | tr '\n' '|'; echo ;;
  tap)
    xy=$(dump | python3 -c '
import re, sys
xml, want = sys.stdin.read(), sys.argv[1]
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r"(text|content-desc)=\"" + re.escape(want) + "\"", node):
        a, b, c, d = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((a + c) // 2, (b + d) // 2)
        break
' "$2")
    if [ -n "$xy" ]; then $ADB shell input tap $xy && echo "tapped $2 at $xy"; else echo "not found: $2"; exit 1; fi ;;
  back) $ADB shell input keyevent KEYCODE_BACK ;;
  *) sed -n '2,6p' "$0"; exit 1 ;;
esac
