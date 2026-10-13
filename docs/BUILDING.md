# Building PocketForge

## Reproducible build (no Gradle, no Google Maven) — used for the released APK
Requirements: Linux or macOS with JDK 17+ (21 used), bash, python3, zip, git, curl.
```
tools/fetch-toolchain.sh ~/pf-toolchain          # downloads pinned releases, verifies SHA-256 (tools/toolchain.lock)
PF_TOOLCHAIN=~/pf-toolchain tools/verify-all.sh  # static checks, all tests, E2Es, release APK
```
Individual builds:
```
PF_TOOLCHAIN=~/pf-toolchain tools/build-apk.sh             # debug APK (debuggable, local debug key)
PF_TOOLCHAIN=~/pf-toolchain tools/build-apk.sh --release   # non-debuggable, D8 release mode
PF_TOOLCHAIN=~/pf-toolchain tools/build-apk.sh --release --minify   # + R8 shrinking (test on a device first)
```
Output: `build-local/apk/PocketForge-<version>-<mode>.apk` and a `.sha256` file.

"Reproducible" here means: the same pinned, checksum-verified inputs and the same scripted steps on any
machine (verified on 2026-10-13 by fetching the toolchain into an empty folder and passing `verify-all.sh`).
The APK is **not yet bit-for-bit identical** across builds (zip entry timestamps); comparing two builds
therefore needs `apksigner verify` + content comparison rather than a single hash.

What the script does, in order: verify tool checksums → static policy checks → engine tests →
aapt2 compile/link (manifest package injected; aapt2 keep rules generated) → javac R.java →
kotlinc engine against **API-26** android.jar → kotlinc `Compat.kt` against API 35 → kotlinc app
against **API-26** android.jar → D8/R8 → zip → apksig v1+v2+v3 signing → apksig verification and
`aapt2 dump badging`.

### Signing
- Default: a LOCAL debug key is created in `$PF_TOOLCHAIN/keys/` (never in the repository).
- Your key: `PF_KEYSTORE=/path/key.p12 PF_KEYSTORE_PASS=env:MY_PASS PF_KEY_ALIAS=pocketforge tools/build-apk.sh --release`
  (`pass:`, `env:` and `file:` forms are accepted, like `apksigner`).
- Keep the keystore and its password safe and private. Android only installs an update over an existing
  install if it is signed with the **same key**; losing it means uninstalling (which deletes app data —
  export your projects first).

## Android Studio / Gradle (unverified in the original build environment)
Open the folder in Android Studio (AGP 8.13, Kotlin 2.2.20, compileSdk 35) or run
`gradle wrapper && ./gradlew assembleDebug :core:engine:test`. The Gradle build uses the same
sources and has no runtime dependencies besides `:core:engine`.

## Building android-lite projects on the phone
See the in-app guide (Help → Building APKs) or `app/src/main/assets/help/03-building.md`.
