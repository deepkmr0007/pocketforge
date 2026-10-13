# ADR-0004: One consent gateway; single-use tickets bound to the exact request
Status: Accepted (v0.1)

## Decision
`ConsentGateway.request()` shows the consent UI and, on "Approve once", issues a random, single-use ticket valid for 2 minutes and bound to a fingerprint of method + URL + header names/hashed values + body. `GatedTransport.send()` redeems it and refuses any mismatch, reuse, expiry, or a network kill switch turned on after approval. HTTPS only; plain HTTP only to loopback (ADR-0010). Redirects are not followed. Missing UI, timeouts, leaving the screen, or exceptions = deny. Decisions are logged with hashes and sizes, never content.

## Consequences
+ The approved bytes are exactly the sent bytes. + Easy to prove "no request before approval" (tests + on-device SelfTest).
− Each request needs its own approval (by design; the prompt forbids blanket approval).
