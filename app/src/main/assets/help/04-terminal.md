# Terminal
The terminal runs Android's own shell (`/system/bin/sh`, toybox) inside the project folder. It is real, but it is **not** a Linux distribution: there is no gcc, java, python, git or package manager. For those, use Termux.

- The working folder, command, exit code and duration are always shown.
- The environment is clean: `HOME` is the project folder; the app's own environment is not passed on.
- Commands are classified as read-only, file-modifying, network, destructive or privileged. Anything that is not plainly read-only, or that names a path outside the project, asks for confirmation first. Imported (untrusted) projects ask for every command.
- Network commands are **blocked** unless you allow them in Settings, and then still need confirmation each time.
- Timeout and output limit are configurable. **Stop** ends the command; background child processes started with `&` may outlive it (Android gives apps no process-group control).
- Deleting files here bypasses the PocketForge trash.
- Commands suggested by the AI assistant are never run automatically; you can paste them here and run them yourself.
