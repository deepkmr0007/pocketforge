# ADR-0001: Zero third-party runtime dependencies
Status: Accepted (2026-10-12, reaffirmed 2026-10-13)

## Context
An IDE that holds source code and API keys is a high-value target. Every runtime library adds code we did not review, license obligations and update churn.

## Decision
The APK contains only our code and the Kotlin standard library. JSON, diffing, ZIP hardening, AXML parsing, highlighting and the HTTP gate are implemented in-house in `core/engine` with hard limits and tests. `tools/verify/static_checks.py` fails the build if the app declares other runtime dependencies or uses network/process APIs outside the approved files.

## Consequences
+ Small attack surface and APK (~1 MB unminified), nothing to license-audit at runtime, builds without Google Maven.
− More code to maintain; no ready-made widgets (editor, lists) — see ADR-0006/0007.
