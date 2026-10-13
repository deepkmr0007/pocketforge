#!/usr/bin/env bash
# Builds the PocketForge APK WITHOUT Gradle or Google Maven, from pinned official tool releases
# (see tools/toolchain.lock and tools/fetch-toolchain.sh). This is how the APK in this repository's
# releases is produced; the Gradle files remain for Android Studio users.
#
#   PF_TOOLCHAIN=/path/to/toolchain tools/build-apk.sh [--release] [--minify] [--no-tests]
#
#   --release  non-debuggable build (D8 release mode). Default: debug.
#   --minify   additionally shrink/optimise with R8 (smaller APK). Off by default because the R8 inside
#              the pinned bundletool predates Kotlin 2.0; enable only after testing on a device.
#
# Signing: by default a LOCAL debug key is generated in $PF_TOOLCHAIN/keys (never committed).
# For a release key set PF_KEYSTORE, PF_KEYSTORE_PASS (env:/file:/pass: form) and PF_KEY_ALIAS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${PF_TOOLCHAIN:?set PF_TOOLCHAIN to the folder created by tools/fetch-toolchain.sh}"
OUT="$ROOT/build-local/apk"
MODE=debug; RUN_TESTS=1; MINIFY=0
for a in "$@"; do case "$a" in --release) MODE=release;; --minify) MINIFY=1;; --no-tests) RUN_TESTS=0;; *) echo "unknown option $a"; exit 2;; esac; done

KOTLINC="$TC/kotlinc/bin/kotlinc"
AAPT2="$TC/aapt2"
BT="$TC/bundletool-all-1.18.2.jar"
JAR35="$TC/android-35.jar"
JAR26="$TC/android-26.jar"
KLIB="$TC/kotlinc/lib"
APKSIGN=(java -cp "$TC/apksign/apksign.jar:$BT" dev.pocketforge.tools.ApkSignKt)
quiet() { grep -v -e '^Picked up JAVA_TOOL_OPTIONS' -e '^Info: Unexpected error while reading' || true; }
step() { printf '\n== %s\n' "$*"; }

PKG=dev.pocketforge.ide
VNAME=$(sed -n 's/.*VERSION_NAME = "\(.*\)".*/\1/p' "$ROOT/app/src/main/kotlin/dev/pocketforge/ide/PocketForgeApp.kt")
VCODE=$(sed -n 's/.*VERSION_CODE = \([0-9]*\).*/\1/p' "$ROOT/app/src/main/kotlin/dev/pocketforge/ide/PocketForgeApp.kt")

step "verify toolchain checksums"
(cd "$TC" && sha256sum --quiet -c "$ROOT/tools/toolchain.lock")

step "static checks (network gate, API level rule, secrets)"
python3 -I "$ROOT/tools/verify/static_checks.py" "$ROOT"

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes-r,engine,compat,app,dex}

if [ "$RUN_TESTS" = 1 ]; then
  step "engine unit tests"
  PATH="$TC/kotlinc/bin:$PATH" OUT="$OUT/engine-tests" bash "$ROOT/tools/local-verify/compile-engine.sh" | quiet | tail -3
fi

step "resources (aapt2 $("$AAPT2" version 2>&1 | quiet | head -1 | sed 's/.*(aapt) //'))"
sed '0,/<manifest/s//<manifest package="'"$PKG"'"/' "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/compiled.zip"
DEBUGFLAG=(); [ "$MODE" = debug ] && DEBUGFLAG=(--debug-mode)
"$AAPT2" link -I "$JAR35" --manifest "$OUT/AndroidManifest.xml" ${DEBUGFLAG[@]+"${DEBUGFLAG[@]}"} \
  --min-sdk-version 26 --target-sdk-version 35 --version-code "$VCODE" --version-name "$VNAME" \
  -A "$ROOT/app/src/main/assets" --java "$OUT/gen" --proguard "$OUT/aapt-rules.pro" \
  -o "$OUT/base.apk" "$OUT/res/compiled.zip"

step "compile R (javac --release 8 against API 26)"
javac -nowarn -Xlint:-options --release 8 -classpath "$JAR26" -d "$OUT/classes-r" $(find "$OUT/gen" -name '*.java') 2>&1 | quiet

