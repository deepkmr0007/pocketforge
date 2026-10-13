# PocketForge IDE

A privacy-first, offline-first mobile development studio for Android phones: create and import
projects, edit code with highlighting and diagnostics, search/replace, keep local version history,
use a real (risk-gated) terminal, build APKs through honest routes, run tests where they can run,
and optionally ask an AI — with explicit approval before **every** network request.

> **Not Google's Android Studio** and not affiliated with Google. It does not pretend to replace
> desktop Android Studio; it shows exactly what works on your phone (Help → What works on this phone).

**Status (v1.0.1):** 94 automated checks pass (74 unit tests, 7 Build Host E2E, 13 on-device-build E2E)
plus static security checks and APK signature verification. **Every push is also run on real Android
emulators** (Android 8.0, 11, 15 and 16): a screen walker opens every screen in light and dark mode,
types in the editor, rotates it and runs the security self-test on the device; a release is published
only if nothing crashed, with the screenshots attached. 1.0.0 crashed on start on some Android 15/16
phones; 1.0.1 fixes it. See [docs/TESTING.md](docs/TESTING.md).

## Install
1. Copy `PocketForge-1.0.1-release.apk` to your phone and open it (allow "Install unknown apps" for your file manager when Android asks).
2. Requires Android 8.0+ (API 26); targets API 35 (Android 15). Tested on Android 8.0, 11, 15 and 16 emulators.
3. Verify the download: `sha256sum` must match the `.sha256` file. The release is signed with the PocketForge
   release key, certificate SHA-256 `adb129f6acc6714e59a710afc6185f27ff4819b8655248b9a7e6c337eccbb9c0`
   (`apksigner verify --print-certs`). Updates must carry the same certificate.

## What you can do
| | |
|---|---|
| Projects | Templates (android-lite Java, Android Compose, Kotlin JVM, Web, Python, Empty); import ZIP/folder with hardened extraction; trash with restore |
| Editor | Highlighting for 11 languages, line numbers, error markers, brackets, auto-indent, tabs, undo/redo, find/replace, go-to-line, coding keyboard bar, hardware shortcuts, large-file safeguards |
| Never lose work | Atomic saves, byte-exact encodings/line endings, crash-safe recovery journal, external-change detection, snapshots with undoable restore |
| Search | Project-wide regex/case/word/glob search; replace with per-match preview, stale-file protection and undo |
| Build | On-phone builds via Termux (android-lite), your own Build Host (pinned TLS), or export for Android Studio; APK inspection and install via Android's installer |
| Terminal | Android's real shell in the project folder; risky commands confirmed; network blocked by default |
| Tests | Test discovery; Gradle tests on the Build Host; commands in Termux; on-device security self-test |
| AI (optional) | Anthropic, any OpenAI-compatible service, or a model server on this phone (127.0.0.1); masked secrets; exact-payload review; diffs you accept per change |
| Privacy | One consent gate; kill switch; decision history; Keystore-encrypted secrets; no backups; delete everything |

## Improvements beyond the original spec
1. **Zero runtime dependencies** — nothing but our code and the Kotlin stdlib in the APK (ADR-0001).
2. **Consent tickets bound to the exact request bytes**, single-use and short-lived; any change after approval is refused (ADR-0004).
3. **API-level safety by construction** — everything except one `Compat.kt` compiles against the API-26 `android.jar`, so a call that would crash older phones fails the build.
4. **Static policy checks on every build** — no network or process APIs outside approved files, WebView hardening, manifest exports, secrets scan, dependency rule.
5. **On-device self-test** — the security invariants are re-checked on *your* phone, not just on the developer's machine.
6. **A real on-phone build path** (Termux bridge) whose generated script is reviewable and was validated end-to-end with the same tools.
7. **On-device AI without bundling a model** — loopback-only cleartext for a llama.cpp/Ollama server you run on the phone (ADR-0010).
8. **Reproducible, Gradle-free build** from SHA-256-pinned official releases (ADR-0008).
9. **Tap-jacking protection and delayed Approve** on the consent dialog.
10. **WebView preview sandbox** — virtual origin, every other request answered locally with 403, CSP, Safe Browsing and metrics off.
11. **Signer comparison before install** — warns when an update would fail because the signing key differs.
12. **Storage view and cache cleanup** that can never delete source, snapshots or drafts.

## Repository layout
```
app/                 Android app (framework Views) — see docs/FEASIBILITY.md §B for the module map
core/engine/         Pure Kotlin engine: storage, security, consent, search, diff, build, terminal, AI, self-test
tools/               build-apk.sh, fetch-toolchain.sh, toolchain.lock, verify-all.sh, verify/, apk-toolchain/,
                     build-host/ (Python Build Host), local-verify/ (test runner + E2Es)
docs/                FEASIBILITY (Phase 0 A–E), CAPABILITY_MATRIX, THREAT_MODEL, DEPENDENCIES, BUILDING,
                     TESTING, RELEASE_CHECKLIST, adr/ (10 decisions)
```

## Build from source
```
tools/fetch-toolchain.sh ~/pf-toolchain
PF_TOOLCHAIN=~/pf-toolchain tools/verify-all.sh
```
Details, signing and the Android Studio path: [docs/BUILDING.md](docs/BUILDING.md).

## Permissions and why
| Permission | Used for | When |
|---|---|---|
| `INTERNET` | AI provider or your Build Host | Only after you approve a specific request |
| `ACCESS_NETWORK_STATE` | Show online/offline on Home | Reads status only |
| `REQUEST_INSTALL_PACKAGES` | Install an APK you built | Only when you tap Install; Android confirms |
| `com.termux.permission.RUN_COMMAND` | Ask Termux to run the reviewed build/test script | Requested when you start the first on-device build |

No contacts, SMS, location, camera, microphone, accessibility or broad storage access. Files are
reached only through Android's document/folder pickers.

## Privacy behaviour
No analytics, ads, crash uploads, update checks or accounts. The app never makes a request on its own.
Every network request shows provider, address, purpose, files, size, risks and the exact body, and
needs "Approve once". Decisions are logged locally with hashes and sizes only. Android backup and
device transfer are disabled. Full model: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Build limitations (honest)
- No Gradle/AGP builds inside the app (Android's W^X policy); use Termux (android-lite), a Build Host, or a computer.
- No emulator on the phone. No bundled AI model. No Git, code completion, debugger or folding yet. English only.

## Troubleshooting
| Symptom | Fix |
|---|---|
| "Network is blocked in Settings" | Settings → Privacy → turn off *Block all network* |
| AI request "Only HTTPS destinations are allowed" | Use `https://…`, or `http://127.0.0.1:<port>` for a server on this phone |
| Termux build: "Termux refused the request" | In Termux: `echo 'allow-external-apps=true' >> ~/.termux/termux.properties`, restart Termux |
| Termux build: missing tools | Install the listed packages in Termux; provide `android.jar` (Help → Building APKs) |
| Termux build: "No such file or directory" | The path you confirmed does not match the folder you picked; run `termux-setup-storage`; pick a folder in Internal storage |
| Build Host: certificate mismatch | Re-pair with the fingerprint the host prints now |
| Install fails: conflict | An app with the same package but a different signing key is installed; export projects, uninstall it, install again |
| A file shows "changed outside PocketForge" | Compare, then Reload theirs or Keep mine |
| App closed unexpectedly | Reopen: unsaved edits are offered from the recovery journal; Help → Logs has a redacted trace |

## License
Choose a license for this repository before publishing. Third-party components and their licenses: [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).
