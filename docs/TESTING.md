# Testing — exact commands and results

All results below were produced on 2026-10-13 in the build environment (Linux x86-64, OpenJDK 21,
Kotlin 2.0.21) by:
```
PF_TOOLCHAIN=~/pf-toolchain tools/verify-all.sh
```
Full output is written to `build-local/verify-all.log`.

| Suite | Command (inside verify-all) | Result |
|---|---|---|
| Static policy checks | `python3 tools/verify/static_checks.py .` | OK — network gate, process execution, WebView rules, manifest exports/backup/cleartext, secrets scan, no runtime dependencies |
| Engine unit tests (JVM) | `tools/local-verify/compile-engine.sh` | **74 / 74 passed** in 8 classes |
| Build Host end-to-end (real Python host, pinned TLS) | `compile-engine.sh --e2e` | **7 / 7 checks passed** |
| android-lite build script end-to-end (real aapt2, javac, kotlinc, d8, apksig) | `compile-engine.sh --lite-e2e` | **13 / 13 checks passed** (Java and Java+Kotlin variants) |
| App compile rule | `tools/build-apk.sh` (kotlinc against API-26 android.jar) | Pass — no API newer than minSdk outside `Compat.kt` |
| APK verification | apksig verify + `aapt2 dump badging` | v2 + v3 signatures verify; package `dev.pocketforge.ide`, min 26, target 35, launcher present |

The engine tests use a tiny JUnit-compatible runner (`tools/local-verify/junit-shim`) because JUnit
artifacts could not be downloaded; under Gradle the same tests run with JUnit 5.

## What the tests cover
- **Security**: zip-slip, absolute paths, symlinks, case collisions, archive bombs and limits; path guard; secret redaction; export exclusions; consent gating (no request before approval, single use, bound to bytes, expiry, kill switch, HTTPS-only with loopback exception); certificate pinning; redacted logs and reports.
- **Data safety**: atomic writes, encodings/line endings byte-exact, external-change detection, recovery journal (restart + corrupt drafts), registry migration and future-version refusal, trash/restore, snapshots (dedupe, undoable restore).
- **Editor/search**: lexers and incremental reuse, 1 MB highlighting budget, bracket matching, auto-indent, undo/redo coalescing (full-text and incremental APIs), search options/globs/excludes, ReDoS guard, replace preview/apply/undo with CRLF preservation and stale-file skip, random diff round-trips, stale patch rejection, diagnostics.
- **Builds**: APK inspector (AXML, signature schemes, garbage rejection), remote build success only with a verified APK, test tasks by exit code, toolchain build recording never reporting unverified success, lite descriptor validation, generated script syntax (`bash -n`) and quoting, missing-tool reporting without side effects, real end-to-end lite builds.
- **Terminal**: real `/bin/sh` runs in the workspace with a clean environment, exit codes, timeout, cancel, output cap, analyzer path confinement and risk gating.
- **AI**: context redaction and injection flags, proposals never applied, unsafe paths rejected, loopback labelling, consent texts.
- **Self-test**: the 16 on-device invariants (`SelfTest`) pass on the JVM.

## Not tested (and why)
- **The Android UI was never executed**: no emulator or device was available (no `/dev/kvm`, system images unreachable). Screens, dialogs, insets, IME behaviour, WebView preview, PackageInstaller and Termux intents are compiled and packaged but unverified at runtime.
- **Live AI providers**: no API key was used; request/response formats are tested against fixtures.
- **Termux RUN_COMMAND transport**: the generated script was run with the same tools on Linux; the intent round-trip with a real Termux was not.
- **Accessibility audit with TalkBack**, performance profiling on low-end phones, low-storage behaviour on a device.

These are the first items of [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
