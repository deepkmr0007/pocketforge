# Phase 0 — Feasibility and decisions

Answers sections A–E of the master build prompt. Everything marked *measured* was observed in the
build environment on 2026-10-13; everything about phones is either documented Android behaviour or
is measured at runtime by the app's own capability probe (Help → What works on this phone).

## A. Feasibility assessment for the target phone

No specific phone model was named, so the target is **any arm64/armv7/x86_64 Android phone running
Android 8.0 (API 26) to Android 15 (API 35)**. The app does not assume capabilities; it measures them
on the device (`DeviceProbe` → `CapabilityEvaluator`).

| Question | Answer | Basis |
|---|---|---|
| Can a phone app edit, search, version and export projects offline? | **Yes.** Pure file I/O in app-private storage. | Implemented; engine tests |
| Can the app itself run Gradle + Android Gradle Plugin + kotlinc? | **No (in practice).** Apps targeting API 29+ cannot `exec()` files they wrote (W^X); there is no JDK; AGP's aapt2 is an x86-64 desktop binary; Gradle + AGP + Kotlin need ~4–6 GB heap. | Android 10 behaviour change; the app verifies W^X with an executable probe |
| Can an APK still be built *on the phone*? | **Yes, experimentally, through Termux** for framework-only ("android-lite") projects: aapt2 → javac/kotlinc → d8 → apksigner, all Termux packages installed by the user. | Script validated end-to-end on Linux with the same tools (13/13 checks); Termux transport itself untested on a device |
| Can it build normal Gradle/Compose projects? | **Yes, off-device**: user's own Build Host (pinned TLS, per-build consent) or export to Android Studio. | Build Host E2E 7/7 |
| Real terminal? | **Yes**: `/system/bin/sh` (toybox) exists on every Android device; confined to the project folder with a clean environment. Not a Linux distro. | Engine tests on `/bin/sh` |
| Emulator on the phone? | **No.** Needs hardware virtualization not exposed to apps. | Documented limitation |
| Local AI model? | **Not bundled** (size, licensing, native code). Supported via a model server the user runs on the phone (llama.cpp/Ollama in Termux) at `127.0.0.1`. | Loopback tests |
| Install built APKs? | **Yes**, via `PackageInstaller` with Android's own confirmation; requires the user to allow "Install unknown apps" once. | Implemented; not run on a device here |

### Build-environment facts (measured)
- Reachable: GitHub (git and release assets). **Blocked**: `maven.google.com`, `dl.google.com`, Maven Central, `plugins.gradle.org`, `services.gradle.org`, apt, npm, PyPI (proxy policy denials).
- Consequence: Jetpack Compose, AndroidX, Room, DataStore, Coroutines and the Android Gradle Plugin **cannot be downloaded**, so a Compose app could not be compiled or verified here. See ADR-0007 and ADR-0008.
- Available from official GitHub releases: Kotlin 2.0.21, bundletool 1.18.2 (contains D8/R8 and apksig), Apktool 2.11.1 (contains a static aapt2), and `android.jar` API 26/35 (Sable/android-platforms). Pinned by SHA-256 in `tools/toolchain.lock`.
- No `/dev/kvm`: no Android emulator could be run, so **the UI was compiled, packaged and verified but never executed** in this environment.

## B. Architecture and module map

