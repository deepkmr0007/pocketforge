package dev.pocketforge.ide.feature.builds

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketforge.engine.ai.ErrorExplainer
import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.build.ApkInfo
import dev.pocketforge.engine.build.ApkInspector
import dev.pocketforge.engine.build.BuildAttempt
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.toolchain.Capability
import dev.pocketforge.engine.toolchain.CapabilityEvaluator
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.platform.ApkTools
import dev.pocketforge.ide.platform.PlatformApkInfo
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class InspectedApk(val file: File, val info: ApkInfo, val platform: PlatformApkInfo?)
data class LogView(val attempt: BuildAttempt, val text: String, val hints: List<ErrorExplainer.Explanation>)

data class BuildUi(
    val caps: List<Capability> = emptyList(),
    val history: List<BuildAttempt> = emptyList(),
    val hostTokenSet: Boolean = false,
    val reviewTask: String? = null,
    val manifest: List<ManifestEntry>? = null,
    val exportManifest: List<ManifestEntry>? = null,
    val running: String? = null,
    val runningSince: Long = 0,
    val inspected: InspectedApk? = null,
    val log: LogView? = null,
    val message: String? = null,
    val error: Boolean = false,
)

class BuildViewModel(private val c: AppContainer, private val pid: String) : ViewModel() {
    val state = MutableStateFlow(BuildUi())
    private val root = c.projects.dirOf(pid)

    fun refresh() = viewModelScope.launch {
        io {
            val caps = CapabilityEvaluator.evaluate(c.probe.facts(c.settings.value, c.aiProvider() != null)).filter { it.id.startsWith("build") || it.id == "apk.inspect" }
            Triple(caps, c.buildHistory.list().filter { it.projectId == pid }, c.vault.has(SecretVault.HOST_TOKEN))
        }.onSuccess { (caps, h, tok) -> state.update { it.copy(caps = caps, history = h, hostTokenSet = tok) } }
    }

    fun pairHost(name: String, url: String, fingerprint: String, token: String) = viewModelScope.launch {
        val fp = fingerprint.lowercase().replace(":", "").replace(" ", "")
        when {
            !url.startsWith("https://") -> msg("The Build Host URL must start with https://", true)
            fp.isNotEmpty() && !fp.matches(Regex("[0-9a-f]{64}")) -> msg("Fingerprint must be 64 hex characters (SHA-256).", true)
            token.length < 16 -> msg("Paste the full pairing token shown by the Build Host.", true)
            else -> {
                io { c.vault.put(SecretVault.HOST_TOKEN, token) }.onFailure { e -> msg("Could not store the token securely: ${e.message}", true); return@launch }
                c.settingsRepo.update { it.copy(hostName = name.ifBlank { "Build Host" }, hostUrl = url.trimEnd('/'), hostCertSha256 = fp) }
                msg("Paired with ${name.ifBlank { "Build Host" }}. Nothing has been sent yet.", false); refresh()
            }
        }
    }

    fun unpair() = viewModelScope.launch {
        c.vault.remove(SecretVault.HOST_TOKEN)
        c.settingsRepo.update { it.copy(hostName = "", hostUrl = "", hostCertSha256 = "") }
        refresh()
    }

    /** Step 1: show exactly which files would be uploaded (editable). */
    fun review(task: String) = viewModelScope.launch {
        io { ExportManifest.build(root) }.onSuccess { m -> state.update { it.copy(reviewTask = task, manifest = m) } }.onFailure { e -> msg(e.message, true) }
    }
    fun toggle(path: String) = state.update { s -> s.copy(manifest = s.manifest?.map { if (it.path == path) it.copy(include = !it.include) else it }) }
    fun cancelReview() = state.update { it.copy(reviewTask = null, manifest = null) }

    /** Step 2: one consented upload; the result is verified before it is called a success. */
    fun startRemote() {
        val s = state.value
        val task = s.reviewTask ?: return
        val m = s.manifest ?: return
        val host = c.settings.value.buildHost ?: run { msg("Pair a Build Host first.", true); return }
        state.update { it.copy(reviewTask = null, manifest = null, running = task, runningSince = System.currentTimeMillis()) }
        viewModelScope.launch {
            io {
                val token = c.vault.get(SecretVault.HOST_TOKEN) ?: throw PfException("host.no_token", "Pairing token missing; pair the Build Host again.")
                val zip = c.builds.packProject(root, m)
                val consent = c.builds.remoteConsentRequest(host, token, task, zip, m)
                c.builds.buildRemote(pid, host, c.consentGateway, c.buildHostTransport(), consent, task)
            }.onSuccess { a ->
                c.log.info("build", "remote $task -> ${a.status}")
                state.update { it.copy(running = null) }
                msg(a.message, a.status != BuildStatus.SUCCESS)
                refresh()
            }.onFailure { e -> state.update { it.copy(running = null) }; msg(e.message, true) }
        }
    }

    /** Honest cancel: stop waiting here. The Build Host may still finish; its result is recorded if it arrives. */
    fun stopWaiting() = state.update { it.copy(running = null, message = "Stopped waiting. If the Build Host finishes, the result will appear in history.", error = false) }

    fun prepareExport() = viewModelScope.launch { io { ExportManifest.build(root) }.onSuccess { m -> state.update { it.copy(exportManifest = m) } } }
    fun toggleExport(path: String) = state.update { s -> s.copy(exportManifest = s.exportManifest?.map { if (it.path == path) it.copy(include = !it.include) else it }) }
    fun cancelExport() = state.update { it.copy(exportManifest = null) }
    fun export(ctx: Context, uri: Uri) = viewModelScope.launch {
        val m = state.value.exportManifest ?: return@launch
        val name = io { c.projects.get(pid).name }.getOrDefault("project")
        io { SafImport.writeTo(ctx, uri) { os -> c.builds.exportForDesktop(pid, root, m, name, os) } }
            .onSuccess { a -> state.update { it.copy(exportManifest = null) }; msg(a.message, false); refresh() }
            .onFailure { e -> msg(e.message, true) }
    }

    fun inspectUri(ctx: Context, uri: Uri) = viewModelScope.launch {
        io {
            val f = SafImport.copyToCache(ctx, uri, SafImport.displayName(ctx, uri) ?: "picked.apk")
            InspectedApk(f, ApkInspector.inspect(f), ApkTools.platformInfo(ctx, f))
        }.onSuccess { i -> state.update { it.copy(inspected = i) } }.onFailure { e -> msg("Not a valid APK: ${e.message}", true) }
    }

    fun inspectArtifact(ctx: Context, a: BuildAttempt) = viewModelScope.launch {
        io {
            val f = c.builds.artifactFile(a) ?: throw PfException("apk.missing", "The APK file is no longer available.")
            InspectedApk(f, ApkInspector.inspect(f), ApkTools.platformInfo(ctx, f))
        }.onSuccess { i -> state.update { it.copy(inspected = i) } }.onFailure { e -> msg(e.message, true) }
    }
    fun closeInspect() = state.update { it.copy(inspected = null) }

    fun openLog(a: BuildAttempt) = viewModelScope.launch {
        io { c.builds.logText(a) ?: "(no log)" }.onSuccess { t -> state.update { it.copy(log = LogView(a, t, ErrorExplainer.explain(t))) } }
    }
    fun closeLog() = state.update { it.copy(log = null) }

    fun msg(m: String?, err: Boolean) = state.update { it.copy(message = m, error = err) }

    companion object { val TASKS = BuildService.ALLOWED_TASKS }
}
