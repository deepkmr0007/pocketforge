package dev.pocketforge.ide.screens

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.editor.Diagnostics
import dev.pocketforge.engine.editor.Language
import dev.pocketforge.engine.editor.Severity
import dev.pocketforge.engine.projects.SortOrder
import dev.pocketforge.engine.projects.TreeNode
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.ide.editor.CodeEditText
import dev.pocketforge.ide.editor.EditorController
import dev.pocketforge.ide.editor.KeyboardBar
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.RowAdapter
import dev.pocketforge.ide.ui.simpleWatcher
import dev.pocketforge.ide.ui.value
import java.text.DateFormat
import java.util.Date

/** The IDE: explorer + tabbed editor + find/replace + status + coding keyboard. */
class ProjectActivity : BaseActivity(), EditorController.Binding, CodeEditText.Callbacks {
    private lateinit var pid: String
    private lateinit var ctl: EditorController
    private lateinit var editor: CodeEditText
    private lateinit var explorer: LinearLayout
    private lateinit var editorCol: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var banners: LinearLayout
    private lateinit var status: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var replaceInput: EditText
    private lateinit var findCount: TextView
    private lateinit var emptyState: TextView
    private lateinit var subtitle: TextView
    private lateinit var tree: ListView
    private lateinit var treeAdapter: RowAdapter<TreeNode>
    private var keyboardBar: View? = null
    private var findCase = false
    private val expanded = HashSet<String>()
    private var filter = ""
    private var sort = SortOrder.NAME
    private var wide = false
    private var projectName = ""
    private var cursorLine = 1
    private var cursorCol = 1
    private val journalTick = object : Runnable {
        override fun run() { if (::ctl.isInitialized) ctl.journalDirty(); c.main.postDelayed(this, c.settings.value.draftIntervalSec * 1000L) }
    }
    private val diagRunnable = Runnable { runDiagnostics() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        val proj = runCatching { c.projects.get(pid) }.getOrElse { toastLong(it.message ?: "Project not found"); finish(); return }
        projectName = proj.name
        bg({ c.projects.touch(pid) }) {}
        // Saved state belongs to the project it was saved for (recreate() may switch projects).
        val state = savedInstanceState?.takeIf { it.getString(KEY_PROJECT) == pid }
        state?.getStringArrayList(KEY_EXPANDED)?.let { expanded.addAll(it) }
        wide = resources.configuration.screenWidthDp >= 720
        ctl = c.editor(pid)
        buildUi()
        ctl.binding = this
        showTab(ctl.activeTab) // null → empty state with the editor hidden, so stray taps cannot type into nowhere
        tabsChanged()
        if (ctl.tabs.isEmpty()) {
            // Process death: reopen the tabs that were open; recovered drafts show up as banners.
            val paths = state?.getStringArrayList(KEY_TABS).orEmpty()
            val activePath = state?.getString(KEY_ACTIVE)
            paths.forEach { path -> ctl.open(path) { if (it.path == activePath) ctl.activate(ctl.tabs.indexOf(it)) } }
        }
        handle(intent)
        if (ctl.tabs.isEmpty() && intent.getStringExtra(Nav.PATH) == null && state == null) setExplorerVisible(true)
        refreshTree()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val other = intent.getStringExtra(Nav.PROJECT)
        if (other != null && other != pid) { recreate(); return } // different project: rebuild this screen for it
        handle(intent)
    }

