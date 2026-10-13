# Editing code
- Syntax highlighting for Kotlin, Java, XML/HTML, CSS, JavaScript, JSON, Python, Markdown, Gradle and properties files. Highlighting is computed in the background and applied around what is on screen, so big files stay smooth.
- **Tabs** keep up to 12 files open. ● means unsaved, ! means the file changed outside PocketForge.
- **Undo/redo** (↶ ↷ or Ctrl+Z / Ctrl+Y) groups typing word by word.
- **Find/replace** in the file (Ctrl+F), **search in project** with regex, globs, preview and undo.
- **Coding keyboard bar**: Tab/outdent, brackets, quotes, operators and arrow keys.
- **Hardware keyboard**: Ctrl+S save, Ctrl+Shift+S save all, Ctrl+Z/Y undo/redo, Ctrl+F find, Ctrl+G go to line, Tab/Shift+Tab indent.
- **Problems**: offline checks for JSON syntax, unbalanced brackets, XML well-formedness and likely secrets. Tap the status bar.

## Never losing work
- Unsaved edits are written to an app-private **recovery journal** every few seconds (Settings → Autosave & recovery). After a crash or if Android closes the app, reopening the file offers **Restore / Compare / Discard**.
- Saving is **atomic**: the new content is written to a temporary file, flushed to storage, then swapped in. A power cut leaves either the old or the new file, never half of one.
- Encoding (UTF-8, UTF-8 BOM, UTF-16, ISO-8859-1) and line endings (LF/CRLF/CR) are preserved byte for byte.
- If another app changed the file, PocketForge will not overwrite it silently: **Compare**, **Reload theirs** or **Keep mine**.
- Files larger than the edit limit open as a read-only preview of their first 256 KB.

## Snapshots
Snapshots store the whole project (minus build outputs) locally. Unchanged files are stored once. Restoring takes an automatic "Before restore" snapshot first, so a restore can be undone.
