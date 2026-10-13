#!/usr/bin/env bash
# Compiles core/engine with a standalone kotlinc (no Gradle, no network) and runs its tests
# through the tiny JUnit-compatible runner in this folder. Used where Maven is unreachable.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$ROOT/build-local}"
LIB="$(dirname "$(command -v "$KOTLINC")")/../lib"
rm -rf "$OUT"; mkdir -p "$OUT"
echo "== compile main"
"$KOTLINC" -jvm-target 17 -nowarn -d "$OUT/main.jar" $(find "$ROOT/core/engine/src/main/kotlin" -name '*.kt')
if [[ "${1:-}" == "--no-tests" ]]; then exit 0; fi
echo "== compile tests"
"$KOTLINC" -jvm-target 17 -nowarn -cp "$OUT/main.jar:$LIB/kotlin-test.jar" -d "$OUT/test.jar" \
  $(find "$ROOT/core/engine/src/test/kotlin" -name '*.kt') $(find "$HERE/junit-shim" -name '*.kt')
echo "== run tests"
java -cp "$OUT/main.jar:$OUT/test.jar:$LIB/kotlin-stdlib.jar:$LIB/kotlin-test.jar:$LIB/kotlin-reflect.jar" \
  dev.pocketforge.localverify.RunAllKt "$OUT/test.jar"
if [[ " $* " == *" --e2e "* ]]; then
  echo "== end-to-end: Build Host over pinned TLS"
  "$KOTLINC" -jvm-target 17 -nowarn -cp "$OUT/main.jar:$OUT/test.jar:$LIB/kotlin-test.jar" -d "$OUT/e2e.jar" "$HERE/e2e/BuildHostE2E.kt"
  java -cp "$OUT/main.jar:$OUT/test.jar:$OUT/e2e.jar:$LIB/kotlin-stdlib.jar:$LIB/kotlin-test.jar" \
    dev.pocketforge.localverify.BuildHostE2EKt "$ROOT/tools/build-host/pocketforge_build_host.py"
fi
if [[ " $* " == *" --lite-e2e "* ]]; then
  echo "== end-to-end: android-lite on-device build script with real Android build tools"
  : "${PF_TOOLCHAIN_BIN:?set PF_TOOLCHAIN_BIN to a folder with aapt2, d8, apksigner (see tools/fetch-toolchain.sh)}"
  : "${PF_ANDROID_JAR:?set PF_ANDROID_JAR to an android.jar (API 35)}"
  "$KOTLINC" -jvm-target 17 -nowarn -cp "$OUT/main.jar" -d "$OUT/lite-e2e.jar" "$HERE/e2e/LiteBuildE2E.kt"
  java -cp "$OUT/main.jar:$OUT/lite-e2e.jar:$LIB/kotlin-stdlib.jar" dev.pocketforge.localverify.LiteBuildE2EKt "$PF_TOOLCHAIN_BIN" "$PF_ANDROID_JAR"
fi
