package dev.pocketforge.ide.screens

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.diagnostics.DiagnosticReport
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.engine.toolchain.CapabilityEvaluator
import dev.pocketforge.ide.BuildInfo
import dev.pocketforge.ide.platform.ApkTools
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.Markdown

/** Offline help pages, the live capability matrix, device facts, local logs and a redacted diagnostic report. */
class HelpActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, column) = ui.page(ui.topBar("Help & diagnostics", "Works offline", { finish() }))
        col = column
        setScreen(page)
        render()
        when (intent.getStringExtra(Nav.PATH)) { "capabilities" -> showCapabilities() }
    }

    private fun pages(): List<String> = runCatching { assets.list("help")?.filter { it.endsWith(".md") }?.sorted() ?: emptyList() }.getOrDefault(emptyList())

    private fun render() {
        col.removeAllViews()
        col.addView(ui.section("Guides"))
        pages().forEach { file ->
            val title = runCatching { assets.open("help/$file").bufferedReader().use { it.readLine() }.removePrefix("#").trim() }.getOrDefault(file)
            col.addView(ui.button(title, Btn.SECONDARY) { openPage(file) }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL })
            col.addView(ui.space(6))
        }
        col.addView(ui.section("This device"))
        col.addView(ui.button("What works on this phone (capability matrix)", Btn.SECONDARY) { showCapabilities() })
        col.addView(ui.space(6))
        col.addView(ui.button("Device facts", Btn.SECONDARY) { showDevice() })
        col.addView(ui.space(6))
        col.addView(ui.button("Test whether downloaded programs can run here", Btn.SECONDARY) {
            bg({ c.probe.runExecProbe() }) { r -> r.onSuccess { (_, msg) -> Dialogs.info(this, "Executable probe", "$msg\n\nThis is a local test with a copy of a system binary; nothing is downloaded.") } }
        })
        col.addView(ui.section("Logs & reports (never uploaded)"))
        col.addView(ui.button("View local log", Btn.SECONDARY) {
            bg({ c.log.tail(400).joinToString("\n") }) { r -> r.onSuccess { Dialogs.text(this, "Local log (redacted)", it.ifEmpty { "(empty)" }, "Clear log" to { c.log.clear(); toast("Log cleared") }) } }
        })
        col.addView(ui.space(6))
        col.addView(ui.button("Diagnostic report…", Btn.SECONDARY) { report() })
        col.addView(ui.space(16))
        col.addView(ui.caption("PocketForge ${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE}). Not affiliated with Google or Android Studio."))
        col.addView(ui.space(24))
    }

    private fun openPage(file: String) {
        bg({ assets.open("help/$file").bufferedReader().use { it.readText() } }) { r ->
            r.onSuccess { md ->
                Dialogs.custom(this, md.lineSequence().first().removePrefix("#").trim(), ui.scroll(ui.vertical(20) {
                    addView(ui.text(Markdown.render(md.substringAfter('\n'), p), 15f).apply { setTextIsSelectable(true) })
                }))
            }.onFailure { showError(it) }
        }
    }

    private fun showCapabilities() {
        bg({ CapabilityEvaluator.evaluate(c.probe.facts(c.settings.value, c.aiProvider() != null)) }) { r ->
            r.onSuccess { caps ->
                Dialogs.custom(this, "What works on this phone", ui.scroll(ui.vertical(16) {
                    addView(ui.caption("Measured on this device right now. \"Experimental\" means implemented but not verified on every phone."))
                    caps.forEach { cap ->
                        val color = when (cap.status) { CapStatus.SUPPORTED -> p.ok; CapStatus.UNAVAILABLE -> p.danger; CapStatus.EXPERIMENTAL -> p.warn; else -> p.info }
                        addView(ui.space(10))
                        addView(ui.horizontal { addView(ui.chip(cap.status.label.uppercase(), color)) })
                        addView(ui.text(cap.title, 15f, bold = true))
                        addView(ui.caption(cap.detail))
                    }
                }))
            }.onFailure { showError(it) }
        }
    }

    private fun showDevice() {
        bg({ c.probe.facts(c.settings.value, c.aiProvider() != null) }) { r ->
            r.onSuccess { f ->
                Dialogs.text(this, "Device facts", """
                    Android ${f.release} (API ${f.apiLevel}) · ${Build.MANUFACTURER} ${Build.MODEL}
                    CPU ABIs: ${f.abis.joinToString()}
                    RAM: ${humanBytes(f.totalRamBytes)} total, ${humanBytes(f.availRamBytes)} available
                    Free app storage: ${humanBytes(f.freeStorageBytes)}
                    App targets API ${f.targetSdk}
                    Shell available: ${f.shellAvailable}
                    Run downloaded executables from app storage: ${if (f.execFromAppDataAllowed) "allowed" else "blocked (Android W^X policy)"}
                    JDK on PATH: ${f.javaOnPath ?: "none"} · Gradle: ${f.gradleOnPath ?: "none"} · Android SDK: ${f.androidSdkPath ?: "none"}
                    Termux installed: ${f.termuxInstalled} · Termux permission: ${f.termuxPermissionGranted}
                    Hardware keyboard: ${f.hardwareKeyboard}
                """.trimIndent())
            }
        }
    }

    private fun report() {
        bg({
            val facts = c.probe.facts(c.settings.value, c.aiProvider() != null)
            DiagnosticReport.build(BuildInfo.VERSION_NAME, facts, CapabilityEvaluator.evaluate(facts), c.log.tail(200), c.settings.value.summary())
        }) { r ->
            r.onSuccess { text ->
                Dialogs.text(this, "Diagnostic report (redacted)", text, "Save / share…" to {
                    Dialogs.choose(this, "Diagnostic report", listOf("Save to a file…", "Share…")) { i ->
                        if (i == 0) { pendingReport = text; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, "pocketforge-report.txt"), REQ_SAVE) }
                        else runCatching { ApkTools.share(this, ApkTools.stageForShare(this, "pocketforge-report.txt") { it.write(text.toByteArray()) }, "text/plain") }.onFailure { showError(it) }
                    }
                })
            }.onFailure { showError(it) }
        }
    }

    private var pendingReport: String? = null

    @Deprecated("Framework Activity result API (ADR-0007).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        val text = pendingReport ?: return
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) bg({ SafImport.writeTo(this, uri) { it.write(text.toByteArray()) } }) { r -> r.onSuccess { toast("Saved") }.onFailure { showError(it) } }
    }

    companion object { private const val REQ_SAVE = 41 }
}
