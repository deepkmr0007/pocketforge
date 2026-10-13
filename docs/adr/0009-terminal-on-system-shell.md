# ADR-0009: Terminal on Android's system shell, with risk gating
Status: Accepted (2026-10-13). Supersedes v0.1 "no terminal".

## Decision
Every Android device ships `/system/bin/sh` (toybox), which apps may execute. The terminal runs one command at a time with `sh -c`, working directory and HOME = project, a cleared environment, closed stdin, timeout, output cap and cancellation. `CommandAnalyzer` classifies risk and detects paths outside the project; anything not plainly read-only, any outside path, and any command in an untrusted project requires confirmation; network commands are blocked unless enabled in Settings. AI-suggested commands can only be pasted, never run automatically.

## Consequences
+ A real, honest terminal for file inspection and scripting. − Not a Linux userland (no compilers); child processes started in the background may outlive Stop; network-capable commands use the app's INTERNET permission outside the byte-bound consent gate (hence blocked by default and confirmed each time).
