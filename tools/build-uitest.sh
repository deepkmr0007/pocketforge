#!/usr/bin/env bash
# Builds the test-only screen-walker APK (app/src/uitest) against the classes produced by
# tools/build-apk.sh, and signs it with the SAME key (Android only lets an instrumentation run
# against an app signed with the same certificate). Run build-apk.sh first.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${PF_TOOLCHAIN:?set PF_TOOLCHAIN}"
APK="$ROOT/build-local/apk"; OUT="$ROOT/build-local/uitest"
BT="$TC/bundletool-all-1.18.2.jar"; JAR35="$TC/android-35.jar"; KLIB="$TC/kotlinc/lib"
APKSIGN=(java -cp "$TC/apksign/apksign.jar:$BT" dev.pocketforge.tools.ApkSignKt)
quiet() { grep -v -e '^Picked up JAVA_TOOL_OPTIONS' -e '^Info: Unexpected error while reading' || true; }
[ -d "$APK/app" ] || { echo "run tools/build-apk.sh first"; exit 2; }
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/cp"

"$TC/aapt2" link -I "$JAR35" --manifest "$ROOT/app/src/uitest/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 35 -o "$OUT/base.apk"

APPCP="$APK/engine:$APK/compat:$APK/app:$APK/classes-r"
"$TC/kotlinc/bin/kotlinc" -no-jdk -no-reflect -jvm-target 1.8 -nowarn -cp "$JAR35:$APPCP" -d "$OUT/classes" \
  $(find "$ROOT/app/src/uitest/kotlin" -name '*.kt') 2>&1 | quiet

# App classes and the Kotlin stdlib are only on the classpath: at run time they come from the app APK.
for d in engine compat app classes-r; do (cd "$APK/$d" && find . -name '*.class' | zip -q -@ "$OUT/cp/$d.jar"); done
CLASSES=(); while IFS= read -r -d '' f; do CLASSES+=("$f"); done < <(find "$OUT/classes" -name '*.class' -print0)
java -cp "$BT" com.android.tools.r8.D8 --release --min-api 26 --lib "$JAR35" \
  --classpath "$KLIB/kotlin-stdlib.jar" $(for j in "$OUT"/cp/*.jar; do printf -- '--classpath %s ' "$j"; done) \
  --output "$OUT/dex" "${CLASSES[@]}" 2>&1 | quiet

cp "$OUT/base.apk" "$OUT/unsigned.apk"; (cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)
if [ -n "${PF_KEYSTORE:-}" ]; then KS="$PF_KEYSTORE"; KSPASS="$PF_KEYSTORE_PASS"; ALIAS="$PF_KEY_ALIAS"
else KS="$TC/keys/pocketforge-local-debug.p12"; KSPASS="pass:android"; ALIAS=pfdebug; fi
"${APKSIGN[@]}" sign --ks "$KS" --ks-pass "$KSPASS" --ks-key-alias "$ALIAS" --out "$APK/PocketForge-uitest.apk" "$OUT/unsigned.apk" 2>&1 | quiet
"${APKSIGN[@]}" verify "$APK/PocketForge-uitest.apk" 2>&1 | quiet
ls -la "$APK/PocketForge-uitest.apk"
