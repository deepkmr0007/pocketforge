#!/usr/bin/env bash
# Runs every automated check available without an Android device:
#   1. static policy checks   2. engine unit tests (JVM)   3. Build Host E2E over pinned TLS
#   4. android-lite on-device build script E2E with real build tools   5. APK build (API-26 compile rule) + signature verification
#   PF_TOOLCHAIN=/path/to/toolchain tools/verify-all.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${PF_TOOLCHAIN:?set PF_TOOLCHAIN}"
LOG="$ROOT/build-local/verify-all.log"
mkdir -p "$ROOT/build-local"
: > "$LOG"
quiet() { grep -v -e '^Picked up JAVA_TOOL_OPTIONS' -e '^Info: Unexpected error while reading' || true; }
run() { echo "== $1" | tee -a "$LOG"; shift; "$@" 2>&1 | quiet | tee -a "$LOG" | tail -${TAIL:-3}; }
run "static checks" python3 -I "$ROOT/tools/verify/static_checks.py" "$ROOT"
TAIL=12 run "engine tests + Build Host E2E + lite-build E2E" env PATH="$TC/kotlinc/bin:$PATH" OUT="$ROOT/build-local/engine" \
  PF_TOOLCHAIN_BIN="$TC/bin" PF_ANDROID_JAR="$TC/android-35.jar" bash "$ROOT/tools/local-verify/compile-engine.sh" --e2e --lite-e2e
TAIL=14 run "APK build (release) + verification" env PF_TOOLCHAIN="$TC" bash "$ROOT/tools/build-apk.sh" --release --no-tests
echo "Full log: $LOG"
