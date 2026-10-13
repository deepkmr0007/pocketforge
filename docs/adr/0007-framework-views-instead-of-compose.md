# ADR-0007: Android framework Views instead of Jetpack Compose (v1.0)
Status: Accepted (2026-10-13). Supersedes the v0.1 Compose UI (preserved in git at commit d7d6fd1).

## Context
The master prompt asks for Compose + Material 3. In the build environment, Google's Maven repository and Maven Central are blocked by policy, so Compose, AndroidX, Coroutines and the Android Gradle Plugin cannot be downloaded. The v0.1 Compose UI had therefore never been compiled. The prompt also forbids fabricated build results.

## Decision
Rewrite the UI layer with framework Views (`android.app.Activity`, `EditText`, `ListView`, `AlertDialog`, framework Material themes), a small `Ui` toolkit for consistent styling, and plain executors instead of coroutines. Framework APIs deprecated in newer Android versions (`onActivityResult`, `onBackPressed`, `systemUiVisibility` below API 30) are used deliberately because their AndroidX replacements are unavailable; all post-API-26 calls live in `platform/Compat.kt` behind `SDK_INT` checks, and the rest of the app is compiled against the API-26 `android.jar` so an unguarded newer API fails the build.

## Consequences
+ The app is actually compiled, packaged, signed and verified. + No third-party code (ADR-0001). + Smaller and faster to start.
− More imperative UI code; no Compose previews. − Material 3 look is approximated with framework Material + custom palette.
Migration path: the engine is UI-agnostic; a Compose UI can be reintroduced screen-by-screen in a build environment with Google Maven access, keeping the same AppContainer and engine.
