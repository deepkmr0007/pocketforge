package dev.pocketforge.ide.editor

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.editor.Diagnostic
import dev.pocketforge.engine.editor.EditHistory
import dev.pocketforge.engine.editor.Highlighter
import dev.pocketforge.engine.editor.Language
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.ide.AppContainer
import java.util.concurrent.Executors

/**
 * Tabbed editor state for one project. Lives in [AppContainer] so tabs survive rotation and
 * navigation; unsaved text is additionally journaled to app-private storage for process death.
 * The bound screen supplies the live text of the active tab ([Binding.liveText]).
 */
class EditorController(private val c: AppContainer, val projectId: String) {
    class Tab(val path: String, var doc: Workspace.OpenedDocument, var savedText: String) {
        val language: Language = Language.forFileName(path)
        /** Text of this tab while it is not displayed (the screen owns the live text of the active tab). */
        var text: String = savedText
        val history = EditHistory()
        val highlighter = Highlighter(language)
        var selStart = 0
        var selEnd = 0
        var scrollY = 0
        var draft: RecoveryJournal.Draft? = null
        var external: ExternalChange = ExternalChange.NONE
        var diskText: String? = null
        var lastSavedAt: Long? = null
        var lastJournaledHash: Int = savedText.hashCode()
        var diagnostics: List<Diagnostic> = emptyList()
        var dirty = false
        val readOnly get() = doc.readOnly
        val highlight get() = doc.mode == LargeFilePolicy.Mode.FULL_EDIT
        val name get() = path.substringAfterLast('/')
    }

    interface Binding {
        /** Live text of the active tab, or null if the screen is not showing it. */
        fun liveText(): CharSequence?
        fun captureState(tab: Tab)
        fun showTab(tab: Tab?)
        fun tabsChanged()
        fun message(msg: String, error: Boolean)
    }

    val tabs = ArrayList<Tab>()
    var active = -1
        private set
    var binding: Binding? = null
    val ws: Workspace = c.workspace(projectId)
    /** Serialises file writes and journal writes for this project. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pf-editor-${projectId.take(6)}").apply { isDaemon = true } }

    val activeTab: Tab? get() = tabs.getOrNull(active)

    fun textOf(t: Tab): String = if (t === activeTab) binding?.liveText()?.toString() ?: t.text else t.text

    fun refreshDirty(t: Tab): Boolean { t.dirty = !t.readOnly && textOf(t) != t.savedText; return t.dirty }

    fun open(path: String, line: Int = 0, onOpened: (Tab) -> Unit = {}) {
        val norm = runCatching { ws.guard.normalize(path) }.getOrElse { binding?.message(it.message ?: "Invalid path", true); return }
        tabs.indexOfFirst { it.path == norm }.takeIf { it >= 0 }?.let { activate(it); onOpened(tabs[it]); return }
        c.io.execute {
            val r = runCatching {
                val doc = ws.open(norm)
                val draft = c.recovery.drafts().firstOrNull { it.projectId == projectId && it.relativePath == doc.path }
                doc to draft
            }
            c.main.post {
                r.onSuccess { (doc, draft) ->
                    if (tabs.size >= MAX_TABS) closeOldestClean()
                    val t = Tab(doc.path, doc, doc.decoded.text).apply { this.draft = draft?.takeIf { it.text != doc.decoded.text } }
                    tabs += t
                    activate(tabs.size - 1)
                    onOpened(t)
                }.onFailure { e -> binding?.message(e.message ?: "Could not open $norm", true) }
            }
        }
    }

    fun activate(i: Int) {
        if (i !in tabs.indices) return
        activeTab?.let { cur -> binding?.captureState(cur); cur.text = textOf(cur) }
        active = i
        binding?.showTab(tabs[i])
        binding?.tabsChanged()
        checkExternal(tabs[i])
    }

    /** Closes a tab. Callers confirm first if it is dirty; [discard] drops its draft. */
    fun close(i: Int, discard: Boolean) {
        val t = tabs.getOrNull(i) ?: return
        if (discard) c.recovery.clear(projectId, t.path)
        val wasActive = i == active
        if (wasActive) binding?.captureState(t)
        tabs.removeAt(i)
        active = when {
            tabs.isEmpty() -> -1
            wasActive -> i.coerceAtMost(tabs.size - 1)
            i < active -> active - 1
            else -> active
        }
        if (wasActive) binding?.showTab(activeTab)
        binding?.tabsChanged()
    }

    private fun closeOldestClean() {
        val idx = tabs.indices.firstOrNull { it != active && !refreshDirty(tabs[it]) } ?: return
        close(idx, discard = false)
    }

