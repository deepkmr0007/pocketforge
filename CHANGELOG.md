# Changelog

## 1.0.1 — 2026-10-13
### Fixed
- **Crash on start on some Android 15/16 phones** (`NullPointerException` in `PhoneWindow.getInsetsController`, reported from a real phone). The window's decor view is now created before edge-to-edge setup, the insets controller is taken from the decor view, and any vendor failure falls back to the older flags instead of crashing.

### Added
- **On-device screen walker** (`app/src/uitest`, test-only APK, never shipped): opens every screen the way the app navigates to it, types in the editor, rotates it, runs the security self-test on the device, and screenshots everything.
- **CI on real Android emulators** (Android 8.0, 11, 15, 16; light and dark): a crash fails the build with the crash trace as an annotation; releases are published only after all devices pass, with the screenshots attached.

## 1.0.0 — 2026-10-13
First complete build across phases 0–6.

### Added
- Native Android UI (framework Views, zero third-party libraries) for every screen in the spec: Home, project wizard, explorer + tabbed editor, project search/replace, snapshots, export, Build center, terminal, Test center, AI assistant, Privacy & permissions, Settings, Help & diagnostics, offline web preview.
- Code editor: gutter with error markers, background highlighting applied to the visible region, bracket matching, auto-indent, coding keyboard bar, hardware shortcuts, find/replace, recovery-journal banners, external-change resolution, large-file modes.
- Terminal on Android's system shell with risk classification, out-of-project path detection, confirmation, network blocking, timeouts and output caps (engine `terminal/`).
- On-device builds through Termux for the new **android-lite** template (generated, reviewable aapt2 → javac/kotlinc → d8 → apksigner script; APK re-hashed and inspected before success).
- Test runs via Termux; test history; on-device security self-test (16 checks).
- On-device AI model servers via 127.0.0.1 (cleartext allowed only for loopback).
- APK installation via PackageInstaller with system confirmation; signer comparison with the installed app.
- Storage usage view and cache cleanup that never touches source files.
- Reproducible Gradle-free build (`tools/build-apk.sh`) from pinned tool releases; static policy checks; `verify-all.sh`; lite-build E2E.
- Documentation: feasibility (Phase 0 A–E), capability matrix, threat model, dependency inventory, 10 ADRs, building, testing, release checklist, offline help pages.

### Changed
- UI layer moved from Jetpack Compose (v0.1, never compiled — Google Maven unreachable) to framework Views (ADR-0007). The engine, its tests, the Build Host and their behaviour are unchanged and extended.
- Settings storage: DataStore → SharedPreferences (same fields, plus new ones).
- FileProvider → own read-only `ShareProvider`.

### Removed
- Compose/AndroidX/Coroutines dependency declarations (unresolvable; ADR-0007). The v0.1 UI source remains in git history (commit d7d6fd1).

## 0.1.0 — 2026-10-12 (pre-existing)
Engine with tests (56), Build Host with E2E, unverified Compose UI.

## Migration strategy for future releases
- **Settings**: SharedPreferences keys are stable; new keys get defaults; never reuse a key with a different type.
- **Metadata files** (`projects.json`, build history, snapshots, journal) carry `schemaVersion`/`v`; add a migration step in the engine for each bump and a test with a fixture of the previous version; future versions are refused rather than downgraded.
- **Signing**: keep using the same key; key rotation must use APK Signature Scheme v3 lineage (`apksigner rotate`), never a silent key change.
- **Features**: never remove a working feature without a replacement; regression tests in `core/engine/src/test` must keep passing (they are run by every build).
