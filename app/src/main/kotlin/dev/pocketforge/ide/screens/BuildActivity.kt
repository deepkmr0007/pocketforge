package dev.pocketforge.ide.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketforge.engine.ai.ErrorExplainer
import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.build.ApkInfo
import dev.pocketforge.engine.build.ApkInspector
import dev.pocketforge.engine.build.BuildAttempt
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.build.LiteBuildDescriptor
import dev.pocketforge.engine.build.TermuxProtocol
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.engine.toolchain.CapabilityEvaluator
import dev.pocketforge.ide.platform.ApkTools
import dev.pocketforge.ide.platform.PlatformApkInfo
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.platform.TermuxBridge
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.value
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Build center. Every result shown here comes from a real run: SUCCESS is recorded only after an
 * APK was received, re-hashed and parsed. No progress bars are simulated; while waiting we show
 * elapsed time only.
 */
class BuildActivity : BaseActivity() {
    private var pid: String? = null
    private var projectName = ""
    private lateinit var col: LinearLayout
    private var hostRunning: String? = null
    private var hostSince = 0L
    private val tick = object : Runnable { override fun run() { if (hostRunning != null || c.termux.pending != null) { refreshRunning(); c.main.postDelayed(this, 1000) } } }
    private var runningLabel: TextView? = null
    private val termuxListener: (BuildAttempt) -> Unit = { a -> refresh(); showAttempt(a) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT)
        projectName = pid?.let { runCatching { c.projects.get(it).name }.getOrNull() } ?: ""
        val (page, column) = ui.page(ui.topBar("Build center", if (pid != null) projectName else "All projects", { finish() }))
        col = column
        setScreen(page)
        c.termux.addListener(termuxListener)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() { c.termux.removeListener(termuxListener); c.main.removeCallbacks(tick); super.onDestroy() }

    private data class Data(val caps: List<dev.pocketforge.engine.toolchain.Capability>, val history: List<BuildAttempt>, val tokenSet: Boolean, val lite: Boolean, val termuxInstalled: Boolean, val termuxPerm: Boolean)

    private fun refresh() {
        val project = pid
        bg({
            val facts = c.probe.facts(c.settings.value, c.aiProvider() != null)
            Data(
                CapabilityEvaluator.evaluate(facts).filter { it.id.startsWith("build") || it.id == "apk.inspect" },
                c.buildHistory.list().filter { project == null || it.projectId == project },
                c.vault.has(SecretVault.HOST_TOKEN),
                project != null && File(c.projects.dirOf(project), LiteBuildDescriptor.FILE).isFile,
                facts.termuxInstalled, facts.termuxPermissionGranted,
            )
        }) { r -> r.onSuccess(::render).onFailure { showError(it) } }
    }

