#!/usr/bin/env bash
# Downloads the pinned, official tool releases used by tools/build-apk.sh and verifies every
# file against tools/toolchain.lock. Nothing is installed system-wide.
#
#   tools/fetch-toolchain.sh /path/to/toolchain
#
# Sources (all public releases):
#   Kotlin compiler 2.0.21          github.com/JetBrains/kotlin/releases            (Apache-2.0)
#   bundletool 1.18.2 (D8/R8, apksig) github.com/google/bundletool/releases         (Apache-2.0; R8 BSD-3-Clause)
#   Apktool 2.11.1 (prebuilt aapt2)  github.com/iBotPeaches/Apktool/releases        (Apache-2.0; aapt2 from AOSP, Apache-2.0)
#   android.jar API 35 and API 26    github.com/Sable/android-platforms @ 1e98db1a  (Android SDK; Android SDK License applies —
#                                    used only at build time as a compile-time library, never redistributed in the APK)
set -euo pipefail
TC="${1:?usage: fetch-toolchain.sh <toolchain-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$TC"; cd "$TC"

get() { [ -f "$2" ] || curl -fL --retry 3 -o "$2.part" "$1" && { [ -f "$2" ] || mv "$2.part" "$2"; }; }
get https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip kotlin-compiler-2.0.21.zip
get https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar bundletool-all-1.18.2.jar
get https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar apktool_2.11.1.jar

if [ ! -f android-35.jar ] || [ ! -f android-26.jar ]; then
  rm -rf sable
  git clone --filter=blob:none --no-checkout https://github.com/Sable/android-platforms.git sable
  (cd sable && git checkout -q 1e98db1a199e8f7f85541af26bfc27019501b132 -- android-35/android.jar android-26/android.jar)
  cp sable/android-35/android.jar android-35.jar
  cp sable/android-26/android.jar android-26.jar
  rm -rf sable
fi
[ -f aapt2 ] || { unzip -q -o apktool_2.11.1.jar prebuilt/linux/aapt2_64 -d apktool-x && cp apktool-x/prebuilt/linux/aapt2_64 aapt2 && chmod +x aapt2 && rm -rf apktool-x; }

echo "== verifying checksums"
sha256sum -c "$ROOT/tools/toolchain.lock"

[ -d kotlinc ] || unzip -q kotlin-compiler-2.0.21.zip

echo "== building the apksig CLI (tools/apk-toolchain/ApkSign.kt)"
mkdir -p apksign
kotlinc/bin/kotlinc -jvm-target 17 -nowarn -cp bundletool-all-1.18.2.jar -include-runtime -d apksign/apksign.jar "$ROOT/tools/apk-toolchain/ApkSign.kt"

echo "== wrapper commands for the android-lite E2E (bin/)"
mkdir -p bin
cat > bin/apksigner <<EOF
#!/usr/bin/env bash
exec java -cp "$TC/apksign/apksign.jar:$TC/bundletool-all-1.18.2.jar" dev.pocketforge.tools.ApkSignKt "\$@"
EOF
cat > bin/d8 <<EOF
#!/usr/bin/env bash
exec java -Xmx2g -cp "$TC/bundletool-all-1.18.2.jar" com.android.tools.r8.D8 "\$@"
EOF
ln -sf "$TC/aapt2" bin/aapt2
ln -sf "$TC/kotlinc/bin/kotlinc" bin/kotlinc
chmod +x bin/*
echo "Toolchain ready in $TC"
