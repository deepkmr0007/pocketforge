# ADR-0006: Own code editor on top of the framework EditText
Status: Accepted (2026-10-13)

## Options evaluated
| Option | Notes |
|---|---|
| Sora Editor (Rosemoe) | Mature, fast, LGPL-2.1, distributed via Maven Central (unreachable here); adds a large dependency and LGPL obligations. |
| CodeView / similar EditText wrappers | Small, but lightly maintained; Maven-only. |
| WebView + CodeMirror/Monaco | Powerful, but a JS bundle inside a WebView widens the attack surface, needs asset licensing review, and is heavy on low-end phones. |
| **Framework EditText + own gutter/highlighting** | No dependency; works with every IME; accessibility from the platform. Highlighting can be bounded to the visible region. |

## Decision
`CodeEditText` extends EditText: line-number gutter (logical lines, also with wrap), error markers, current-line and bracket highlight, auto-indent, Tab handling, hardware shortcuts. Lexing runs in the background with the engine's line-cached `Highlighter`; spans are applied only around the visible region. Undo uses the engine's `EditHistory` fed by minimal edits from a TextWatcher.

## Consequences
+ Zero dependency; predictable IME behaviour (VISIBLE_PASSWORD input type disables auto-correct). − No code folding, no LSP, slower than a custom-rendered editor for multi-MB files (mitigated by LargeFilePolicy).
