package dev.pocketforge.ide.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketforge.engine.editor.BracketMatcher
import dev.pocketforge.engine.editor.Diagnostic
import dev.pocketforge.engine.editor.Diagnostics
import dev.pocketforge.engine.editor.EditHistory
import dev.pocketforge.engine.editor.Highlighter
import dev.pocketforge.engine.editor.Indenter
import dev.pocketforge.engine.editor.Language
import dev.pocketforge.engine.editor.Span
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorTab(
    val path: String,
    val doc: Workspace.OpenedDocument,
    val value: TextFieldValue,
    val savedText: String,
    val language: Language,
    val spans: List<Span> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val bracket: Pair<Int, Int>? = null,
    val lastSavedAt: Long? = null,
    val draft: RecoveryJournal.Draft? = null,
    val external: ExternalChange = ExternalChange.NONE,
    val conflictDiskText: String? = null,
) {
    val dirty get() = value.text != savedText
    val highlight get() = doc.mode == LargeFilePolicy.Mode.FULL_EDIT
}

data class FindState(val visible: Boolean = false, val query: String = "", val replacement: String = "", val caseSensitive: Boolean = false, val matchCount: Int = 0)

data class EditorUiState(
    val tabs: List<EditorTab> = emptyList(),
    val active: Int = -1,
    val message: String? = null,
    val error: Boolean = false,
    val find: FindState = FindState(),
    val showDiagnostics: Boolean = false,
) {
    val tab get() = tabs.getOrNull(active)
}

/**
 * Tabbed editor state. Lives in the Activity's ViewModelStore (keyed per project) so tabs survive
 * navigation and rotation; unsaved text is additionally journaled to disk for process death.
 */
class EditorViewModel(private val c: AppContainer, private val pid: String) : ViewModel() {
    val state = MutableStateFlow(EditorUiState())
    private val ws = c.workspace(pid)
    private val histories = HashMap<String, EditHistory>()
    private val highlighters = HashMap<String, Highlighter>()
    private var analysisJob: Job? = null

    init {
        // Recovery journal ticker: writes drafts of dirty tabs; optionally autosaves to file.
        viewModelScope.launch {
            val lastDrafted = HashMap<String, String>()
            while (isActive) {
                delay(c.settings.value.draftIntervalSec * 1000L)
                for (t in state.value.tabs.filter { it.dirty && !it.doc.readOnly }) {
                    if (lastDrafted[t.path] == t.value.text) continue
                    io { c.recovery.saveDraft(pid, t.path, t.value.text, t.doc.stamp?.sha256) }.onSuccess { lastDrafted[t.path] = t.value.text }
                    if (c.settings.value.autosaveToFile && t.external == ExternalChange.NONE) save(t.path, force = false, quiet = true)
                }
            }
        }
    }

    fun open(path: String, line: Int = 0) {
        val existing = state.value.tabs.indexOfFirst { it.path == path }
        if (existing >= 0) { state.update { it.copy(active = existing) }; if (line > 0) goToLine(line); checkExternal(); return }
        viewModelScope.launch {
            io { ws.open(path) to c.recovery.drafts().firstOrNull { it.projectId == pid && it.relativePath == ws.guard.normalize(path) } }
                .onSuccess { (doc, draft) ->
                    val tab = EditorTab(doc.path, doc, TextFieldValue(doc.decoded.text), doc.decoded.text, Language.forFileName(doc.path), draft = draft)
                    histories[doc.path] = EditHistory()
                    state.update { s -> s.copy(tabs = s.tabs + tab, active = s.tabs.size) }
                    if (line > 0) goToLine(line)
                    analyze()
                }
                .onFailure { e -> msg(e.message ?: "Could not open $path", true) }
        }
    }

    fun select(i: Int) { state.update { it.copy(active = i) }; checkExternal(); analyze() }

    fun close(i: Int) {
        val t = state.value.tabs.getOrNull(i) ?: return
        if (!t.dirty) c.recovery.clear(pid, t.path)
        histories.remove(t.path); highlighters.remove(t.path)
        state.update { s ->
            val tabs = s.tabs.filterIndexed { idx, _ -> idx != i }
            s.copy(tabs = tabs, active = if (tabs.isEmpty()) -1 else minOf(s.active, tabs.size - 1).coerceAtLeast(0))
        }
    }

