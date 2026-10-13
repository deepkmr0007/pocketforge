# ADR-0003: Versioned JSON metadata files instead of Room
Status: Accepted (v0.1)

## Context
The prompt suggests Room. Metadata here is small (project list, build history, consent log, snapshots), written rarely, and must be readable by the pure-JVM engine and its tests. Room requires AndroidX + annotation processing (unavailable in the build environment).

## Decision
Atomic, versioned JSON files with explicit migrations (`ProjectRegistry.migrate`; future versions refused rather than downgraded), JSON-lines for append-only logs, and content-addressed object storage for snapshots.

## Consequences
+ Testable on the JVM; crash-safe via atomic writes; no ORM. − Not suitable for large query workloads; revisit if history grows beyond thousands of entries (bounded today: 200 builds, 1000 consent entries).
