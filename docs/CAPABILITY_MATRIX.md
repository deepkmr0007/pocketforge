# Feature / capability matrix

Legend
- **Tested** — implemented, and its logic is covered by automated tests that pass (JVM unit tests and/or end-to-end runs with real tools).
- **Built** — implemented in the Android UI, compiled against API 26 and packaged in the verified APK, but **not executed on a device or emulator** in the build environment (none was available).
- **Device-dependent** — works only where the phone/Android version/user setup allows; the app detects and reports this at runtime.
- **Experimental** — implemented, logic tested, but its integration with third-party software (Termux) has not been run.
- **Not implemented** — absent; the UI says so instead of showing a fake control.

The app shows the live, device-measured version of this table under Help → What works on this phone.

| Area | Feature | Status |
|---|---|---|
| Projects | Create from 6 templates (android-lite Java, Android Compose, Kotlin JVM, Web, Python, Empty) with validated names/package IDs | Tested + Built |
| | Import ZIP (traversal, absolute paths, symlinks, case collisions, entry/size/ratio limits) | Tested + Built |
| | Import folder via Storage Access Framework (copied into private storage) | Built |
| | Edit in place inside a shared/SAF folder | Not implemented |
| | Rename, trash with restore, purge | Tested + Built |
| | Trusted/untrusted projects (imported = untrusted) | Built |
| Editor | Syntax highlighting (Kotlin, Java, XML/HTML, CSS, JS/TS, JSON, Python, Markdown, Gradle, properties) | Tested (lexers) + Built (view) |
| | Line numbers, current line, bracket matching, error markers in gutter | Built |
| | Tabs (12), dirty/conflict indicators | Built |
| | Undo/redo with word-level grouping | Tested + Built |
| | Auto-indent, Tab/Shift-Tab, coding keyboard bar, hardware shortcuts | Built |
| | Find/replace in file, go to line, format JSON | Built |
| | Offline diagnostics (JSON, brackets, XML, secrets, mixed indent) | Tested + Built |
| | Atomic saves, encoding + line-ending preservation | Tested |
| | Recovery journal (crash/process death), restore/compare/discard | Tested + Built |
| | External-change detection: compare / reload / keep mine | Tested + Built |
| | Large files: no-highlight mode; read-only 256 KB preview; binary refusal | Tested + Built |
| | Code folding | Not implemented |
| | Code completion / language server / refactoring engine | Not implemented |
| Search | Project search: regex, case, whole word, include/exclude globs, ReDoS guards, cancel | Tested + Built |
| | Replace with per-match preview, stale-file protection, undo | Tested + Built |
| History | Snapshots (content-addressed), compare, per-file diff, undoable restore | Tested + Built |
| | Git integration | Not implemented |
| Export | Editable manifest with secret/build-output exclusions; save via SAF or share; BUILD_INSTRUCTIONS.md | Tested + Built |
| Build | Capability probe (W^X exec test, RAM, storage, ABIs, tools, Termux) | Built (probe), Tested (evaluation) |
| | On-device build via Termux for android-lite projects | Experimental (script: Tested end-to-end on Linux with aapt2/javac/kotlinc/d8/apksig; Termux intent transport: not run) |
| | Remote build on your Build Host (pinned TLS, token, allow-listed tasks, verified APK) | Tested (E2E) + Built |
| | Export to computer (Android Studio / Gradle) | Tested + Built |
| | In-app Gradle/AGP build | Not available (Android W^X policy; see ADR-0002) |
| | Emulator | Not available on phones |
| APKs | Inspect (package, versions, SDKs, debuggable, v1/v2/v3/v3.1 signature blocks, SHA-256) | Tested |
| | Android's own parse + signer digests + installed-version/signer comparison | Built |
| | Install via PackageInstaller with system confirmation | Built, Device-dependent (needs "Install unknown apps") |
| Tests | Detect test files; run Gradle unit tests on Build Host; run a command in Termux | Built (Termux part Experimental) |
| | In-app JVM/Python test execution | Not available (no JDK/Python for apps) |
| | On-device security self-test (16 checks) | Tested (JVM) + Built |
| Terminal | /system/bin/sh in project folder, clean env, timeout, output cap, cancel, history | Tested (runner/analyzer on /bin/sh) + Built |
| | Risk classification, out-of-project path detection, confirmation, network block | Tested + Built |
| | Linux userland (gcc, java, python, git) | Not provided (use Termux) |
| AI | Providers: Anthropic; OpenAI-compatible (HTTPS); on-phone model server via 127.0.0.1 | Tested (request building, parsing, loopback rules) + Built |
| | Context selection, secret masking, injection flagging, exact-payload preview, per-request consent | Tested + Built |
| | Diff review with per-hunk accept, snapshot before apply, stale-file refusal | Tested (parser, diff) + Built |
| | Bundled on-device model | Not implemented |
| | Offline rule-based build-error explainer (not AI) | Tested + Built |
| Privacy & security | Central consent gateway; single-use tickets bound to request bytes; fail-closed broker; kill switch | Tested + Built |
| | Audit log of decisions (hashes and sizes only) | Tested + Built |
| | Keystore-encrypted secrets; deletion | Built |
| | No backups/device transfer; delete all local data | Built |
| | Redacted rotating logs; diagnostic report export | Tested + Built |
| | WebView preview: virtual origin, all other requests blocked, no file access, no JS bridges, Safe Browsing and metrics off | Built |
| | Static policy checks in every build | Tested |
| UX | Dark/light/system themes; edge-to-edge insets; phone and tablet (≥720dp side-by-side) layouts; FLAG_SECURE option | Built |
| | Screen-reader labels on icon buttons and rows; 48dp touch targets | Built (not audited with TalkBack) |
| | Translations | Not implemented (English only) |