    private fun handle(i: Intent) {
        val path = i.getStringExtra(Nav.PATH) ?: return
        val line = i.getIntExtra(Nav.LINE, 0)
        ctl.open(path) { if (line > 0) editor.post { editor.goToLine(line) } }
        if (!wide) setExplorerVisible(false)
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        val titleBlock = ui.vertical {
            addView(ui.text(projectName, 17f, bold = true, maxLines = 1))
            subtitle = ui.caption("").apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
            addView(subtitle)
        }
        val bar = ui.horizontal {
            setBackgroundColor(p.surface)
            minimumHeight = ui.dp(56)
            addView(ui.iconButton("←", "Back to projects") { finish() })
            addView(ui.iconButton("☰", "Show or hide files") { setExplorerVisible(explorer.visibility != View.VISIBLE) })
            addView(titleBlock, ui.lp(0, weight = 1f))
            addView(ui.iconButton("↶", "Undo") { undo() })
            addView(ui.iconButton("↷", "Redo") { redo() })
            addView(ui.button("Save", Btn.TEXT) { save() })
            val more = ui.iconButton("⋮", "More actions") {}
            more.setOnClickListener { menu(more) }
            addView(more)
        }

        // Explorer
        treeAdapter = RowAdapter(emptyList()) { n, _, convert -> treeRow(n, convert) }
        tree = ui.list().apply {
            adapter = treeAdapter
            setOnItemClickListener { _, _, pos, _ -> onTreeClick(treeAdapter.getItem(pos)) }
            setOnItemLongClickListener { _, _, pos, _ -> treeMenu(treeAdapter.getItem(pos)); true }
        }
        val filterInput = ui.input("Filter files").apply { addTextChangedListener(simpleWatcher { filter = it.trim(); refreshTree() }) }
        explorer = ui.vertical {
            setBackgroundColor(p.surface)
            addView(ui.horizontal {
                setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6))
                addView(filterInput, ui.lp(0, weight = 1f))
                addView(ui.iconButton("＋", "New file or folder") { newEntry("") })
                addView(ui.iconButton("⇅", "Sort files") { pickSort() })
                addView(ui.iconButton("⟳", "Refresh files") { refreshTree() })
            })
            addView(ui.divider())
            addView(tree, ui.lp(h = 0, weight = 1f))
        }

        // Editor column
        tabsRow = ui.horizontal { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        banners = ui.vertical()
        editor = CodeEditText(this, p).apply { callbacks = this@ProjectActivity }
        emptyState = ui.body("Open a file from the explorer (☰).\n\nTips: long-press files for more actions · Ctrl+S saves · ⋮ has search, build, terminal and AI.").apply {
            gravity = Gravity.CENTER; setTextColor(p.muted); setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
        }
        val editorFrame = FrameLayout(this).apply {
            addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyState, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        buildFindBar()
        status = ui.caption("").apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); setBackgroundColor(p.surface); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showDiagnostics() }
            contentDescription = "Status bar. Tap for problems in this file."
        }
        editorCol = ui.vertical {
            addView(ui.hscroll(tabsRow).apply { setBackgroundColor(p.surface) }, ui.lp())
            addView(banners, ui.lp())
            addView(editorFrame, ui.lp(h = 0, weight = 1f))
            addView(findBar, ui.lp())
            addView(ui.divider())
            addView(status, ui.lp())
        }
        val body = ui.horizontal {
            gravity = Gravity.NO_GRAVITY
            addView(explorer, if (wide) ui.lp(ui.dp(300), ViewGroup.LayoutParams.MATCH_PARENT) else ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            if (wide) addView(View(this@ProjectActivity).apply { setBackgroundColor(p.border) }, ui.lp(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(editorCol, ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val root = ui.vertical {
            addView(bar, ui.lp())
            addView(ui.divider())
            addView(body, ui.lp(h = 0, weight = 1f))
            if (c.settings.value.keyboardBar) {
                keyboardBar = KeyboardBar.build(ui) { editor.takeIf { ctl.activeTab?.readOnly == false } }
                addView(keyboardBar, ui.lp())
            }
        }
        setExplorerVisible(wide)
        applyEditorSettings()
        setScreen(root)
    }

    private fun buildFindBar() {
        findInput = ui.input("Find", monoFont = true)
        replaceInput = ui.input("Replace with", monoFont = true)
        findCount = ui.caption("")
        findInput.addTextChangedListener(simpleWatcher { updateFindCount() })
        findBar = ui.vertical(6) {
            setBackgroundColor(p.surface)
            visibility = View.GONE
            addView(ui.horizontal {
                addView(findInput, ui.lp(0, weight = 1f))
                addView(ui.iconButton("↑", "Previous match") { findNext(true) })
                addView(ui.iconButton("↓", "Next match") { findNext(false) })
                addView(ui.iconButton("✕", "Close find") { visibility = View.GONE })
            })
            addView(ui.horizontal {
                addView(replaceInput, ui.lp(0, weight = 1f))
                addView(ui.button("Replace", Btn.TEXT) { replaceOne() })
                addView(ui.button("All", Btn.TEXT) { replaceAll() })
            })
            addView(ui.horizontal {
                addView(ui.checkbox("Match case", false) { findCase = it; updateFindCount() })
                addView(findCount, ui.lp(0, weight = 1f))
            })
        }
    }

    private fun applyEditorSettings() {
        val s = c.settings.value
        editor.configure(s.fontSizeSp, s.wordWrap, s.noImeSuggestions)
        editor.showLineNumbers = s.lineNumbers
        editor.indentUnit = s.indentUnit
        ctl.activeTab?.let { editor.setReadOnly(it.readOnly) }
    }

    private fun setExplorerVisible(v: Boolean) {
        explorer.visibility = if (v) View.VISIBLE else View.GONE
        if (!wide) editorCol.visibility = if (v) View.GONE else View.VISIBLE
        if (v) refreshTree()
    }

    // ------------------------------------------------------------------ explorer

    private fun refreshTree() {
        val showHidden = c.settings.value.showHidden
        val exp = HashSet(expanded); val f = filter; val so = sort
        bg({ ctl.ws.tree(exp, so, f, showHidden) }) { r -> r.onSuccess { treeAdapter.set(it) }.onFailure { showError(it) } }
    }

    private fun treeRow(n: TreeNode, convert: View?): View {
        val row = (convert as? LinearLayout) ?: ui.horizontal {
            minimumHeight = ui.dp(44)
            addView(ui.caption("").apply { tag = "arrow"; minWidth = ui.dp(18) })
            addView(ui.chip("").apply { tag = "badge"; typeface = ui.mono })
            addView(ui.body("").apply { tag = "name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(ui.dp(8), 0, 0, 0) }, ui.lp(0, weight = 1f))
        }
        row.setPadding(ui.dp(8 + n.depth * 14), ui.dp(6), ui.dp(8), ui.dp(6))
        (row.findViewWithTag<TextView>("arrow")).text = if (n.isDir) (if (n.path in expanded || filter.isNotEmpty()) "▾" else "▸") else ""
        val badge = row.findViewWithTag<TextView>("badge")
        val (label, color) = badgeFor(n)
        badge.text = label; badge.setTextColor(color)
        badge.background = ui.rounded((color and 0x00FFFFFF) or 0x22000000, 6)
        row.findViewWithTag<TextView>("name").text = n.name
        val open = ctl.tabs.any { it.path == n.path }
        row.findViewWithTag<TextView>("name").setTextColor(if (open) p.accent else p.text)
        row.contentDescription = (if (n.isDir) "Folder " else "File ") + n.name
        return row
    }

    private fun badgeFor(n: TreeNode): Pair<String, Int> {
        if (n.isDir) return "DIR" to p.muted
        return when (Language.forFileName(n.name)) {
            Language.KOTLIN -> "KT" to p.token(dev.pocketforge.engine.editor.TokenType.KEYWORD)
            Language.JAVA -> "JV" to p.warn
            Language.XML -> "XML" to p.token(dev.pocketforge.engine.editor.TokenType.TAG)
            Language.HTML -> "HTM" to p.token(dev.pocketforge.engine.editor.TokenType.TAG)
            Language.CSS -> "CSS" to p.info
            Language.JAVASCRIPT -> "JS" to p.warn
            Language.JSON -> "{}" to p.ok
            Language.PYTHON -> "PY" to p.info
            Language.MARKDOWN -> "MD" to p.muted
            Language.GRADLE_GROOVY -> "GR" to p.ok
            Language.PROPERTIES -> "CFG" to p.muted
            Language.PLAIN -> "TXT" to p.muted
        }
    }

    private fun onTreeClick(n: TreeNode) {
        if (n.isDir) {
            if (!expanded.add(n.path)) expanded.remove(n.path)
            refreshTree()
        } else {
            if (n.sizeBytes > 20L * 1024 * 1024) { toastLong("${n.name} is ${humanBytes(n.sizeBytes)} — too large to open. Use project search."); return }
            ctl.open(n.path)
            if (!wide) setExplorerVisible(false)
        }
    }

    private fun treeMenu(n: TreeNode) {
        val parent = if (n.isDir) n.path else n.path.substringBeforeLast('/', "")
        val items = mutableListOf("New file or folder here…", "Rename…", "Copy relative path", "Move to trash")
        if (!n.isDir && n.name.endsWith(".html", true)) items.add(0, "Preview in app")
        Dialogs.choose(this, n.path, items) { i ->
            when (items[i]) {
                "Preview in app" -> Nav.preview(this, pid, n.path)
                "New file or folder here…" -> newEntry(parent)
                "Rename…" -> Dialogs.input(this, "Rename ${n.name}", "New name", n.name, "Rename") { nn ->
                    if (ctl.tabs.any { it.path == n.path || it.path.startsWith(n.path + "/") }) { toastLong("Close tabs for ${n.name} first."); return@input }
                    bg({ ctl.ws.rename(n.path, nn.trim()) }) { r -> r.onFailure { showError(it) }; refreshTree() }
                }
                "Copy relative path" -> Dialogs.copy(this, "path", n.path)
                "Move to trash" -> Dialogs.confirm(this, "Move ${n.name} to trash?", "You can restore it from Privacy → Trash.", "Move to trash", destructive = true) {
                    ctl.tabs.indices.reversed().filter { ctl.tabs[it].path == n.path || ctl.tabs[it].path.startsWith(n.path + "/") }.forEach { ctl.close(it, discard = true) }
                    bg({ c.projects.moveToTrash(pid, n.path) }) { r -> r.onSuccess { toast("Moved to trash") }.onFailure { showError(it) }; refreshTree() }
                }
            }
        }
    }

    private fun newEntry(parent: String) {
        Dialogs.input(this, "New file or folder", "name.kt   or   folder/", "", "Create",
            help = "End with / to create a folder. Paths like src/main/Util.kt create folders as needed." + if (parent.isNotEmpty()) "\nInside: $parent/" else "",
        ) { raw ->
            val rel = (if (parent.isEmpty()) "" else "$parent/") + raw.trim()
            bg({ if (rel.endsWith("/")) { ctl.ws.createFolder(rel.trimEnd('/')); null } else { ctl.ws.createFile(rel); rel } }) { r ->
                r.onSuccess { created -> refreshTree(); if (created != null) { ctl.open(created); if (!wide) setExplorerVisible(false) } }.onFailure { showError(it) }
            }
        }
    }

    private fun pickSort() {
        val opts = SortOrder.values()
        Dialogs.choose(this, "Sort files by", opts.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { sort = opts[it]; refreshTree() }
    }

    // ------------------------------------------------------------------ Binding

    override fun liveText(): CharSequence? = if (ctl.activeTab != null) editor.text else null

    override fun captureState(tab: EditorController.Tab) {
        if (tab !== ctl.activeTab) return
        tab.selStart = editor.selectionStart; tab.selEnd = editor.selectionEnd; tab.scrollY = editor.scrollY
    }

    override fun showTab(tab: EditorController.Tab?) {
        editor.highlighter = null
        if (tab == null) {
            editor.load("", 0, 0, 0)
            editor.visibility = View.INVISIBLE; emptyState.visibility = View.VISIBLE
            subtitle.text = ""
            renderBanners(); updateStatus()
            return
        }
        editor.visibility = View.VISIBLE; emptyState.visibility = View.GONE
        editor.language = tab.language
        editor.load(tab.text, tab.selStart, tab.selEnd, tab.scrollY)
        editor.setReadOnly(tab.readOnly)
        editor.errorLines = errorLinesOf(tab)
        if (tab.highlight) editor.highlighter = tab.highlighter
        subtitle.text = tab.path
        renderBanners()
        updateStatus()
        scheduleDiagnostics(300)
    }

    override fun tabsChanged() {
        tabsRow.removeAllViews()
        ctl.tabs.forEachIndexed { i, t ->
            val active = i == ctl.active
            val marker = when { t.external != ExternalChange.NONE -> " !"; t.dirty -> " ●"; else -> "" }
            val tab = ui.horizontal {
                setPadding(ui.dp(12), 0, ui.dp(2), 0)
                minimumHeight = ui.dp(44)
                background = if (active) ui.rounded(p.surfaceHi, 0, null) else null
                addView(ui.text(t.name + marker, 14f, if (active) p.accent else p.text, bold = active, maxLines = 1))
                addView(ui.iconButton("✕", "Close ${t.name}") { closeTab(i) }.apply { minWidth = ui.dp(36); setTextColor(p.muted) })
                setOnClickListener { ctl.activate(i) }
                contentDescription = "Tab ${t.name}${if (t.dirty) ", unsaved" else ""}"
            }
            tabsRow.addView(tab)
        }
        renderBanners()
        updateStatus()
        if (::treeAdapter.isInitialized) treeAdapter.notifyDataSetChanged()
    }

    override fun message(msg: String, error: Boolean) { if (error) toastLong(msg) else toast(msg) }

    // ------------------------------------------------------------------ banners & status

    private fun banner(text: String, color: Int, vararg actions: Pair<String, () -> Unit>) {
        banners.addView(ui.vertical(10) {
            setBackgroundColor((color and 0x00FFFFFF) or 0x26000000)
            addView(ui.text(text, 14f, p.text))
            if (actions.isNotEmpty()) addView(ui.horizontal { actions.forEach { (l, f) -> addView(ui.button(l, Btn.TEXT, onClick = f)) } })
        })
    }

    private fun renderBanners() {
        banners.removeAllViews()
        val t = ctl.activeTab ?: return
        t.draft?.let { d ->
            banner("Unsaved changes from ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.savedAtMillis))} were recovered for this file.", p.info,
                "Restore" to { ctl.restoreDraft(t) },
                "Compare" to { showDiff("Recovered draft vs file", t.savedText, d.text) },
                "Discard" to { Dialogs.confirm(this, "Discard recovered changes?", "The recovered text will be deleted.", "Discard", true) { ctl.discardDraft(t) } })
        }
        when (t.external) {
            ExternalChange.MODIFIED -> banner("${t.name} was changed outside PocketForge.", p.warn,
                "Compare" to { showDiff("File on storage vs your version", t.diskText ?: "", ctl.textOf(t)) },
                "Reload theirs" to { ctl.reloadFromDisk(t) },
                "Keep mine" to { Dialogs.confirm(this, "Overwrite the file?", "Your version replaces the file on storage. Take a snapshot first if unsure.", "Overwrite", true) { ctl.keepMine(t) } })
            ExternalChange.DELETED -> banner("${t.name} was deleted outside PocketForge. Your text is still here.", p.warn,
                "Save again" to { ctl.keepMine(t) })
            ExternalChange.NONE -> {}
        }
        when (t.doc.mode) {
            LargeFilePolicy.Mode.READ_ONLY_PREVIEW -> banner("Read-only preview of the first ${humanBytes(t.doc.decoded.text.length.toLong())} of a ${humanBytes(t.doc.sizeBytes)} file (too large to edit safely on a phone).", p.muted)
            LargeFilePolicy.Mode.EDIT_NO_HIGHLIGHT -> banner("Large file (${humanBytes(t.doc.sizeBytes)}): syntax highlighting is off to keep editing smooth.", p.muted)
            else -> {}
        }
        if (t.doc.decoded.mixedLineEndings) banner("This file mixes line endings; it will be saved with ${t.doc.decoded.lineEnding.label}.", p.muted)
    }

    private fun updateStatus() {
        val t = ctl.activeTab ?: run { status.text = "No file open"; return }
        val errs = t.diagnostics.count { it.severity == Severity.ERROR }
        val warns = t.diagnostics.count { it.severity == Severity.WARNING }
        val saved = t.lastSavedAt?.let { "saved " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: if (t.dirty) "unsaved" else "no changes"
        status.text = "Ln $cursorLine, Col $cursorCol · ${t.doc.decoded.encoding.label} · ${t.doc.decoded.lineEnding.label} · ${t.language.label}" +
            (if (errs + warns > 0) " · ⚠ $errs error(s), $warns warning(s)" else " · no problems") + " · $saved" + if (t.readOnly) " · read-only" else ""
        status.setTextColor(if (errs > 0) p.danger else p.muted)
    }

    private fun showDiff(title: String, a: String, b: String) {
        val m = DiffView.model(a, b)
        Dialogs.custom(this, title, ui.scroll(ui.vertical(12) { addView(ui.caption(DiffView.summary(m))); addView(DiffView.build(ui, m, selectable = false)) }))
    }

    // ------------------------------------------------------------------ editing callbacks

    override fun onUserEdit(start: Int, removed: String, inserted: String, cursorBefore: Int, cursorAfter: Int) {
        val t = ctl.activeTab ?: return
        t.history.recordEdit(start, removed, inserted, cursorBefore, cursorAfter, System.currentTimeMillis())
        if (!t.dirty) { t.dirty = true; tabsChanged() }
        scheduleDiagnostics(900)
    }

    override fun onCursor(line: Int, col: Int) { cursorLine = line; cursorCol = col; updateStatus() }

    override fun onShortcut(s: CodeEditText.Shortcut): Boolean {
        when (s) {
            CodeEditText.Shortcut.SAVE -> save()
            CodeEditText.Shortcut.SAVE_ALL -> ctl.saveAll()
            CodeEditText.Shortcut.UNDO -> undo()
            CodeEditText.Shortcut.REDO -> redo()
            CodeEditText.Shortcut.FIND -> openFind()
            CodeEditText.Shortcut.GOTO -> goToLine()
        }
        return true
    }

    private fun undo() {
        val t = ctl.activeTab ?: return
        if (t.readOnly) return
        val e = t.history.undoEdit(editor.text) ?: run { toast("Nothing to undo"); return }
        editor.replaceRange(e.start, e.start + e.inserted.length, e.removed, record = false)
        editor.setSelection(e.cursorBefore.coerceIn(0, editor.text.length))
        afterProgrammaticChange(t)
    }

    private fun redo() {
        val t = ctl.activeTab ?: return
        if (t.readOnly) return
        val e = t.history.redoEdit(editor.text) ?: run { toast("Nothing to redo"); return }
        editor.replaceRange(e.start, e.start + e.removed.length, e.inserted, record = false)
        editor.setSelection(e.cursorAfter.coerceIn(0, editor.text.length))
        afterProgrammaticChange(t)
    }

    private fun afterProgrammaticChange(t: EditorController.Tab) {
        ctl.refreshDirty(t)
        tabsChanged()
        scheduleDiagnostics(600)
    }

    private fun save() {
        val t = ctl.activeTab ?: return
        ctl.save(t)
    }

    private fun closeTab(i: Int) {
        val t = ctl.tabs.getOrNull(i) ?: return
        if (!ctl.refreshDirty(t)) { ctl.close(i, discard = false); return }
        Dialogs.choose(this, "${t.name} has unsaved changes", listOf("Save and close", "Discard changes and close", "Keep open")) { k ->
            when (k) {
                0 -> ctl.save(t) { ok -> if (ok) ctl.tabs.indexOf(t).takeIf { it >= 0 }?.let { ctl.close(it, discard = true) } }
                1 -> ctl.close(ctl.tabs.indexOf(t), discard = true)
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private fun scheduleDiagnostics(delay: Long) {
        c.main.removeCallbacks(diagRunnable)
        c.main.postDelayed(diagRunnable, delay)
    }

    private fun runDiagnostics() {
        val t = ctl.activeTab ?: return
        if (t.doc.mode == LargeFilePolicy.Mode.READ_ONLY_PREVIEW) return
        val text = editor.text.toString()
        if (text.length > 1_000_000) return
        bg({ Diagnostics.run(text, t.language) }) { r ->
            r.onSuccess { d ->
                if (t !== ctl.activeTab) return@onSuccess
                t.diagnostics = d
                editor.errorLines = errorLinesOf(t)
                updateStatus()
            }
        }
    }

    private fun errorLinesOf(t: EditorController.Tab) = t.diagnostics.filter { it.severity == Severity.ERROR }.map { it.line }.toSet()

    private fun showDiagnostics() {
        val t = ctl.activeTab ?: return
        if (t.diagnostics.isEmpty()) { toast("No problems found by the offline checks."); return }
        val items = t.diagnostics.map { "${it.severity.name.lowercase()} · line ${it.line}:${it.column} · ${it.message} (${it.source})" }
        Dialogs.choose(this, "Problems in ${t.name}", items) { i -> val d = t.diagnostics[i]; editor.goToLine(d.line, d.column) }
    }

    // ------------------------------------------------------------------ find / replace

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        val sel = editor.text.substring(minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0), maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0))
        if (sel.isNotEmpty() && !sel.contains('\n')) findInput.setText(sel)
        findInput.requestFocus()
        updateFindCount()
    }

    private fun updateFindCount() {
        val q = findInput.value()
        if (q.isEmpty()) { findCount.text = ""; return }
        val text = editor.text.toString()
        var n = 0; var i = text.indexOf(q, 0, !findCase)
        while (i >= 0 && n < 100_000) { n++; i = text.indexOf(q, i + q.length, !findCase) }
        findCount.text = if (n == 0) "No matches" else "$n match(es)"
    }

    private fun findNext(backwards: Boolean) {
        val q = findInput.value(); if (q.isEmpty()) return
        val text = editor.text.toString()
        val from = if (backwards) minOf(editor.selectionStart, editor.selectionEnd) - 1 else maxOf(editor.selectionStart, editor.selectionEnd)
        val i = if (!backwards) text.indexOf(q, from.coerceAtLeast(0), !findCase).let { if (it < 0) text.indexOf(q, 0, !findCase) else it }
        else text.lastIndexOf(q, from.coerceAtLeast(0), !findCase).let { if (it < 0) text.lastIndexOf(q, text.length, !findCase) else it }
        if (i < 0) { toast("No matches"); return }
        editor.requestFocus()
        editor.setSelection(i, i + q.length)
    }

    private fun replaceOne() {
        val t = ctl.activeTab ?: return; if (t.readOnly) return
        val q = findInput.value(); if (q.isEmpty()) return
        val a = minOf(editor.selectionStart, editor.selectionEnd); val b = maxOf(editor.selectionStart, editor.selectionEnd)
        if (a >= 0 && b > a && editor.text.substring(a, b).equals(q, ignoreCase = !findCase)) editor.text.replace(a, b, replaceInput.value())
        findNext(false)
        updateFindCount()
    }

    private fun replaceAll() {
        val t = ctl.activeTab ?: return; if (t.readOnly) return
        val q = findInput.value(); if (q.isEmpty()) return
        val text = editor.text.toString()
        val out = text.replace(q, replaceInput.value(), ignoreCase = !findCase)
        if (out == text) { toast("No matches"); return }
        var n = 0
        var i = text.indexOf(q, 0, !findCase)
        while (i >= 0) { n++; i = text.indexOf(q, i + q.length, !findCase) }
        editor.text.replace(0, editor.text.length, out) // recorded as ONE undo step
        updateFindCount()
        toast("Replaced $n occurrence(s). Undo restores them.")
    }

    // ------------------------------------------------------------------ menu

    private fun menu(anchor: View) {
        val t = ctl.activeTab
        val pm = PopupMenu(this, anchor)
        val items = mutableListOf("Find in file", "Search in project", "Go to line", "Problems (offline checks)", "Save all")
        if (t != null && t.language == Language.JSON) items += "Format JSON"
        if (t != null && (t.path.endsWith(".html", true) || t.path.endsWith(".htm", true))) items += "Preview in app"
        items += listOf("Snapshots", "Export ZIP", "Build center", "Run tests", "Terminal", "AI assistant", "Close all tabs", "Settings", "Help")
        items.forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
        pm.setOnMenuItemClickListener { mi ->
            when (items[mi.itemId]) {
                "Find in file" -> openFind()
                "Search in project" -> { ctl.journalDirty(); Nav.search(this, pid) }
                "Go to line" -> goToLine()
                "Problems (offline checks)" -> showDiagnostics()
                "Save all" -> ctl.saveAll()
                "Format JSON" -> formatJson()
                "Preview in app" -> t?.let { tab -> saveThen(tab) { Nav.preview(this, pid, tab.path) } }
                "Snapshots" -> requireSaved { Nav.snapshots(this, pid) }
                "Export ZIP" -> requireSaved { Nav.export(this, pid) }
                "Build center" -> requireSaved { Nav.build(this, pid) }
                "Run tests" -> requireSaved { Nav.tests(this, pid) }
                "Terminal" -> requireSaved { Nav.terminal(this, pid) }
                "AI assistant" -> {
                    val a = minOf(editor.selectionStart, editor.selectionEnd); val b = maxOf(editor.selectionStart, editor.selectionEnd)
                    val (from, to) = if (t != null && b > a) editor.lineCol(a).first to editor.lineCol(b).first else 0 to 0
                    Nav.ai(this, pid, t?.path, from, to)
                }
                "Close all tabs" -> closeAll()
                "Settings" -> Nav.settings(this)
                "Help" -> Nav.help(this)
            }
            true
        }
        pm.show()
    }

    private fun goToLine() {
        if (ctl.activeTab == null) return
        Dialogs.input(this, "Go to line", "1 – ${editor.lineCount()}", "", "Go", validate = { v -> if (v.trim().toIntOrNull() in 1..editor.lineCount()) null else "Enter a line between 1 and ${editor.lineCount()}" }) {
            editor.goToLine(it.trim().toInt())
        }
    }

    private fun formatJson() {
        if (ctl.activeTab?.readOnly != false) return
        val text = editor.text.toString()
        val pretty = runCatching { Json.stringify(Json.parse(text), pretty = true) + "\n" }.getOrElse { showError(it, "Not valid JSON"); return }
        if (pretty == text) { toast("Already formatted"); return }
        editor.text.replace(0, editor.text.length, pretty)
        toast("Formatted. Undo restores the original.")
    }

    /** Screens that read files from storage need the latest text saved first. */
    private fun requireSaved(go: () -> Unit) {
        val dirty = ctl.dirtyTabs()
        if (dirty.isEmpty()) { go(); return }
        Dialogs.choose(this, "${dirty.size} file(s) have unsaved changes", listOf("Save all and continue", "Continue without saving", "Cancel")) { k ->
            when (k) { 0 -> ctl.saveAll { go() }; 1 -> go() }
        }
    }

    private fun saveThen(t: EditorController.Tab, go: () -> Unit) { if (!ctl.refreshDirty(t)) go() else ctl.save(t, quiet = true) { if (it) go() } }

    private fun closeAll() {
        val dirty = ctl.dirtyTabs()
        val doClose = { while (ctl.tabs.isNotEmpty()) ctl.close(ctl.tabs.size - 1, discard = true) }
        if (dirty.isEmpty()) { doClose(); return }
        Dialogs.choose(this, "${dirty.size} file(s) have unsaved changes", listOf("Save all and close", "Discard all changes", "Cancel")) { k ->
            when (k) { 0 -> ctl.saveAll { doClose() }; 1 -> doClose() }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (ctl.binding !== this) {
            // Another editor screen for this project was bound meanwhile: re-sync from the shared controller.
            ctl.binding = this
            showTab(ctl.activeTab)
            tabsChanged()
        }
        applyEditorSettings()
        ctl.tabs.forEach { ctl.checkExternal(it) } // clean tabs changed on storage reload silently; dirty ones show a banner
        c.main.removeCallbacks(journalTick)
        c.main.postDelayed(journalTick, c.settings.value.draftIntervalSec * 1000L)
    }

    override fun onPause() {
        c.main.removeCallbacks(journalTick)
        if (::ctl.isInitialized) {
            ctl.activeTab?.let { captureState(it) }
            ctl.journalDirty()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::ctl.isInitialized) return
        outState.putString(KEY_PROJECT, pid)
        outState.putStringArrayList(KEY_TABS, ArrayList(ctl.tabs.map { it.path }))
        outState.putString(KEY_ACTIVE, ctl.activeTab?.path)
        outState.putStringArrayList(KEY_EXPANDED, ArrayList(expanded))
    }

    @Deprecated("Framework back handling; AndroidX OnBackPressedDispatcher is intentionally not used (ADR-0007).")
    override fun onBackPressed() {
        when {
            findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
            !wide && explorer.visibility == View.VISIBLE && ctl.tabs.isNotEmpty() -> setExplorerVisible(false)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        c.main.removeCallbacks(diagRunnable)
        if (::ctl.isInitialized && ctl.binding === this) {
            ctl.activeTab?.let { it.text = editor.text.toString(); captureState(it) }
            ctl.binding = null
        }
        if (::editor.isInitialized) editor.release()
        super.onDestroy()
    }

    companion object {
        private const val KEY_PROJECT = "project"
        private const val KEY_TABS = "tabs"
        private const val KEY_ACTIVE = "active"
        private const val KEY_EXPANDED = "expanded"
    }
}
