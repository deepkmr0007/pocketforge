# ADR-0010: Plain HTTP allowed only to 127.0.0.1 for on-device model servers
Status: Accepted (2026-10-13)

## Context
The prompt prefers a genuinely local model. Bundling one (hundreds of MB, native inference code, model licenses) is not feasible here. Users can run llama.cpp's `llama-server` or Ollama inside Termux, which expose OpenAI-compatible APIs on `http://127.0.0.1:<port>`.

## Decision
The consent gateway, transport and network security config allow cleartext **only** for loopback hosts. Such providers are labelled "On-device model server"; every request still requires consent, and the dialog notes that any app on the phone listening on that port could receive the data.

## Consequences
+ A real on-device AI path without bundling a model. − Depends on the user running a server; requests to it are as private as the phone itself.
