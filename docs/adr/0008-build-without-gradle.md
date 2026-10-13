# ADR-0008: Reproducible APK build without Gradle, from pinned official tool releases
Status: Accepted (2026-10-13)

## Decision
`tools/build-apk.sh` builds the APK with aapt2 (compile/link), javac (R.java), kotlinc (engine and app, against API-26 android.jar; Compat against API 35), D8 (or R8 with `--minify`), zip, and an apksig-based signer (`tools/apk-toolchain/ApkSign.kt`). Tools are fetched by `tools/fetch-toolchain.sh` from GitHub releases and verified against `tools/toolchain.lock`. The script runs static policy checks first and verifies the signature (apksig) and manifest (aapt2 dump) at the end.

The Gradle files remain for Android Studio users; they are unverified here (ADR-0007 context).

## Consequences
+ Builds anywhere with a JDK and GitHub access; every input pinned by hash. − Two build paths to keep in sync (the static check enforces the shared "no dependencies" rule; versions come from one place in PocketForgeApp.kt/build.gradle.kts).