```
app/ (Android, framework Views, zero third-party libraries)
├─ PocketForgeApp, AppContainer        manual DI (ADR-0005), executors, crash recorder
├─ ui/        Theme (palettes), Ui toolkit, BaseActivity (insets, FLAG_SECURE, consent host),
│             Dialogs, ConsentDialog, DiffView, Markdown
├─ editor/    CodeEditText (gutter, highlighting, brackets, indent, shortcuts),
│             EditorController (tabs, save/conflicts, journal, autosave), KeyboardBar
├─ screens/   Home, NewProject, Project (explorer+editor), Search, Snapshots, Export, Build,
│             Terminal, Test, Ai, Privacy, Settings, Help, Preview, Nav
└─ platform/  Settings (SharedPreferences), SecretVault (Keystore AES-GCM), DeviceProbe,
              SafImport (SAF), ApkTools (+InstallResultReceiver), ShareProvider,
              ConsentBroker, TermuxBridge (+TermuxResultReceiver), Compat (only API>26 calls)

core/engine/ (pure Kotlin/JVM, zero dependencies; compiles against API-26 android.jar)
├─ common/      Json, Hashing, PfException, Clock
├─ security/    PathGuard (no traversal/symlinks), SecretRedactor
├─ storage/     TextCodec (encodings, line endings), AtomicFileWriter, ConflictDetector,
│               LargeFilePolicy, RecoveryJournal
├─ projects/    ProjectManager (registry + migrations, trash), Workspace, Templates
├─ archive/     SafeZipImporter (zip-slip/bomb/limits), ExportManifest
├─ editor/      Highlighter (line-incremental lexers), EditHistory, Indenter, BracketMatcher, Diagnostics
├─ search/      ProjectSearcher (ReDoS guards), Replacer (preview/apply/undo, stale protection)
├─ snapshots/   SnapshotStore (content-addressed, undoable restore)
├─ diff/        Myers diff, hunks, unified patches
├─ consent/     ConsentGateway (single-use tickets bound to request bytes), GatedTransport, audit log
├─ build/       BuildService (export/remote/toolchain recording), ApkInspector (AXML + signing block),
│               LiteBuild (descriptor + script), TermuxProtocol
├─ terminal/    CommandAnalyzer, ShellRunner
├─ ai/          ContextBuilder, providers, ResponseParser, ErrorExplainer
├─ toolchain/   DeviceFacts, CapabilityEvaluator, CommandClassifier
├─ diagnostics/ LocalLog (rotating, redacted), DiagnosticReport
└─ selftest/    SelfTest (security invariants re-checked on the device)

tools/  build-apk.sh, fetch-toolchain.sh, toolchain.lock, verify-all.sh, verify/static_checks.py,
        apk-toolchain/ApkSign.kt, build-host/ (Python Build Host), local-verify/ (test runners, E2E)
```
Module boundaries from the prompt map to packages (editor, projects, toolchain, terminal, ai,
consent, storage, security, diagnostics). They are packages rather than Gradle modules because
the extra modules would contain no independent build logic yet (prompt: "do not create empty
modules for appearance only"). The hard boundary that matters — Android-free engine vs. app — is a
real module, and the engine is compiled separately.

## C. Feature matrix
See [CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md). Summary: editing/search/snapshots/export/terminal/
consent/AI-plumbing are local and implemented; on-device APK builds are experimental via Termux;
Gradle builds and unit tests are remote-only (Build Host) or computer-only; emulator, Git, LSP
completion, debugger are not implemented.

## D. Risks, licenses, toolchain requirements, alternatives

| Risk | Impact | Mitigation / alternative |
|---|---|---|
| UI never executed (no emulator here) | Runtime crashes on some devices | API-26 compile rule, conservative D8 build (no R8), lifecycle-safe async, crash recorder + recovery journal, on-device self-test; **first-run checklist in RELEASE_CHECKLIST.md** |
| W^X blocks in-app compilers | No in-app Gradle builds | Termux bridge (user-installed tools), Build Host, export |
| Termux RUN_COMMAND behaviour varies by Termux version | On-device builds may fail to start | Clear error messages; documented setup; script also runnable manually in Termux |
| Malicious build scripts (Build Host/Termux) | Code execution on the host / in Termux | Per-build consent, untrusted-project flag, Build Host runs a fixed task allow-list, docs recommend VM/container |
| AI prompt injection from project files | Model tries to make user run harmful commands | Untrusted-file wrapping, injection flagging, diff-only application, commands never executed |
| Secret leakage in uploads/logs | Credential exposure | Redaction everywhere + exact payload review + export exclusions |
| R8 from 2022 with Kotlin 2.0 bytecode | Mis-optimisation | R8 is opt-in (`--minify`); shipped APK uses D8 only |
| Signing key loss | Updates need uninstall (data loss) | Key generated for you and delivered separately; export projects before reinstalling |

**Licenses** (full inventory in [DEPENDENCIES.md](DEPENDENCIES.md)): runtime contains only Kotlin
stdlib (Apache-2.0). Build-time: Kotlin compiler (Apache-2.0), bundletool/D8/R8/apksig
(Apache-2.0/BSD-3), aapt2 from AOSP via Apktool (Apache-2.0), `android.jar` under the Android SDK
License — compile-time only, never redistributed in the APK. Termux is GPLv3 but is a separate app
the user installs; PocketForge only sends it a documented intent (no linking).

**Toolchain requirements**: build machine with JDK 17+ (21 used), bash, python3, zip, git, curl;
`tools/fetch-toolchain.sh` fetches the rest. On the phone for on-device builds: Termux with
`aapt2 d8 apksigner openjdk-17 zip` and an `android.jar`.

## E. Phased plan with measurable acceptance tests — and status

| Phase | Acceptance tests (measurable) | Status |
|---|---|---|
| 0 Feasibility | This document; ADRs; capability matrix; licenses | **Done** |
| 1 Offline IDE foundation | Create from every template (test); import ZIP rejects traversal/bomb/symlink (tests); atomic save + encodings byte-exact (tests); journal survives restart (test); registry migration (test); app compiles against API 26 | **Done** (engine tests pass; UI compiled, not device-run) |
| 2 Editing productivity | Highlighter incremental + 1 MB under budget (test); search/replace preview/apply/undo, stale files skipped (tests); diff round-trip on random inputs (test); snapshots dedupe + undoable restore (test); export excludes secrets (test) | **Done** |
| 3 Build integration | Capability probe honest (tests); remote build SUCCESS only with verified APK; denied consent sends 0 bytes (E2E 7/7); lite script builds signed, aligned APK with Java and Kotlin (E2E 13/13); missing tools reported, nothing created (test) | **Done** (Termux transport untested on device) |
| 4 Tests & diagnostics | Test-run recording by exit code (test); redacted logs/reports (test); on-device SelfTest passes on JVM (test) | **Done** |
| 5 AI | Context redaction + injection flags (test); proposals never applied, escaping paths rejected (test); loopback-only HTTP (test); consent required (tests) | **Done** (no live provider call made from here) |
| 6 Hardening & release | Static policy checks (network gate, exec, WebView, manifest, secrets, deps) in every build; pinned toolchain; signed APK verified by apksig + aapt2 | **Done**; device testing per RELEASE_CHECKLIST.md is **open** |
