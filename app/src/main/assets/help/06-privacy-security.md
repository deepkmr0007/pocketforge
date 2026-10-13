# Privacy & security
- **No telemetry**: no analytics, ads, crash uploads, update checks or accounts. The WebView preview opts out of usage metrics and Safe Browsing lookups.
- **One network gate**: every network feature must obtain a single-use consent ticket bound to the exact bytes of one request (method, address, headers, body). If anything changes after you approve, the request is refused. Redirects are not followed. Leaving the screen, a timeout, or a missing screen all count as "deny".
- **Kill switch**: Settings → Block all network refuses everything, including already-approved requests.
- **TLS only** (system certificate authorities; user-installed CAs are not trusted). Build Hosts can be pinned by certificate fingerprint. Plain HTTP is allowed only to 127.0.0.1/localhost.
- **Secrets** are encrypted with AES-256-GCM under a non-exportable Android Keystore key. Logs and reports are redacted before they are written.
- **Exports** exclude keystores, `.env` files, `local.properties` and build outputs by default.
- **Backups**: Android cloud backup and device transfer are off; your projects never go to a cloud backup by accident.
- **Delete everything**: Privacy → Delete local app data removes projects, history, settings, keys and the Keystore key.

## Honest limits
No app is "unhackable". Residual risks are documented in docs/THREAT_MODEL.md in the source: a compromised phone or OS, malicious build scripts on your Build Host or in Termux, AI providers' own data handling once you approve a request, and pattern-based secret detection missing unusual formats (which is why the exact payload is always shown).