    private fun render(d: Data) {
        col.removeAllViews()
        col.addView(ui.section("What can build here"))
        col.addView(ui.card {
            d.caps.forEach { cap ->
                val color = when (cap.status) { CapStatus.SUPPORTED -> p.ok; CapStatus.UNAVAILABLE -> p.danger; CapStatus.EXPERIMENTAL -> p.warn; else -> p.info }
                addView(ui.horizontal { addView(ui.chip(cap.status.label.uppercase(), color)) })
                addView(ui.text(cap.title, 14f, bold = true).apply { setPadding(0, ui.dp(4), 0, 0) })
                addView(ui.caption(cap.detail).apply { setPadding(0, 0, 0, ui.dp(10)) })
            }
        })
        runningLabel = ui.text("", 14f, p.warn, bold = true)
        col.addView(runningLabel)
        refreshRunning()

        val project = pid
        if (project != null) {
            col.addView(ui.section("On this phone (Termux) · experimental"))
            col.addView(ui.card {
                addView(ui.caption(
                    "Builds android-lite projects with aapt2 → javac → d8 → apksigner inside Termux, which you install and set up yourself. " +
                        "PocketForge copies the project to a shared folder you pick, asks Termux to run a generated script you can read first, then verifies the APK.",
                ))
                addView(ui.space(6))
                addView(ui.caption("Termux installed: ${yes(d.termuxInstalled)} · permission: ${yes(d.termuxPerm)} · shared folder: ${yes(c.termux.treeUri() != null)}${if (c.termux.treeUri() != null) " (${c.settings.value.termuxPath})" else ""}", p.text))
                if (!d.lite) addView(ui.caption("This project has no pocketforge.json, so it is not an android-lite project. Use a Build Host or Export instead.", p.warn))
                addView(ui.horizontal {
                    addView(ui.button("Setup guide", Btn.TEXT) { termuxGuide() })
                    addView(ui.button("Choose folder", Btn.TEXT) { pickTermuxFolder() })
                })
                addView(ui.button("Build debug APK on this phone", Btn.PRIMARY, enabled = d.lite && d.termuxInstalled && c.termux.pending == null) { startTermuxBuild(project) })
            })

            col.addView(ui.section("Your Build Host (computer you control)"))
            col.addView(ui.card {
                val host = c.settings.value.buildHost
                if (host == null || !d.tokenSet) {
                    addView(ui.caption("Run tools/build-host/pocketforge_build_host.py on your own computer (Python 3, no extra packages), then pair it here. Your code goes only to that machine, and only after you approve each build."))
                    addView(ui.button("Pair Build Host", Btn.SECONDARY) { pairHost() })
                } else {
                    addView(ui.text("${host.name}", 15f, bold = true))
                    addView(ui.caption(host.baseUrl + (host.certSha256?.let { "\nCertificate pinned: ${it.take(16)}…" } ?: "\nUses the platform's trusted certificates")))
                    addView(ui.horizontal {
                        addView(ui.button("Debug APK", Btn.PRIMARY, enabled = hostRunning == null) { reviewRemote(project, "assembleDebug") }, ui.lp(0, weight = 1f))
                        addView(ui.space(0), ui.lp(ui.dp(8), 1))
                        addView(ui.button("Release", Btn.SECONDARY, enabled = hostRunning == null) { reviewRemote(project, "assembleRelease") }, ui.lp(0, weight = 1f))
                    })
                    addView(ui.horizontal {
                        addView(ui.button("Lint", Btn.TEXT, enabled = hostRunning == null) { reviewRemote(project, "lintDebug") })
                        addView(ui.button("Unit tests", Btn.TEXT, enabled = hostRunning == null) { reviewRemote(project, "testDebugUnitTest") })
                        addView(ui.button("Unpair", Btn.TEXT) { unpair() })
                    })
                }
            })

            col.addView(ui.section("On a computer"))
            col.addView(ui.card {
                addView(ui.caption("Export a clean ZIP (no secrets, no build outputs) with BUILD_INSTRUCTIONS.md for Android Studio or Gradle."))
                addView(ui.button("Export project…", Btn.SECONDARY) { Nav.export(this@BuildActivity, project) })
            })
        }

        col.addView(ui.section("Inspect an APK"))
        col.addView(ui.card {
            addView(ui.caption("Reads package, version, SDK levels, debug/release and signatures — offline. Installing always goes through Android's own installer and confirmation."))
            addView(ui.button("Pick an APK…", Btn.SECONDARY) { pickApk() })
        })

        col.addView(ui.section("History (${d.history.size})"))
        if (d.history.isEmpty()) col.addView(ui.caption("No builds yet."))
        d.history.take(50).forEach { a -> col.addView(historyRow(a)) }
        col.addView(ui.space(24))
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun refreshRunning() {
        val l = runningLabel ?: return
        val tp = c.termux.pending
        l.text = when {
            hostRunning != null -> "Running $hostRunning on your Build Host… ${(System.currentTimeMillis() - hostSince) / 1000}s (waiting for the host; no estimated progress is shown)"
            tp != null -> "Running ${tp.kind.name.lowercase()} in Termux… ${(System.currentTimeMillis() - tp.startedAt) / 1000}s. Termux shows a notification while it works."
            else -> ""
        }
        l.visibility = if (l.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (l.text.isNotEmpty()) { c.main.removeCallbacks(tick); c.main.postDelayed(tick, 1000) }
    }

    private fun historyRow(a: BuildAttempt) = ui.card(12) {
        val color = when (a.status) { BuildStatus.SUCCESS -> p.ok; BuildStatus.EXPORTED -> p.info; BuildStatus.CANCELLED -> p.muted; else -> p.danger }
        addView(ui.text("${a.status.name} · ${a.task} · ${a.backend}", 14f, color, bold = true))
        addView(ui.caption(a.message, p.text))
        addView(ui.caption("${DateFormat.getDateTimeInstance().format(Date(a.startedAt))} · ${((a.finishedAt - a.startedAt) / 1000).coerceAtLeast(0)} s" +
            (a.apk?.let { " · APK ${humanBytes(it.sizeBytes)} · SHA-256 ${it.sha256.take(12)}…" } ?: "")))
        addView(ui.horizontal {
            if (a.logFile != null) addView(ui.button("Log", Btn.TEXT) { openLog(a) })
            if (a.artifactPath != null) addView(ui.button("Inspect / install", Btn.TEXT) { inspectArtifact(a) })
        })
    }

    private fun showAttempt(a: BuildAttempt) {
        Dialogs.info(this, if (a.status == BuildStatus.SUCCESS) "Build succeeded" else "Build ${a.status.name.lowercase()}", a.message)
    }

    // ---------------------------------------------------------------- Termux

    private fun termuxGuide() {
        val text = """
            |1. Install Termux from F-Droid or its GitHub releases (the Play Store version is outdated).
            |2. Open Termux and run:
            |
            |${TermuxProtocol.SETUP_COMMANDS}
            |
            |3. Provide an android.jar (API ${35}) at ~/.pocketforge/android.jar, or set ANDROID_JAR in Termux. It comes from the Android SDK platform package on a computer (Android SDK license applies).
            |4. Restart Termux, then in PocketForge tap "Choose folder" and pick (or create) a folder such as Internal storage › PocketForge.
            |5. Start a build. Android asks once whether PocketForge may run commands in Termux.
            |
            |What runs: only the script shown before each build. It never downloads or installs anything; missing tools are listed instead.
        """.trimMargin()
        Dialogs.text(this, "On-device builds with Termux", text)
    }

    private fun pickTermuxFolder() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE) }.onFailure { toastLong("No folder picker is available.") }
    }

    private fun startTermuxBuild(project: String) {
        if (!c.termux.isInstalled()) { termuxGuide(); return }
        if (checkSelfPermission(TermuxProtocol.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Dialogs.confirm(this, "Allow PocketForge to ask Termux to run commands?", "Android will show a permission prompt. PocketForge only ever asks Termux to run the build script you can review before each build.", "Continue") {
                requestPermissions(arrayOf(TermuxProtocol.PERMISSION), REQ_PERM)
            }
            return
        }
        if (c.termux.treeUri() == null) { pickTermuxFolder(); return }
        val script = runCatching { c.termux.buildScriptFor(c.projects.dirOf(project)) }.getOrElse { showError(it); return }
        val folder = TermuxProtocol.projectFolderName(projectName, project)
        Dialogs.text(this, "Script Termux will run", script, "Start build" to {
            Dialogs.confirm(this, "Start the build in Termux?",
                "PocketForge copies the project (secrets and build outputs excluded) to ${c.settings.value.termuxPath}/$folder, replacing that folder, then asks Termux to run the script you just read. Termux runs it with Termux's own permissions.",
                "Start") {
                bg({ c.termux.start(project, projectName, TermuxBridge.Kind.BUILD, script, "build") }) { r ->
                    r.onSuccess { toast("Started in Termux"); refresh() }.onFailure { showError(it, "Could not start the build") }
                }
            }
        })
    }

    @Deprecated("Framework permission callback (ADR-0007).")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { toast("Permission granted"); pid?.let { startTermuxBuild(it) } }
            else Dialogs.info(this, "Permission not granted", "On-device builds need it. You can grant it later in Android Settings › Apps › PocketForge › Permissions › Additional permissions.")
            refresh()
        }
    }

    // ---------------------------------------------------------------- Build Host

    private fun pairHost() {
        val name = ui.input("Name", "My computer")
        val url = ui.input("https://192.168.1.20:8765", monoFont = true)
        val fp = ui.input("Certificate SHA-256 (64 hex)", monoFont = true)
        val token = ui.input("Pairing token", monoFont = true, password = true)
        val err = ui.caption("", p.danger)
        val v = ui.vertical(20) {
            addView(ui.caption("Copy these from the Build Host's terminal output. The token is encrypted with the Android Keystore; the fingerprint pins the host's identity."))
            listOf(name, url, fp, token).forEach { addView(ui.space(8)); addView(it, ui.lp()) }
            addView(err)
        }
        val d = Dialogs.custom(this, "Pair Build Host", ui.scroll(v), "Pair")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val f = fp.value().lowercase().replace(":", "").replace(" ", "")
            when {
                !url.value().startsWith("https://") -> err.text = "The URL must start with https://"
                f.isNotEmpty() && !f.matches(Regex("[0-9a-f]{64}")) -> err.text = "Fingerprint must be 64 hex characters (SHA-256)."
                token.value().length < 16 -> err.text = "Paste the full pairing token."
                else -> bg({ c.vault.put(SecretVault.HOST_TOKEN, token.value().trim()) }) { r ->
                    r.onSuccess {
                        c.settings.update { s -> s.copy(hostName = name.value().ifBlank { "Build Host" }, hostUrl = url.value().trim().trimEnd('/'), hostCertSha256 = f) }
                        d.dismiss(); toast("Paired. Nothing has been sent yet."); refresh()
                    }.onFailure { e -> err.text = "Could not store the token securely: ${e.message}" }
                }
            }
        }
    }

    private fun unpair() {
        Dialogs.confirm(this, "Unpair the Build Host?", "Deletes the stored token and address from this phone.", "Unpair", true) {
            c.vault.remove(SecretVault.HOST_TOKEN)
            c.settings.update { it.copy(hostName = "", hostUrl = "", hostCertSha256 = "") }
            refresh()
        }
    }

    private fun reviewRemote(project: String, task: String) {
        bg({ ExportManifest.build(c.projects.dirOf(project)) }) { r ->
            r.onSuccess { m ->
                val labels = m.map { e -> e.path + "  (" + humanBytes(e.sizeBytes) + ")" + (e.reason?.let { " — excluded: $it" } ?: "") }
                val checked = BooleanArray(m.size) { m[it].include }
                Dialogs.multiChoose(this, "Files to upload for $task", labels, checked) { sel ->
                    startRemote(project, task, m.mapIndexed { i, e -> e.copy(include = sel[i]) })
                }
            }.onFailure { showError(it) }
        }
    }

    private fun startRemote(project: String, task: String, m: List<ManifestEntry>) {
        val host = c.settings.value.buildHost ?: run { toast("Pair a Build Host first."); return }
        hostRunning = task; hostSince = System.currentTimeMillis()
        refreshRunning()
        bg({
            val token = c.vault.get(SecretVault.HOST_TOKEN) ?: throw PfException("host.no_token", "Pairing token missing; pair the Build Host again.")
            val root = c.projects.dirOf(project)
            val zip = c.builds.packProject(root, m)
            val consent = c.builds.remoteConsentRequest(host, token, task, zip, m)
            c.builds.buildRemote(project, host, c.consentGateway, c.buildHostTransport(), consent, task)
        }) { r ->
            hostRunning = null
            r.onSuccess { a -> c.log.info("build", "remote $task -> ${a.status}"); showAttempt(a) }.onFailure { showError(it, "Build did not run") }
            refresh()
        }
    }

    // ---------------------------------------------------------------- logs & APKs

    private fun openLog(a: BuildAttempt) {
        bg({ c.builds.logText(a) ?: "(no log)" }) { r ->
            r.onSuccess { text ->
                val hints = ErrorExplainer.explain(text)
                val shown = (if (hints.isNotEmpty()) "Offline hints (rule-based, not AI):\n" + hints.joinToString("\n") { "• ${it.title}: ${it.detail} Fix: ${it.fix}" } + "\n\n" else "") + text.takeLast(200_000)
                Dialogs.text(this, "Build log (redacted)", shown, if (a.status != BuildStatus.SUCCESS) "Ask AI" to {
                    c.aiHandoff = text.takeLast(12_000)
                    Nav.ai(this, a.projectId, action = "EXPLAIN_ERROR")
                } else null)
            }.onFailure { showError(it) }
        }
    }

    private fun inspectArtifact(a: BuildAttempt) {
        bg({
            val f = c.builds.artifactFile(a) ?: throw PfException("apk.missing", "The APK file is no longer available.")
            Triple(f, ApkInspector.inspect(f), ApkTools.platformInfo(this, f))
        }) { r -> r.onSuccess { (f, i, pi) -> showApk(f, i, pi, shareable = true) }.onFailure { showError(it) } }
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        runCatching { startActivityForResult(i, REQ_APK) }.onFailure { toastLong("No document picker is available.") }
    }

    private fun showApk(f: File, i: ApkInfo, pi: PlatformApkInfo?, shareable: Boolean) {
        val tooOld = (i.minSdk ?: 0) > Build.VERSION.SDK_INT
        val text = buildString {
            append("Package: ${i.packageName}\nVersion: ${i.versionName} (${i.versionCode})\n")
            append("SDK: min ${i.minSdk} · target ${i.targetSdk} · this phone ${Build.VERSION.SDK_INT}${if (tooOld) "  ← TOO OLD for this APK" else ""}\n")
            append("Build type: ${i.kind}\nSignature blocks: ${if (i.signaturePresent) i.signatureSchemes.joinToString(", ") else "none (unsigned — cannot be installed)"}\n")
            append("Size: ${humanBytes(i.sizeBytes)} · ${i.entryCount} entries · code: ${if (i.hasDex) "yes" else "NO"}\nSHA-256: ${i.sha256}\n")
            if (pi == null) append("\nAndroid could not parse this APK (it may be corrupt or for a newer Android).\n")
            else {
                append("\nAndroid's check: signers ${pi.signerSha256.joinToString { it.take(16) + "…" }.ifEmpty { "none" }}\n")
                pi.installedVersion?.let { v ->
                    append("Installed now: version $v; ")
                    append(when (pi.installedSignerMatches) { true -> "same signing key — can update."; false -> "DIFFERENT signing key — Android will refuse to update; uninstall first (deletes its data)."; null -> "signer unknown." })
                    append('\n')
                }
            }
            append("\n\"Signed\" means a signature block is present; Android verifies it cryptographically when installing.")
        }
        val canInstall = pi != null && i.signaturePresent && !tooOld
        Dialogs.text(this, "APK details", text, if (canInstall) "Install…" to { install(f) } else if (shareable) "Share" to { runCatching { share(f) }.onFailure { showError(it) } } else null)
    }

    private fun share(f: File) {
        val staged = ApkTools.stageForShare(this, f.name) { os -> f.inputStream().use { it.copyTo(os) } }
        ApkTools.share(this, staged, "application/vnd.android.package-archive")
    }

    private fun install(f: File) {
        if (!ApkTools.canInstall(this)) {
            Dialogs.confirm(this, "Allow installs from PocketForge?", "Android requires you to allow \"Install unknown apps\" for PocketForge once. Android will still ask you to confirm every installation.", "Open settings") {
                runCatching { ApkTools.openInstallPermissionSettings(this) }.onFailure { showError(it) }
            }
            return
        }
        Dialogs.confirm(this, "Install ${f.name}?", "Android's installer will show its own confirmation screen. Only install APKs you built or trust.", "Continue") {
            bg({ ApkTools.install(this, f) }) { r -> r.onSuccess { toast("Handed to Android's installer…") }.onFailure { showError(it) } }
        }
    }

    @Deprecated("Framework Activity result API (ADR-0007).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_APK -> bg({
                val f = SafImport.copyToCache(this, uri, SafImport.displayName(this, uri) ?: "picked.apk")
                Triple(f, ApkInspector.inspect(f), ApkTools.platformInfo(this, f))
            }) { r -> r.onSuccess { (f, i, pi) -> showApk(f, i, pi, shareable = false) }.onFailure { showError(it, "Not a valid APK") } }
            REQ_TREE -> {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }.onFailure { showError(it); return }
                val suggestion = suggestPath(uri) ?: c.settings.value.termuxPath
                Dialogs.input(this, "Folder path as Termux sees it", "/storage/emulated/0/PocketForge", suggestion, "Save",
                    help = "Android's folder picker does not reveal real paths, so confirm where this folder is. Shared storage is /storage/emulated/0/… in Termux (after termux-setup-storage).",
                    validate = { TermuxProtocol.validateWorkspacePath(it) }) { path ->
                    c.settings.update { s -> s.copy(termuxTreeUri = uri.toString(), termuxPath = path.trim().trimEnd('/')) }
                    refresh()
                }
            }
        }
    }

    /** Best-effort: Android's own external-storage provider uses ids like "primary:PocketForge". */
    private fun suggestPath(tree: Uri): String? = runCatching {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (!id.startsWith("primary:")) return null
        "/storage/emulated/0/" + id.removePrefix("primary:")
    }.getOrNull()?.trimEnd('/')

    companion object {
        private const val REQ_APK = 31
        private const val REQ_TREE = 32
        private const val REQ_PERM = 33
        val TASKS = BuildService.ALLOWED_TASKS
    }
}
