package dev.pocketforge.ide.screens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import dev.pocketforge.engine.build.BuildAttempt
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.projects.Project
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.engine.toolchain.Capability
import dev.pocketforge.engine.toolchain.CapabilityEvaluator
import dev.pocketforge.ide.BuildInfo
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Home dashboard: projects, create/import, recovered drafts, recent builds, honest device status. */
class HomeActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    private data class HomeData(
        val projects: List<Project>,
        val drafts: List<RecoveryJournal.Draft>,
        val builds: List<BuildAttempt>,
        val caps: List<Capability>,
        val freeBytes: Long,
        val online: Boolean,
        val crashedAt: Long?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = ui.topBar("PocketForge", "Offline-first mobile IDE · v${BuildInfo.VERSION_NAME}", null, listOf(
            ui.iconButton("?", "Help and diagnostics") { Nav.help(this) },
            ui.iconButton("⚙", "Settings") { Nav.settings(this) },
        ))
        val (root, column) = ui.page(bar)
        col = column
        setScreen(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        bg({
            val s = c.settings.value
            val facts = c.probe.facts(s, c.aiProvider() != null)
            val marker = File(c.dataRoot, "last-crash.marker")
            HomeData(
                projects = c.projects.list(),
                drafts = c.recovery.drafts(),
                builds = c.buildHistory.list().take(3),
                caps = CapabilityEvaluator.evaluate(facts),
                freeBytes = c.probe.freeStorageBytes(),
                online = c.probe.isOnline(),
                crashedAt = marker.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull(),
            )
        }) { r -> r.onSuccess(::render).onFailure { showError(it) } }
    }

    private fun render(d: HomeData) {
        col.removeAllViews()
        val s = c.settings.value

        // Status: what works right now, stated plainly.
        col.addView(ui.card {
            addView(ui.horizontal {
                addView(ui.chip(if (s.networkBlocked) "NETWORK BLOCKED" else "NETWORK: ASK EVERY TIME", if (s.networkBlocked) p.ok else p.info))
                addView(ui.space(0), ui.lp(ui.dp(8), 1))
                addView(ui.chip(if (d.online) "DEVICE ONLINE" else "DEVICE OFFLINE", p.muted))
            })
            addView(ui.space(8))
            addView(ui.caption("Projects stay on this phone. Nothing is uploaded unless you approve a specific request. ${humanBytes(d.freeBytes)} free."))
            val unavailable = d.caps.count { it.status == CapStatus.UNAVAILABLE }
            if (unavailable > 0) addView(ui.button("$unavailable features unavailable on this device — see why", Btn.TEXT) { Nav.help(this@HomeActivity, "capabilities") })
        })

        if (d.crashedAt != null) col.addView(ui.card {
            addView(ui.text("PocketForge closed unexpectedly", 16f, p.warn, bold = true))
            addView(ui.caption("At ${DateFormat.getDateTimeInstance().format(Date(d.crashedAt))}. Unsaved edits were journaled and are listed below if any. A redacted trace is in Help → Logs (never uploaded)."))
            addView(ui.button("Dismiss", Btn.TEXT) { File(c.dataRoot, "last-crash.marker").delete(); refresh() })
        })

        // Primary actions.
        col.addView(ui.horizontal {
            addView(ui.button("＋ New project", Btn.PRIMARY) { Nav.newProject(this@HomeActivity) }, ui.lp(0, weight = 1f))
            addView(ui.space(0), ui.lp(ui.dp(8), 1))
            addView(ui.button("Import…", Btn.SECONDARY) {
                Dialogs.choose(this@HomeActivity, "Import a project", listOf("ZIP archive", "Folder (copied into PocketForge)")) { i ->
                    if (i == 0) pickZip() else pickFolder()
                }
            }, ui.lp(0, weight = 1f))
        })

        if (d.drafts.isNotEmpty()) {
            col.addView(ui.section("Recovered unsaved work"))
            col.addView(ui.card {
                addView(ui.caption("These edits were saved to the recovery journal before PocketForge stopped. Open the file to restore, compare or discard."))
                d.drafts.take(8).forEach { dr ->
                    val pname = d.projects.firstOrNull { it.id == dr.projectId }?.name
                    if (pname == null) return@forEach
                    addView(ui.button("${dr.relativePath.substringAfterLast('/')} · $pname · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dr.savedAtMillis))}", Btn.TEXT) {
                        Nav.project(this@HomeActivity, dr.projectId, dr.relativePath)
                    })
                }
            })
        }

        col.addView(ui.section("Projects"))
        if (d.projects.isEmpty()) {
            col.addView(ui.card {
                addView(ui.body("No projects yet."))
                addView(ui.caption("Create one from a template (Android, web, Python, Kotlin) or import a ZIP or folder. Everything stays on this phone."))
            })
        }
        d.projects.forEach { proj -> col.addView(projectRow(proj, s.trustedProjects.contains(proj.id))) }

        if (d.builds.isNotEmpty()) {
            col.addView(ui.section("Recent builds"))
            d.builds.forEach { b ->
                val color = when (b.status) { BuildStatus.SUCCESS -> p.ok; BuildStatus.EXPORTED -> p.info; BuildStatus.CANCELLED -> p.muted; else -> p.danger }
                col.addView(ui.card(10) {
                    addView(ui.text("${b.status.name} · ${b.backend}", 14f, color, bold = true))
                    addView(ui.caption(b.message, p.text).apply { maxLines = 3 })
                    setOnClickListener { Nav.build(this@HomeActivity, b.projectId) }
                })
            }
        }

        col.addView(ui.section("Tools"))
        col.addView(ui.horizontal {
            addView(ui.button("Build center", Btn.SECONDARY) { Nav.build(this@HomeActivity, null) }, ui.lp(0, weight = 1f))
            addView(ui.space(0), ui.lp(ui.dp(8), 1))
            addView(ui.button("AI assistant", Btn.SECONDARY) { Nav.ai(this@HomeActivity, null) }, ui.lp(0, weight = 1f))
        })
        col.addView(ui.space(8))
        col.addView(ui.horizontal {
            addView(ui.button("Privacy", Btn.SECONDARY) { Nav.privacy(this@HomeActivity) }, ui.lp(0, weight = 1f))
            addView(ui.space(0), ui.lp(ui.dp(8), 1))
            addView(ui.button("Help", Btn.SECONDARY) { Nav.help(this@HomeActivity) }, ui.lp(0, weight = 1f))
        })
        col.addView(ui.space(24))
    }

    private fun projectRow(proj: Project, trusted: Boolean): View = ui.card(12) {
        addView(ui.text(proj.name, 16f, bold = true, maxLines = 1))
        val pkg = proj.packageId ?: proj.templateId
        addView(ui.caption("${proj.language} · $pkg · opened ${DateFormat.getDateInstance().format(Date(proj.lastOpenedAt))}" + if (proj.templateId == "imported" && !trusted) " · imported (untrusted)" else ""))
        isClickable = true; isFocusable = true
        background = ui.ripple(ui.rounded(p.surface, 14, p.border), 14)
        contentDescription = "Open project ${proj.name}"
        setOnClickListener { Nav.project(this@HomeActivity, proj.id) }
        setOnLongClickListener { projectMenu(proj, trusted); true }
        addView(ui.horizontal {
            addView(ui.button("Open", Btn.TEXT) { Nav.project(this@HomeActivity, proj.id) })
            addView(ui.button("More…", Btn.TEXT) { projectMenu(proj, trusted) })
        })
    }

    private fun projectMenu(proj: Project, trusted: Boolean) {
        val items = listOf("Rename", "Snapshots", "Export ZIP", "Build center", if (trusted) "Mark as untrusted" else "Mark as trusted (terminal asks less)", "Move to trash")
        Dialogs.choose(this, proj.name, items) { i ->
            when (i) {
                0 -> Dialogs.input(this, "Rename project", "Name", proj.name, "Rename", validate = { dev.pocketforge.engine.projects.ProjectNames.validate(it) }) { n ->
                    bg({ c.projects.rename(proj.id, n) }) { r -> r.onFailure { showError(it) }; refresh() }
                }
                1 -> Nav.snapshots(this, proj.id)
                2 -> Nav.export(this, proj.id)
                3 -> Nav.build(this, proj.id)
                4 -> { c.settings.update { s -> s.copy(trustedProjects = if (trusted) s.trustedProjects - proj.id else s.trustedProjects + proj.id) }; refresh() }
                5 -> Dialogs.confirm(this, "Move \"${proj.name}\" to trash?", "You can restore it from Privacy → Trash until you empty the trash.", "Move to trash", destructive = true) {
                    c.dropEditor(proj.id)
                    bg({ c.projects.moveProjectToTrash(proj.id) }) { r -> r.onSuccess { toast("Moved to trash") }.onFailure { showError(it) }; refresh() }
                }
            }
        }
    }

    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        runCatching { startActivityForResult(i, REQ_ZIP) }.onFailure { toastLong("No document picker is available on this phone.") }
    }

    private fun pickFolder() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE) }.onFailure { toastLong("No folder picker is available on this phone.") }
    }

    @Deprecated("Framework Activity result API; AndroidX is intentionally not used (ADR-0007).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        toast("Importing…")
        when (requestCode) {
            REQ_ZIP -> bg({ SafImport.importZip(this, c.projects, uri) }) { r -> r.onSuccess { p -> imported(p) }.onFailure { showError(it, "Import failed") } }
            REQ_TREE -> bg({ SafImport.importTree(this, c.projects, uri) }) { r -> r.onSuccess { p -> imported(p) }.onFailure { showError(it, "Import failed") } }
        }
    }

    private fun imported(p: Project) {
        c.log.info("import", "imported project ${p.id.take(8)}")
        Dialogs.info(this, "Imported \"${p.name}\"", "The project was copied into PocketForge's private storage. Imported projects are untrusted by default: the terminal asks before every command, and build scripts only run on a Build Host or in Termux after you approve.", "Open") {
            Nav.project(this, p.id)
        }
        refresh()
    }

    companion object {
        private const val REQ_ZIP = 11
        private const val REQ_TREE = 12
    }
}