    fun save(t: Tab, force: Boolean = false, quiet: Boolean = false, done: (Boolean) -> Unit = {}) {
        if (t.readOnly) { binding?.message("This file is open read-only (too large to edit safely).", true); done(false); return }
        val text = textOf(t)
        writer.execute {
            val r = runCatching { ws.save(t.doc, text, force) }
            c.main.post {
                r.onSuccess { res ->
                    when (res) {
                        is Workspace.SaveResult.Saved -> {
                            c.io.execute { c.recovery.clear(projectId, t.path) }
                            t.doc = t.doc.copy(stamp = res.stamp)
                            t.savedText = text
                            t.lastSavedAt = System.currentTimeMillis()
                            t.external = ExternalChange.NONE; t.diskText = null; t.draft = null
                            t.lastJournaledHash = text.hashCode()
                            refreshDirty(t)
                            if (!quiet) binding?.message("Saved ${t.name}", false)
                            binding?.tabsChanged()
                            done(true)
                        }
                        is Workspace.SaveResult.Conflict -> {
                            t.external = res.change; t.diskText = res.diskText
                            binding?.tabsChanged()
                            if (!quiet) binding?.message("${t.name} was changed outside PocketForge. Choose how to resolve it.", true)
                            done(false)
                        }
                    }
                }.onFailure { e ->
                    binding?.message((e as? PfException)?.message ?: "Save failed: ${e.message}", true)
                    done(false)
                }
            }
        }
    }

    fun saveAll(done: (Int) -> Unit = {}) {
        val dirty = tabs.filter { refreshDirty(it) }
        if (dirty.isEmpty()) { done(0); return }
        var remaining = dirty.size; var ok = 0
        dirty.forEach { t -> save(t, quiet = true) { s -> if (s) ok++; if (--remaining == 0) { binding?.message("Saved $ok file(s)", ok != dirty.size); done(ok) } } }
    }

    fun dirtyTabs(): List<Tab> = tabs.filter { refreshDirty(it) }

    /** Writes drafts for dirty tabs whose text changed since the last draft; optionally autosaves to file. */
    fun journalDirty() {
        val autosave = c.settings.value.autosaveToFile
        for (t in tabs) {
            if (t.readOnly) continue
            val text = textOf(t)
            if (text == t.savedText) { t.dirty = false; continue }
            t.dirty = true
            val h = text.hashCode()
            if (h != t.lastJournaledHash) {
                t.lastJournaledHash = h
                val base = t.doc.stamp?.sha256
                writer.execute { runCatching { c.recovery.saveDraft(projectId, t.path, text, base) }.onFailure { c.log.warn("journal", "draft write failed", it) } }
            }
            if (autosave && t.external == ExternalChange.NONE) save(t, quiet = true)
        }
    }

    /** Synchronous last-chance journal (pause, crash). Small app-private writes only. */
    fun journalAllNow() {
        for (t in tabs) {
            if (t.readOnly) continue
            val text = runCatching { textOf(t) }.getOrNull() ?: t.text
            if (text != t.savedText) runCatching { c.recovery.saveDraft(projectId, t.path, text, t.doc.stamp?.sha256) }
        }
    }

    fun checkExternal(t: Tab) {
        c.io.execute {
            val ch = runCatching { ws.checkExternal(t.doc) }.getOrDefault(ExternalChange.NONE)
            if (ch == ExternalChange.NONE) return@execute
            val disk = runCatching { ws.guard.resolve(t.path).takeIf { it.isFile }?.let { f -> TextCodec.decode(f.readBytes()).text } }.getOrNull()
            c.main.post {
                if (ch == ExternalChange.MODIFIED && t in tabs && !refreshDirty(t)) { reloadFromDisk(t); return@post }
                t.external = ch; t.diskText = disk; binding?.tabsChanged()
            }
        }
    }

    /** Conflict resolution: take the disk version. My edits are journaled first so nothing is lost. */
    fun reloadFromDisk(t: Tab) {
        val mine = textOf(t)
        c.io.execute {
            val r = runCatching {
                if (mine != t.savedText) c.recovery.saveDraft(projectId, t.path, mine, t.doc.stamp?.sha256)
                ws.open(t.path)
            }
            c.main.post {
                r.onSuccess { doc ->
                    t.doc = doc; t.savedText = doc.decoded.text; t.text = doc.decoded.text
                    t.history.clear(); t.external = ExternalChange.NONE; t.diskText = null; t.dirty = false
                    if (t === activeTab) binding?.showTab(t)
                    binding?.tabsChanged()
                    binding?.message("Reloaded ${t.name} from storage.", false)
                }.onFailure { e -> binding?.message(e.message ?: "Reload failed", true) }
            }
        }
    }

    fun keepMine(t: Tab) = save(t, force = true)

    /** After replace-all, snapshot restore or an applied AI change: reload clean tabs, flag dirty ones. */
    fun reloadIfClean(paths: Collection<String>?) {
        for (t in tabs.toList()) {
            if (paths != null && t.path !in paths) continue
            if (!refreshDirty(t)) reloadFromDisk(t) else checkExternal(t)
        }
    }

    fun restoreDraft(t: Tab) {
        val d = t.draft ?: return
        t.draft = null
        t.text = d.text
        if (t === activeTab) binding?.showTab(t)
        refreshDirty(t)
        binding?.tabsChanged()
    }

    fun discardDraft(t: Tab) {
        t.draft = null
        c.io.execute { c.recovery.clear(projectId, t.path) }
        binding?.tabsChanged()
    }

    fun shutdown() {
        runCatching { journalAllNow() }
        writer.shutdown()
    }

    companion object { const val MAX_TABS = 12 }
}
