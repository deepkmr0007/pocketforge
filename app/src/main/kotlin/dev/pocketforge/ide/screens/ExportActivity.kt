package dev.pocketforge.ide.screens

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.ide.platform.ApkTools
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.RowAdapter

/**
 * Export with an editable manifest: build outputs, caches and secret-looking files are excluded by
 * default (with the reason shown). Saves through Android's document picker or shares directly.
 */
class ExportActivity : BaseActivity() {
    private lateinit var pid: String
    private var name = "project"
    private var entries: List<ManifestEntry> = emptyList()
    private lateinit var adapter: RowAdapter<ManifestEntry>
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        name = runCatching { c.projects.get(pid).name }.getOrDefault("project")
        summary = ui.caption("Scanning files…")
        val header = ui.vertical(12) {
            addView(ui.caption("Tap a file to include or exclude it. Excluded by default: build outputs, caches, signing keys, .env files and files that look like they contain secrets. A BUILD_INSTRUCTIONS.md for Android Studio / Gradle is added."))
            addView(ui.space(8))
            addView(summary)
            addView(ui.space(8))
            addView(ui.horizontal {
                addView(ui.button("Save ZIP…", Btn.PRIMARY) { saveViaPicker() }, ui.lp(0, weight = 1f))
                addView(ui.space(0), ui.lp(ui.dp(8), 1))
                addView(ui.button("Share…", Btn.SECONDARY) { share() }, ui.lp(0, weight = 1f))
            })
        }
        adapter = RowAdapter(emptyList()) { e, _, convert -> row(e, convert) }
        val list = ui.list().apply {
            adapter = this@ExportActivity.adapter
            addHeaderView(header, null, false)
            setOnItemClickListener { _, _, pos, _ ->
                val e = this@ExportActivity.adapter.getItem(pos - headerViewsCount)
                entries = entries.map { if (it.path == e.path) it.copy(include = !it.include) else it }
                this@ExportActivity.adapter.set(entries)
                updateSummary()
            }
        }
        setScreen(ui.vertical {
            addView(ui.topBar("Export \"$name\"", "ZIP for a computer, backup or sharing", { finish() }), ui.lp())
            addView(ui.divider())
            addView(list, ui.lp(h = 0, weight = 1f))
        })
        bg({ dev.pocketforge.engine.archive.ExportManifest.build(c.projects.dirOf(pid)) }) { r ->
            r.onSuccess { entries = it; adapter.set(it); updateSummary() }.onFailure { showError(it) }
        }
    }

    private fun updateSummary() {
        val inc = entries.filter { it.include }
        summary.text = "${inc.size} of ${entries.size} files included · ${humanBytes(inc.sumOf { it.sizeBytes })}"
    }

    private fun row(e: ManifestEntry, convert: View?): View {
        val v = (convert as? LinearLayout) ?: ui.horizontal(10) {
            addView(ui.text("", 18f).apply { tag = "box"; minWidth = ui.dp(32) })
            addView(ui.vertical {
                addView(ui.body("").apply { tag = "path"; maxLines = 2 })
                addView(ui.caption("").apply { tag = "why" })
            }, ui.lp(0, weight = 1f))
        }
        v.findViewWithTag<TextView>("box").apply { text = if (e.include) "☑" else "☐"; setTextColor(if (e.include) p.accent else p.muted) }
        v.findViewWithTag<TextView>("path").text = e.path
        v.findViewWithTag<TextView>("why").text = humanBytes(e.sizeBytes) + (e.reason?.let { " · excluded by default: $it" } ?: "")
        v.contentDescription = "${e.path}, ${if (e.include) "included" else "excluded"}"
        return v
    }

    private fun zipName() = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + ".zip"

    private fun saveViaPicker() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, zipName())
        runCatching { startActivityForResult(i, REQ_SAVE) }.onFailure { toastLong("No document picker is available.") }
    }

    private fun share() {
        val m = entries
        bg({
            ApkTools.stageForShare(this, zipName()) { os -> c.builds.exportForDesktop(pid, c.projects.dirOf(pid), m, name, os) }
        }) { r -> r.onSuccess { f -> runCatching { ApkTools.share(this, f, "application/zip") }.onFailure { showError(it) } }.onFailure { showError(it) } }
    }

    @Deprecated("Framework Activity result API (ADR-0007).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val m = entries
        bg({
            var attempt: dev.pocketforge.engine.build.BuildAttempt? = null
            SafImport.writeTo(this, uri) { os -> attempt = c.builds.exportForDesktop(pid, c.projects.dirOf(pid), m, name, os) }
            attempt ?: throw dev.pocketforge.engine.common.PfException("export.failed", "Export did not complete.")
        }) { r ->
            r.onSuccess { a -> toastLong(a.message) }.onFailure { showError(it, "Export failed") }
        }
    }

    companion object { private const val REQ_SAVE = 21 }
}
