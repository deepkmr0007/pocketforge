# Threat model and privacy data flow

## Assets
1. Source code and project files (user's intellectual property; may contain secrets).
2. Secrets: AI API key, Build Host pairing token, signing keys the user keeps in projects.
3. Integrity of the user's files (no silent overwrite, no loss).
4. Integrity of what gets installed (APKs).
5. The user's attention/consent (no tricked approvals).

## Actors
- The user (trusted).
- Untrusted inputs: imported ZIPs/folders, project files written by others, AI model output, build logs, Build Host responses, Termux output, web content in the preview.
- Remote parties the user chooses to talk to: AI provider, the user's own Build Host.
- Other apps on the phone (may overlay the screen, may run servers on 127.0.0.1).
- A network attacker between the phone and remote parties.

## Trust boundaries and data flow
```
             ┌──────────────── phone ──────────────────────────────────────────────┐
 ZIP/folder ─┼─▶ SafeZipImporter / SafImport ──▶ app-private projects ◀─▶ Editor    │
 (untrusted) │     (PathGuard, limits)              │   ▲                         │
             │                                      │   │ snapshots, drafts,       │
             │                                      ▼   │ logs (redacted)          │
             │   Terminal ── /system/bin/sh (cwd=project, clean env, confirm)      │
             │   Preview  ── WebView (virtual origin only, everything else 403)   │
             │                                                                     │
             │   AI / Build Host request ─▶ ConsentGateway ─▶ consent dialog       │
             │         (redacted, user-chosen)   │ single-use ticket bound to bytes│
             │                                   ▼                                 │
             │                           GatedTransport ──TLS (pinned for host)──┼──▶ AI provider / Build Host
             │                                                                     │
             │   Termux bridge ─▶ SAF folder copy ─▶ RUN_COMMAND intent ─▶ Termux ─┼─(Termux's own permissions)
             │         ◀── stdout/exit code + APK read back, re-hashed, inspected  │
             └─────────────────────────────────────────────────────────────────────┘
```
Nothing leaves the phone except (a) bodies the user approved in the consent dialog, (b) files the
user explicitly exports/shares through Android's share sheet or document picker, and (c) project
copies the user sends to their own shared folder for Termux.

## Threats, mitigations and evidence

| # | Threat | Mitigation | Evidence |
|---|---|---|---|
| T1 | Zip-slip / absolute paths / symlinks in imported archives write outside the project | `PathGuard.normalize/resolve`, symlink refusal along the path, extraction only via guard | `SecurityTest.zipSlip…`, `absoluteZipEntryRejected`, `pathGuardRefusesSymlinks`; SelfTest |
| T2 | Zip bombs / huge archives exhaust storage | Entry count, per-entry, total and compression-ratio limits; streaming counters | `zipBombStoppedByRatio`, `zipLimitsEntryCountAndSize` |
| T3 | Code leaves the phone without consent | Single network gate; tickets single-use, short-lived, bound to method+URL+headers+body; redirects disabled; kill switch checked on request and redeem; fail-closed broker; static check bans other network APIs | `ConsentTest.*`, Build Host E2E "denied consent sends nothing", SelfTest, `static_checks.py` |
| T4 | Approved request altered after approval (e.g. different host/body) | Fingerprint comparison on redeem | `ticketIsBoundToExactPayloadHeadersAndHost`, SelfTest |
| T5 | Tap-jacking / accidental approval | `filterTouchesWhenObscured` on consent buttons; Approve enabled after 1.2 s; no "always allow" | Code review (ConsentDialog) |
| T6 | Network attacker | TLS with system CAs only (user CAs not trusted); Build Host certificate pinning; cleartext only to loopback | network_security_config; E2E wrong-fingerprint refusal |
| T7 | Secrets sent to AI/Build Host or written to logs/reports | `SecretRedactor` on AI context, build logs, local log, diagnostic report; exact payload preview; export excludes keystores/.env/local.properties | `redactorMasksCommonSecrets`, `aiContextIsRedacted…`, `errorExplainerAndRedactedLogsAndReport`, export manifest test |
| T8 | Prompt injection in project files steers the AI | Files wrapped as untrusted data; injection phrases flagged on the consent screen; output only becomes diffs; commands never executed; escaping paths rejected | `aiResponseBecomesReviewableProposalsNeverActions`, SelfTest |
| T9 | AI or user overwrites concurrent edits | Conflict detection on save; AI apply refuses stale files; snapshot before AI apply; replace skips stale files | Storage/search tests |
| T10 | Data loss (crash, process death, power loss) | Atomic writes with fsync; recovery journal with checksums; corrupt drafts skipped | `atomicWrite…`, `recoveryJournal…` |
| T11 | Destructive terminal commands / out-of-project access | Risk classification, path analysis, confirmation, untrusted-project mode, network commands blocked by default, timeouts and output caps | `analyzerConfinesPathsAndGatesRisk`, SelfTest |
| T12 | Fake "build succeeded" | SUCCESS recorded only after APK received, SHA-256 matches, AXML parsed, DEX present | `remoteBuildSucceedsOnlyWithVerifiedApk`, `toolchainBuildRecordingNeverReportsUnverifiedSuccess`, E2E |
| T13 | Installing a tampered APK | Android verifies signatures at install; app shows signer digests and compares with installed app; system confirmation always shown | Built (not device-tested) |
| T14 | Web preview exfiltrates or reads app data | Virtual origin only; every other request answered 403 locally; CSP `connect-src 'self'`; no file/content access; no JS bridges; Safe Browsing and metrics off | `static_checks.py` (loadUrl/JS-bridge rules) |
| T15 | Exported components abused by other apps | Only the launcher activity is exported; receivers/provider not exported; provider read-only and limited to two folders | `static_checks.py` manifest rule |
| T16 | Secrets at rest | AES-256-GCM, non-exportable Keystore key; backups disabled | Built |
| T17 | Supply-chain compromise | Zero runtime libraries; pinned build tools by SHA-256; static dependency rule | `toolchain.lock`, `static_checks.py` |
| T18 | Other app on the phone listening on 127.0.0.1 receives AI requests | Consent dialog warns that any app running a server on that port could receive the data | Code (consentFor local) |

## Residual risks (not solved, stated plainly)
- **Compromised device/OS or root**: can read app-private storage and the Keystore-protected secrets in use.
- **Build scripts** executed on a Build Host or inside Termux run with those environments' permissions. Only build projects you trust; prefer a VM/container for the Build Host.
- **Terminal** commands run inside PocketForge's sandbox and can change or delete anything in its private storage; background processes started with `&` can outlive Stop (Android gives no process-group control).
- **Pattern-based redaction** can miss unusual secret formats; the exact payload is therefore always reviewable.
- **AI providers** process approved data under their own terms and retention.
- **WebView** engine vulnerabilities are outside the app's control (kept updated by Google Play / the OEM).
- **DNS**: Android system services may resolve names independently of PocketForge.
- **The UI has not been executed on a device yet**; runtime defects may exist (see RELEASE_CHECKLIST.md).

PocketForge is not, and does not claim to be, "unhackable".
