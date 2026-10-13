# Release checklist

## Automated (must pass before every release)
- [x] `tools/fetch-toolchain.sh` checksums verify
- [x] `tools/verify-all.sh` — static checks, 74 engine tests, Build Host E2E 7/7, lite-build E2E 13/13
- [x] APK compiled with the API-26 rule, signed (v2+v3), `apksig verify` passes
- [x] Release signed with a dedicated release key (RSA 4096, PKCS#12, random 32-char password), generated outside the repository and handed to the owner separately; certificate SHA-256 `adb129f6acc6714e59a710afc6185f27ff4819b8655248b9a7e6c337eccbb9c0`

## On a real device (open — could not be done in the build environment)
Install on at least one Android 8–10 phone and one Android 14/15 phone.
- [ ] App starts; Home renders in light and dark theme; system bars and display cutout do not cover content (edge-to-edge on Android 15)
- [ ] Create each template; open, edit, save, close, reopen
- [ ] Kill the app from recents while a file has unsaved edits → reopen → recovered-draft banner → Restore works
- [ ] Rotate while editing (text, cursor, undo history kept)
- [ ] Edit a file with the terminal (`echo x >> file`) while it is open → external-change banner → Compare/Reload/Keep mine
- [ ] Import a ZIP and a folder; try a malicious ZIP (`../` entry) → refused
- [ ] Search & replace with preview; undo replace
- [ ] Snapshot, modify, compare, restore, restore "Before restore"
- [ ] Export ZIP via document picker and share sheet
- [ ] Terminal: `ls`, `cat`, a command touching `/data` (asks), `rm` (asks), `ping` (blocked), timeout (`sleep 999`), Stop
- [ ] Self-test (Test center) → all pass (or symlink SKIP on some storage)
- [ ] Settings → Block all network → AI request is refused without a dialog
- [ ] AI with a real provider: consent dialog shows payload; Deny sends nothing (check decision history); Approve → answer → diff → apply selected hunks → snapshot exists
- [ ] AI with a local llama.cpp/Ollama server in Termux at 127.0.0.1
- [ ] Build Host pairing and a debug build; Inspect / install the returned APK (system confirmation appears)
- [ ] Termux on-device build of an android-lite project (setup guide steps, permission prompt, folder pick, result verification)
- [ ] Web preview: page renders, JS toggle, external `<img src="https://…">` is blocked (counter increases)
- [ ] TalkBack pass over Home, editor, consent dialog; hardware keyboard shortcuts
- [ ] Low storage: save fails with an actionable message and the edits stay in memory/journal
- [ ] Delete everything → app data cleared, Keystore key removed

## Packaging
- [ ] Bump `BuildInfo.VERSION_NAME/VERSION_CODE` and `app/build.gradle.kts`
- [ ] Update CHANGELOG.md
- [ ] Publish APK + `.sha256`; never publish the keystore