    fun onValueChange(v: TextFieldValue) {
        val t = state.value.tab ?: return
        val old = t.value
        if (v.text == old.text) { // cursor/selection move only
            updateActive { it.copy(value = v) }
            updateBracket()
            return
        }
        if (t.doc.readOnly) return
        var nv = v
        // Smart newline: carry indentation; open a block between braces.
        if (v.text.length == old.text.length + 1 && v.selection.collapsed && v.selection.start > 0 && v.text[v.selection.start - 1] == '\n' &&
            v.text.removeRange(v.selection.start - 1, v.selection.start) == old.text
        ) {
            val r = Indenter.newline(old.text, old.selection.start, c.settings.value.indentUnit, t.language)
            nv = TextFieldValue(r.text, TextRange(r.cursor))
        }
        histories.getOrPut(t.path) { EditHistory() }.record(old.text, nv.text, old.selection.start, nv.selection.start, System.currentTimeMillis())
        updateActive { it.copy(value = nv, bracket = null) }
        analyze()
    }

    fun insert(s: String) {
        val t = state.value.tab ?: return
        val v = t.value
        val newText = v.text.replaceRange(v.selection.min, v.selection.max, s)
        onValueChange(TextFieldValue(newText, TextRange(v.selection.min + s.length)))
    }

    fun indent(outdent: Boolean) {
        val t = state.value.tab ?: return
        val v = t.value
        val (txt, a, b) = Indenter.shiftLines(v.text, v.selection.min, v.selection.max, c.settings.value.indentUnit, outdent)
        onValueChange(TextFieldValue(txt, if (v.selection.collapsed) TextRange((v.selection.start + (txt.length - v.text.length)).coerceIn(a, b)) else TextRange(a, b)))
    }

    fun moveCursor(delta: Int) = updateActive { t -> t.copy(value = t.value.copy(selection = TextRange((t.value.selection.start + delta).coerceIn(0, t.value.text.length)))) }

    fun undo() { val t = state.value.tab ?: return; histories[t.path]?.undo(t.value.text)?.let { s -> updateActive { it.copy(value = TextFieldValue(s.text, TextRange(s.cursor))) }; analyze() } }
    fun redo() { val t = state.value.tab ?: return; histories[t.path]?.redo(t.value.text)?.let { s -> updateActive { it.copy(value = TextFieldValue(s.text, TextRange(s.cursor))) }; analyze() } }
    val canUndo get() = state.value.tab?.let { histories[it.path]?.canUndo } ?: false

    fun goToLine(line: Int) = updateActive { t ->
        var off = 0; var l = 1
        while (l < line && off < t.value.text.length) { val n = t.value.text.indexOf('\n', off); if (n < 0) break; off = n + 1; l++ }
        t.copy(value = t.value.copy(selection = TextRange(off)))
    }

    fun save(path: String? = null, force: Boolean = false, quiet: Boolean = false) {
        val t = (path?.let { p -> state.value.tabs.firstOrNull { it.path == p } } ?: state.value.tab) ?: return
        if (t.doc.readOnly) { msg("This file is open read-only (too large to edit safely).", true); return }
        val text = t.value.text
        viewModelScope.launch {
            io { ws.save(t.doc, text, force) }.onSuccess { r ->
                when (r) {
                    is Workspace.SaveResult.Saved -> {
                        c.recovery.clear(pid, t.path)
                        updateTab(t.path) { it.copy(savedText = text, doc = it.doc.copy(stamp = r.stamp), lastSavedAt = System.currentTimeMillis(), external = ExternalChange.NONE, conflictDiskText = null, draft = null) }
                        if (!quiet) msg("Saved ${t.path.substringAfterLast('/')}", false)
                    }
                    is Workspace.SaveResult.Conflict -> updateTab(t.path) { it.copy(external = r.change, conflictDiskText = r.diskText) }
                }
            }.onFailure { e -> msg(e.message ?: "Save failed", true) }
        }
    }

    fun saveAll() = state.value.tabs.filter { it.dirty }.forEach { save(it.path) }

    fun checkExternal() {
        val t = state.value.tab ?: return
        viewModelScope.launch {
            io { ws.checkExternal(t.doc) }.onSuccess { ch ->
                if (ch != ExternalChange.NONE) {
                    val disk = io { ws.guard.resolve(t.path).takeIf { it.isFile }?.let { f -> dev.pocketforge.engine.storage.TextCodec.decode(f.readBytes()).text } }.getOrNull()
                    updateTab(t.path) { it.copy(external = ch, conflictDiskText = disk) }
                }
            }
        }
    }

    /** Conflict resolution: take the disk version (my edits are kept in the recovery journal first). */
    fun reloadFromDisk() {
        val t = state.value.tab ?: return
        viewModelScope.launch {
            if (t.dirty) io { c.recovery.saveDraft(pid, t.path, t.value.text, t.doc.stamp?.sha256) }
            io { ws.open(t.path) }.onSuccess { doc ->
                histories[t.path] = EditHistory()
                updateTab(t.path) { it.copy(doc = doc, value = TextFieldValue(doc.decoded.text), savedText = doc.decoded.text, external = ExternalChange.NONE, conflictDiskText = null) }
                analyze()
            }.onFailure { e -> msg(e.message ?: "Reload failed", true) }
        }
    }

