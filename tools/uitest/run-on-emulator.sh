#!/usr/bin/env bash
# Installs PocketForge + the screen walker on a running emulator/phone and walks every screen in
# light and dark mode. Exit code 0 only if no screen crashed and every on-device check passed.
#   tools/uitest/run-on-emulator.sh <folder with the APKs> <label>
set -uo pipefail
DIR="$1"; LABEL="${2:-device}"
OUT="uitest-out/$LABEL"; mkdir -p "$OUT"
APP=$(ls "$DIR"/PocketForge-*-release.apk "$DIR"/PocketForge-*-debug.apk 2>/dev/null | head -1)
TEST="$DIR/PocketForge-uitest.apk"
enc() { sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"} {print}'; }

adb wait-for-device
adb shell getprop ro.build.fingerprint | tee "$OUT/device.txt"
adb install -r "$APP" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; echo "::error title=$LABEL install failed::$(enc < "$OUT/install.txt")"; exit 1; }
adb install -r "$TEST" >> "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; echo "::error title=$LABEL test install failed::$(enc < "$OUT/install.txt")"; exit 1; }

fail=0
for pass in light dark; do
  adb shell cmd uimode night "$([ "$pass" = dark ] && echo yes || echo no)" > /dev/null 2>&1 || true
  adb logcat -c || true
  timeout 900 adb shell am instrument -w -r -e pass "$pass" dev.pocketforge.uitest/.ScreenWalker > "$OUT/$pass.txt" 2>&1
  tr -d '\r' < "$OUT/$pass.txt" | grep -E "INSTRUMENTATION_STATUS: (screen|step)=" | sed 's/INSTRUMENTATION_STATUS: //' | tr '\n' ' '; echo
  adb pull "/sdcard/Android/data/dev.pocketforge.ide/files/uitest/$pass" "$OUT/screens-$pass" > /dev/null 2>&1 || true
  summary=$(tr -d '\r' < "$OUT/$pass.txt" | sed -n '/INSTRUMENTATION_RESULT: summary=/,/INSTRUMENTATION_CODE/p' | grep -v INSTRUMENTATION_CODE)
  if tr -d '\r' < "$OUT/$pass.txt" | grep -q "INSTRUMENTATION_CODE: -1"; then
    echo "::notice title=$LABEL $pass: all screens OK::$(printf '%s' "$summary" | enc)"
  else
    fail=1
    last=$(tr -d '\r' < "$OUT/$pass.txt" | grep -E "INSTRUMENTATION_STATUS: (screen|step)=" | tail -1 | sed 's/.*=//')
    adb logcat -d -b crash > "$OUT/$pass-crash.txt" 2>&1 || true
    adb logcat -d > "$OUT/$pass-logcat.txt" 2>&1 || true
    crash=$(grep -v "^---------" "$OUT/$pass-crash.txt" | head -60)
    [ -z "$crash" ] && crash=$(grep -E "AndroidRuntime|FATAL|Exception|at dev\.pocketforge" "$OUT/$pass-logcat.txt" | head -60)
    echo "::error title=$LABEL $pass FAILED at screen '${last:-start}'::$( { printf '%s\n' "$summary"; tr -d '\r' < "$OUT/$pass.txt" | tail -15; printf '%s\n' "$crash"; } | head -120 | enc)"
  fi
done
exit $fail
