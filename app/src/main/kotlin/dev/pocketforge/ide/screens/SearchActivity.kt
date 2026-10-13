package dev.pocketforge.ide.screens

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketforge.engine.search.PlannedReplacement
import dev.pocketforge.engine.search.ProjectSearcher
import dev.pocketforge.engine.search.RegexSafety
import dev.pocketforge.engine.search.Replacer
import dev.pocketforge.engine.search.SearchMatch
import dev.pocketforge.engine.search.SearchQuery
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.RowAdapter
import dev.pocketforge.ide.ui.value
import java.util.concurrent.atomic.AtomicBoolean

/** Project-wide search with safe replace: preview every change, pick which to apply, undo afterwards. */
class SearchActivity : BaseActivity() {
    private lateinit var pid: String
    private lateinit var query: EditText
    private lateinit var replacement: EditText
    private lateinit var include: EditText
    private lateinit var exclude: EditText
    private lateinit var summary: TextView
    private lateinit var searchBtn: TextView
    private lateinit var replaceBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var adapter: RowAdapter<SearchMatch>
    private var regex = false
    private var caseSensitive = false
    private var wholeWord = false
    private var lastQuery: SearchQuery? = null
    private var cancel: AtomicBoolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        query = ui.input("Search text or pattern", monoFont = true)
        replacement = ui.input("Replace with (optional)", monoFont = true)
        include = ui.input("Only files matching (e.g. **/*.kt, src/**)", monoFont = true)
        exclude = ui.input("Skip", SearchQuery("").exclude.joinToString(", "), monoFont = true)
        summary = ui.caption("")
        searchBtn = ui.button("Search", Btn.PRIMARY) { if (cancel != null) cancel?.set(true) else runSearch() }
        replaceBtn = ui.button("Preview replace…", Btn.SECONDARY, enabled = false) { previewReplace() }
        undoBtn = ui.button("Undo last replace", Btn.TEXT) { undoReplace() }
        val options = ui.vertical {
            visibility = View.GONE
            addView(ui.horizontal {
                addView(ui.checkbox("Regex", false) { regex = it })
                addView(ui.checkbox("Case", false) { caseSensitive = it })
                addView(ui.checkbox("Whole word", false) { wholeWord = it })
            })
            addView(include, ui.lp())
            addView(ui.space(6))
            addView(exclude, ui.lp())
        }
        // The form sits ABOVE the list (EditTexts inside ListView headers lose focus when results update).
        val form = ui.vertical(12) {
            setBackgroundColor(p.surface)
            addView(query, ui.lp())
            addView(ui.space(8))
            addView(replacement, ui.lp())
            addView(options, ui.lp())
            addView(ui.space(8))
            addView(ui.horizontal {
                addView(searchBtn, ui.lp(0, weight = 1f))
                addView(ui.space(0), ui.lp(ui.dp(8), 1))
                addView(replaceBtn, ui.lp(0, weight = 1f))
            })
            addView(ui.horizontal {
                addView(ui.button("Options", Btn.TEXT) { options.visibility = if (options.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
                addView(undoBtn)
            })
            addView(summary)
        }
        adapter = RowAdapter(emptyList()) { m, _, convert -> row(m, convert) }
        val list = ui.list().apply {
            adapter = this@SearchActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val m = this@SearchActivity.adapter.getItem(pos)
                Nav.project(this@SearchActivity, pid, m.path, m.line)
            }
        }
        val root = ui.vertical {
            addView(ui.topBar("Search in project", null, { finish() }), ui.lp())
            addView(ui.divider())
            addView(form, ui.lp())
            addView(ui.divider())
            addView(list, ui.lp(h = 0, weight = 1f))
        }
        setScreen(root)
        refreshUndo()
    }

    private fun currentQuery() = SearchQuery(
        text = query.value(),
        regex = regex, caseSensitive = caseSensitive, wholeWord = wholeWord,
        include = include.value().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        exclude = exclude.value().split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    private fun runSearch() {
        val q = currentQuery()
        if (q.text.isEmpty()) { toast("Type something to search for"); return }
        if (q.regex) runCatching { RegexSafety.check(q.text); ProjectSearcher.compile(q) }.onFailure { showError(it, "Pattern problem"); return }
        val flag = AtomicBoolean(false)
        cancel = flag
        searchBtn.text = "Stop"
        summary.text = "Searching…"
        val root = c.projects.dirOf(pid)
        bg({ ProjectSearcher.search(root, q) { flag.get() } }) { r ->
            cancel = null
            searchBtn.text = "Search"
            r.onSuccess { res ->
                lastQuery = q
                adapter.set(res.matches)
                summary.text = buildString {
                    append("${res.matches.size} match(es) in ${res.matches.map { it.path }.distinct().size} file(s); ${res.filesScanned} file(s) scanned")
                    if (res.truncated) append(" · stopped at ${q.maxResults} results")
                    if (flag.get()) append(" · stopped by you")
                    if (res.skipped.isNotEmpty()) append(" · skipped ${res.skipped.size} large file(s)")
                }
                ui.setEnabled(replaceBtn, res.matches.isNotEmpty())
            }.onFailure { showError(it, "Search failed") }
        }
    }

    private fun row(m: SearchMatch, convert: View?): View {
        val v = (convert as? LinearLayout) ?: ui.vertical(10) {
            addView(ui.caption("").apply { tag = "loc" })
            addView(ui.text("", 13f, monoFont = true, maxLines = 2).apply { tag = "line" })
        }
        v.findViewWithTag<TextView>("loc").text = "${m.path}:${m.line}:${m.column}"
        val line = m.lineText
        val sb = SpannableStringBuilder(line.trimEnd())
        val a = (m.column - 1).coerceIn(0, sb.length); val b = (a + m.length).coerceIn(a, sb.length)
        if (b > a) sb.setSpan(BackgroundColorSpan(p.findBg), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.findViewWithTag<TextView>("line").text = sb
        v.contentDescription = "Match in ${m.path} line ${m.line}"
        return v
    }

    private fun previewReplace() {
        val q = lastQuery ?: return
        val matches = adapter.items
        val repl = replacement.value()
        bg({ Replacer.plan(c.projects.dirOf(pid), q, matches, repl) }) { r ->
            r.onSuccess { plan -> showPlan(plan) }.onFailure { showError(it, "Cannot prepare the replacement") }
        }
    }

    private fun showPlan(plan: List<PlannedReplacement>) {
        val labels = plan.map { pr ->
            val m = pr.match
            val before = m.lineText.trim()
            val col = m.column - 1
            val after = (m.lineText.substring(0, col.coerceIn(0, m.lineText.length)) + pr.replacement + m.lineText.substring((col + m.length).coerceIn(0, m.lineText.length))).trim()
            "${m.path}:${m.line}\n− $before\n+ $after"
        }
        val checked = BooleanArray(plan.size) { true }
        Dialogs.multiChoose(this, "Review ${plan.size} replacement(s)", labels, checked) { sel ->
            val chosen = plan.mapIndexed { i, pr -> pr.copy(selected = sel[i]) }
            val n = chosen.count { it.selected }
            if (n == 0) return@multiChoose
            val files = chosen.filter { it.selected }.map { it.match.path }.distinct()
            val dirtyOpen = c.editor(pid).dirtyTabs().filter { it.path in files }
            if (dirtyOpen.isNotEmpty()) {
                Dialogs.info(this, "Save open files first", "These files have unsaved edits in the editor: ${dirtyOpen.joinToString { it.name }}. Save or discard them, then replace again.")
                return@multiChoose
            }
            Dialogs.confirm(this, "Replace $n occurrence(s) in ${files.size} file(s)?", "Files changed since the search are skipped, never partially edited. You can undo this replace afterwards.", "Replace") { apply(chosen) }
        }
    }

    private fun apply(plan: List<PlannedReplacement>) {
        bg({ Replacer.apply(c.projects.dirOf(pid), plan) }) { r ->
            r.onSuccess { out ->
                c.replaceUndo[pid] = out.undo
                c.editor(pid).reloadIfClean(plan.map { it.match.path }.toSet())
                refreshUndo()
                Dialogs.info(this, "Replace finished", "${out.replacements} replacement(s) in ${out.filesChanged} file(s)." +
                    if (out.staleFiles.isNotEmpty()) "\n\nSkipped because they changed after the search: ${out.staleFiles.joinToString()}" else "")
                runSearch()
            }.onFailure { showError(it, "Replace failed") }
        }
    }

    private fun undoReplace() {
        val u = c.replaceUndo[pid] ?: return
        Dialogs.confirm(this, "Undo the last replace?", "Restores ${u.fileCount} file(s) to their state before the replace. Files edited since then are left alone.", "Undo") {
            bg({ Replacer.undo(c.projects.dirOf(pid), u) }) { r ->
                r.onSuccess { conflicts ->
                    c.replaceUndo.remove(pid)
                    c.editor(pid).reloadIfClean(null)
                    refreshUndo()
                    Dialogs.info(this, "Undo finished", if (conflicts.isEmpty()) "All files restored." else "Not restored because they changed again: ${conflicts.joinToString()}")
                }.onFailure { showError(it) }
            }
        }
    }

    private fun refreshUndo() { undoBtn.visibility = if (c.replaceUndo[pid] != null) View.VISIBLE else View.GONE }
}
