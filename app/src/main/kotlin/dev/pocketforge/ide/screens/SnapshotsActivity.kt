package dev.pocketforge.ide.screens

import android.os.Bundle
import android.widget.LinearLayout
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.snapshots.Snapshot
import dev.pocketforge.engine.snapshots.SnapshotStore
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.Dialogs
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Local version history: create, compare, restore (itself undoable) and delete snapshots. */
class SnapshotsActivity : BaseActivity() {
    private lateinit var pid: String
    private lateinit var store: SnapshotStore
    private lateinit var col: LinearLayout
    private lateinit var root: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        store = c.snapshots(pid)
        root = c.projects.dirOf(pid)
        val (page, column) = ui.page(ui.topBar("Snapshots", "Local version history (stored on this phone)", { finish() }))
        col = column
        setScreen(page)
        refresh()
    }

    private fun refresh() {
        bg({ store.list() }) { r -> r.onSuccess(::render).onFailure { showError(it) } }
    }

    private fun render(list: List<Snapshot>) {
        col.removeAllViews()
        col.addView(ui.caption("A snapshot stores every project file (except build outputs). Unchanged files are stored once, so snapshots are cheap. Restoring first takes an automatic \"Before restore\" snapshot, so a restore can itself be undone."))
        col.addView(ui.space(10))
        col.addView(ui.button("Take snapshot now", Btn.PRIMARY) {
            Dialogs.input(this, "New snapshot", "Label, e.g. before refactor", "", "Save") { label ->
                bg({ store.create(root, label.trim()) }) { r -> r.onSuccess { toast("Snapshot saved: ${it.files.size} files") }.onFailure { showError(it) }; refresh() }
            }
        })
        col.addView(ui.section("${list.size} snapshot(s)"))
        if (list.isEmpty()) col.addView(ui.caption("No snapshots yet."))
        list.forEach { s ->
            col.addView(ui.card {
                addView(ui.text(s.label, 16f, bold = true))
                addView(ui.caption("${DateFormat.getDateTimeInstance().format(Date(s.createdAt))} · ${s.files.size} files · ${humanBytes(s.totalBytes)}"))
                addView(ui.horizontal {
                    addView(ui.button("Compare", Btn.TEXT) { compare(s) })
                    addView(ui.button("Restore", Btn.TEXT) { restore(s) })
                    addView(ui.button("Delete", Btn.TEXT) { delete(s) })
                })
            })
        }
    }

    private fun compare(s: Snapshot) {
        bg({ store.diff(s.id, root) }) { r ->
            r.onSuccess { d ->
                if (d.isEmpty) { Dialogs.info(this, "No differences", "The project matches \"${s.label}\"."); return@onSuccess }
                val items = d.modified.map { "modified · $it" } + d.added.map { "added since · $it" } + d.removed.map { "deleted since · $it" }
                Dialogs.choose(this, "Changes since \"${s.label}\"", items) { i ->
                    val path = items[i].substringAfter(" · ")
                    if (items[i].startsWith("modified")) showFileDiff(s, path)
                }
            }.onFailure { showError(it) }
        }
    }

    private fun showFileDiff(s: Snapshot, path: String) {
        bg({
            val sf = s.files.first { it.path == path }
            val old = TextCodec.decode(store.contentOf(sf)).text
            val now = TextCodec.decode(File(root, path).readBytes()).text
            DiffView.model(old, now)
        }) { r ->
            r.onSuccess { m -> Dialogs.custom(this, "$path: snapshot → now", ui.scroll(ui.vertical(12) { addView(ui.caption(DiffView.summary(m))); addView(DiffView.build(ui, m, false)) })) }
                .onFailure { showError(it) }
        }
    }

    private fun restore(s: Snapshot) {
        val ctl = c.editor(pid)
        if (ctl.dirtyTabs().isNotEmpty()) {
            Dialogs.info(this, "Unsaved edits are open", "Save or close the unsaved files in the editor first, so nothing is lost.")
            return
        }
        Dialogs.confirm(this, "Restore \"${s.label}\"?", "Project files are replaced with the snapshot's versions; files created later are removed. A \"Before restore\" snapshot is taken first so you can undo this.", "Restore", destructive = true) {
            bg({ store.restore(s.id, root) }) { r ->
                r.onSuccess {
                    ctl.reloadIfClean(null)
                    toastLong("Restored. Undo by restoring the \"Before restore\" snapshot.")
                    refresh()
                }.onFailure { showError(it, "Restore failed — nothing was changed") }
            }
        }
    }

    private fun delete(s: Snapshot) {
        Dialogs.confirm(this, "Delete \"${s.label}\"?", "This snapshot cannot be recovered afterwards.", "Delete", destructive = true) {
            bg({ store.delete(s.id); store.gc() }) { r -> r.onFailure { showError(it) }; refresh() }
        }
    }
}
