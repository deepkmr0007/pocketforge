# ADR-0002: No in-app Gradle build; Termux bridge, Build Host and export instead
Status: Accepted (v0.1), amended 2026-10-13 (Termux bridge added)

## Context
Apps targeting API 29+ cannot execute files they wrote to their own storage (W^X). Android has no JDK. AGP downloads an x86-64 aapt2. Gradle + AGP + kotlinc need several GB of heap. Bundling a JDK/SDK would also raise licensing questions.

## Decision
1. **Termux bridge (experimental)** for framework-only "android-lite" projects: the user installs Termux and its packages; PocketForge writes a reviewed script (aapt2 → javac/kotlinc → d8 → apksigner), asks Termux to run it via the documented RUN_COMMAND intent, and verifies the returned APK.
2. **Build Host**: the user's own computer runs `tools/build-host`, reached over pinned TLS with per-build consent and an allow-listed set of Gradle tasks.
3. **Export** for Android Studio.
The capability probe runs a real W^X test and reports why in-app builds are unavailable.

## Consequences
+ Honest, working routes to an APK from a phone. + No license-restricted components redistributed.
− Compose/AndroidX projects cannot be built on the phone itself. − The Termux transport depends on a third-party app's behaviour.