    fun keepMine() { val t = state.value.tab ?: return; save(t.path, force = true) }
    fun dismissExternal() = updateActive { it.copy(external = ExternalChange.NONE, conflictDiskText = null) }

    fun restoreDraft() = updateActive { t -> t.draft?.let { d -> t.copy(value = TextFieldValue(d.text), draft = null) } ?: t }.also { analyze() }
    fun discardDraft() { val t = state.value.tab ?: return; c.recovery.clear(pid, t.path); updateActive { it.copy(draft = null) } }

    // ---- find / replace in file ----
    fun setFind(f: FindState) { state.update { it.copy(find = f.copy(matchCount = countMatches(f))) } }
    private fun countMatches(f: FindState): Int {
        val t = state.value.tab ?: return 0
        if (f.query.isEmpty()) return 0
        var n = 0; var i = t.value.text.indexOf(f.query, 0, !f.caseSensitive)
        while (i >= 0 && n < 100_000) { n++; i = t.value.text.indexOf(f.query, i + f.query.length.coerceAtLeast(1), !f.caseSensitive) }
        return n
    }
    fun findNext(backwards: Boolean = false) {
        val f = state.value.find; val t = state.value.tab ?: return
        if (f.query.isEmpty()) return
        val text = t.value.text
        val i = if (!backwards) text.indexOf(f.query, t.value.selection.max, !f.caseSensitive).let { if (it < 0) text.indexOf(f.query, 0, !f.caseSensitive) else it }
        else text.lastIndexOf(f.query, (t.value.selection.min - 1).coerceAtLeast(0), !f.caseSensitive).let { if (it < 0) text.lastIndexOf(f.query, text.length, !f.caseSensitive) else it }
        if (i >= 0) updateActive { it.copy(value = it.value.copy(selection = TextRange(i, i + f.query.length))) } else msg("No matches", false)
    }
    fun replaceCurrent() {
        val f = state.value.find; val t = state.value.tab ?: return
        val sel = t.value.text.substring(t.value.selection.min, t.value.selection.max)
        if (sel.equals(f.query, ignoreCase = !f.caseSensitive) && sel.isNotEmpty()) insert(f.replacement)
        findNext()
    }
    fun replaceAll() {
        val f = state.value.find; val t = state.value.tab ?: return
        if (f.query.isEmpty()) return
        val out = t.value.text.replace(f.query, f.replacement, ignoreCase = !f.caseSensitive)
        onValueChange(TextFieldValue(out, TextRange(0))) // one undo step
        setFind(f)
    }

    fun toggleDiagnostics() = state.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    fun toggleFind() = state.update { it.copy(find = it.find.copy(visible = !it.find.visible)) }
    fun clearMessage() = state.update { it.copy(message = null) }

    fun updateBracket() {
        val t = state.value.tab ?: return
        if (!t.highlight) return
        val m = BracketMatcher.match(t.value.text, t.value.selection.start, t.spans)
        if (m != t.bracket) updateActive { it.copy(bracket = m) }
    }

    private fun analyze() {
        val t = state.value.tab ?: return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(120)
            val text = t.value.text
            if (t.highlight) {
                val spans = withContext(Dispatchers.Default) { highlighters.getOrPut(t.path) { Highlighter(t.language) }.highlight(text) }
                updateTab(t.path) { if (it.value.text == text) it.copy(spans = spans) else it }
                updateBracket()
            }
            delay(500)
            val diags = withContext(Dispatchers.Default) { if (text.length <= 1_000_000) Diagnostics.run(text, t.language) else emptyList() }
            updateTab(t.path) { if (it.value.text == text) it.copy(diagnostics = diags) else it }
        }
    }

    private fun updateActive(f: (EditorTab) -> EditorTab) = state.update { s ->
        val i = s.active; if (i !in s.tabs.indices) s else s.copy(tabs = s.tabs.toMutableList().also { it[i] = f(it[i]) })
    }
    private fun updateTab(path: String, f: (EditorTab) -> EditorTab) = state.update { s -> s.copy(tabs = s.tabs.map { if (it.path == path) f(it) else it }) }
    private fun msg(m: String, err: Boolean) = state.update { it.copy(message = m, error = err) }

    override fun onCleared() {
        // Last chance: journal any dirty buffers synchronously (small, app-private writes).
        state.value.tabs.filter { it.dirty }.forEach { t -> runCatching { c.recovery.saveDraft(pid, t.path, t.value.text, t.doc.stamp?.sha256) } }
    }
}
