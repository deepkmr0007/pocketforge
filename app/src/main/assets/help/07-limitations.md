# What PocketForge does not do
Stated plainly, so nothing is presented as working when it is not:
- **No Android emulator** on the phone.
- **No Gradle/Android-plugin builds inside the app** (Android's W^X policy and resource limits). Use Termux (android-lite projects), a Build Host, or export to a computer.
- **No bundled AI model.** A model server you run on the phone (e.g. llama.cpp in Termux) can be used through 127.0.0.1.
- **No Git integration** yet. Snapshots provide local version history.
- **No editing in place in shared folders**: projects are copied into private storage (import) and copied out (export).
- **No code completion or refactoring engine** (no language server). Offline checks cover syntax-level problems only.
- **No debugger, profiler or layout editor.**
- **English only** for now.
- **Unit tests** need a JDK or Python, which Android does not provide to apps: run them on a Build Host or in Termux.