step "compile engine (Kotlin, against API 26 android.jar: proves no API above minSdk is used)"
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 -nowarn -cp "$JAR26" -d "$OUT/engine" \
  $(find "$ROOT/core/engine/src/main/kotlin" -name '*.kt') 2>&1 | quiet

step "compile Compat.kt (the only file allowed newer APIs; every call is SDK_INT-guarded)"
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 -nowarn -cp "$JAR35:$OUT/engine" -d "$OUT/compat" \
  "$ROOT/app/src/main/kotlin/dev/pocketforge/ide/platform/Compat.kt" 2>&1 | quiet

step "compile app (Kotlin, against API 26 android.jar)"
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 -nowarn -cp "$JAR26:$OUT/engine:$OUT/compat:$OUT/classes-r" -d "$OUT/app" \
  $(find "$ROOT/app/src/main/kotlin" -name '*.kt' ! -name 'Compat.kt') 2>&1 | quiet

step "dex ($MODE$([ "$MINIFY" = 1 ] && echo ', R8 minify'))"
CLASSES=()
while IFS= read -r -d '' f; do CLASSES+=("$f"); done < <(find "$OUT/classes-r" "$OUT/engine" "$OUT/compat" "$OUT/app" -name '*.class' -print0)
if [ "$MODE" = release ] && [ "$MINIFY" = 1 ]; then
  cat "$ROOT/app/proguard-rules.pro" "$OUT/aapt-rules.pro" > "$OUT/rules.pro"
  (cd "$OUT" && mkdir -p r8in && cd r8in && for d in classes-r engine compat app; do (cd "$OUT/$d" && find . -name '*.class' | zip -q -@ "$OUT/r8in/$d.jar"); done)
  java -Xmx3g -cp "$BT" com.android.tools.r8.R8 --release --min-api 26 --lib "$JAR35" --pg-conf "$OUT/rules.pro" \
    --pg-map-output "$OUT/mapping.txt" --output "$OUT/dex" "$OUT"/r8in/*.jar "$KLIB/kotlin-stdlib.jar" 2>&1 | quiet
else
  D8MODE=--debug; [ "$MODE" = release ] && D8MODE=--release
  java -Xmx3g -cp "$BT" com.android.tools.r8.D8 $D8MODE --min-api 26 --lib "$JAR35" --output "$OUT/dex" "${CLASSES[@]}" "$KLIB/kotlin-stdlib.jar" 2>&1 | quiet
fi
ls -la "$OUT/dex"

step "package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)

step "sign"
if [ -n "${PF_KEYSTORE:-}" ]; then
  KS="$PF_KEYSTORE"; KSPASS="${PF_KEYSTORE_PASS:?set PF_KEYSTORE_PASS (pass:/env:/file:)}"; ALIAS="${PF_KEY_ALIAS:?set PF_KEY_ALIAS}"
else
  KS="$TC/keys/pocketforge-local-debug.p12"; KSPASS="pass:android"; ALIAS=pfdebug
  if [ ! -f "$KS" ]; then
    mkdir -p "$TC/keys"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android -keypass android -alias "$ALIAS" \
      -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=PocketForge local debug key" 2>&1 | quiet
    echo "created LOCAL debug key $KS (not a release key)"
  fi
fi
NAME="PocketForge-$VNAME-$MODE.apk"
"${APKSIGN[@]}" sign --ks "$KS" --ks-pass "$KSPASS" --ks-key-alias "$ALIAS" --out "$OUT/$NAME" "$OUT/unsigned.apk" 2>&1 | quiet

step "verify"
"${APKSIGN[@]}" verify -v --print-certs "$OUT/$NAME" 2>&1 | quiet
"$AAPT2" dump badging "$OUT/$NAME" 2>/dev/null | grep -E "^(package|minSdkVersion|sdkVersion|targetSdkVersion|uses-permission|launchable-activity|application-debuggable)" || true
( cd "$OUT" && sha256sum "$NAME" | tee "$NAME.sha256" )
ls -la "$OUT/$NAME"
echo "APK: $OUT/$NAME"
