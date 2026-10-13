package dev.pocketforge.ide

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.pocketforge.engine.ai.AiProvider
import dev.pocketforge.engine.ai.AnthropicProvider
import dev.pocketforge.engine.ai.OpenAiCompatibleProvider
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.FileConsentLog
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.Loopback
import dev.pocketforge.engine.consent.UrlConnectionTransport
import dev.pocketforge.engine.diagnostics.LocalLog
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.search.ReplaceUndo
import dev.pocketforge.engine.snapshots.SnapshotStore
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.ide.editor.EditorController
import dev.pocketforge.ide.platform.AiProviderKind
import dev.pocketforge.ide.platform.ConsentBroker
import dev.pocketforge.ide.platform.DeviceProbe
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.platform.SettingsStore
import dev.pocketforge.ide.platform.TermuxBridge
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual dependency container (ADR-0005: no DI framework — the graph is small and explicit).
 * Network-capable features receive a [GatedTransport]; there is no other path to the network.
 */
class AppContainer(val app: Application) {
    val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newFixedThreadPool(3, object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "pf-io-${n.incrementAndGet()}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    })

    val dataRoot: File = File(app.filesDir, "pocketforge").apply { mkdirs() }
    val log = LocalLog(File(dataRoot, "logs"))
    val settings = SettingsStore(app)
    val vault = SecretVault(File(dataRoot, "vault.json"))
    val probe = DeviceProbe(app)

    val projects = ProjectManager(File(dataRoot, "workspace"))
    val recovery = RecoveryJournal(File(dataRoot, "recovery"))
    val buildHistory = BuildHistory(File(dataRoot, "builds/history.json"))
    val builds = BuildService(File(app.filesDir, "builds"), buildHistory)

    val consentBroker = ConsentBroker()
    val consentLog = FileConsentLog(File(dataRoot, "consent-log.jsonl"))
    val consentGateway = ConsentGateway(
        prompter = consentBroker,
        log = consentLog,
        networkAllowed = { !settings.value.networkBlocked },
    )

    /** Transport for AI providers (platform trust store; plain HTTP only to 127.0.0.1). */
    val gatedTransport = GatedTransport(consentGateway, UrlConnectionTransport())

    /** Transport for the paired Build Host: pinned to its certificate when a fingerprint was entered. */
    fun buildHostTransport(): GatedTransport =
        GatedTransport(consentGateway, UrlConnectionTransport(readTimeoutMs = 25 * 60_000, pinnedSha256 = settings.value.buildHost?.certSha256))

    val termux = TermuxBridge(this)

    fun workspace(projectId: String) = Workspace(projects.dirOf(projectId), largeFilePolicy())
    fun largeFilePolicy() = LargeFilePolicy(fullEditMaxBytes = settings.value.largeFileEditMb * 1024L * 1024)
    fun snapshots(projectId: String) = SnapshotStore(File(dataRoot, "snapshots/$projectId"))

    /** Editor state per project survives rotation and navigation (not process death — that is the journal's job). */
    private val editors = HashMap<String, EditorController>()
    fun editor(projectId: String): EditorController = synchronized(editors) { editors.getOrPut(projectId) { EditorController(this, projectId) } }
    fun dropEditor(projectId: String) = synchronized(editors) { editors.remove(projectId)?.shutdown() }
    fun openEditors(): List<EditorController> = synchronized(editors) { editors.values.toList() }

    /** Last project-wide replace, kept so the user can undo it. */
    val replaceUndo = HashMap<String, ReplaceUndo>()

    /** Text handed from another screen (e.g. a build log) to the AI screen as proposed context. */
    @Volatile var aiHandoff: String? = null

    fun aiProvider(): AiProvider? {
        val s = settings.value
        return when (s.aiProvider) {
            AiProviderKind.NONE -> null
            AiProviderKind.ANTHROPIC -> vault.get(SecretVault.AI_KEY)?.let { AnthropicProvider(it, s.aiModel.ifBlank { "claude-sonnet-5-5" }) }
            AiProviderKind.OPENAI_COMPATIBLE -> s.aiBaseUrl.takeIf { url ->
                url.startsWith("https://") || runCatching { url.startsWith("http://") && Loopback.isLoopbackHost(java.net.URI(url).host) }.getOrDefault(false)
            }?.let { OpenAiCompatibleProvider(it, vault.get(SecretVault.AI_KEY), s.aiModel) }
        }
    }

    /** Metadata of AI sessions (never the content): what was asked of whom, sizes, and how many changes were applied. */
    fun logAiSession(action: String, provider: String, local: Boolean, requestSha: String, requestBytes: Int, responseChars: Int, proposals: Int) {
        runCatching {
            val f = File(dataRoot, "ai-sessions.jsonl")
            f.appendText(Json.stringify(Json.obj("t" to System.currentTimeMillis(), "a" to action, "p" to provider, "local" to local, "h" to requestSha, "b" to requestBytes, "r" to responseChars, "n" to proposals)) + "\n")
        }
    }

    fun aiSessionCount(): Int = runCatching { File(dataRoot, "ai-sessions.jsonl").readLines().size }.getOrDefault(0)

    fun runIo(block: () -> Unit) { io.execute { try { block() } catch (t: Throwable) { log.error("io", "background task failed", t) } } }

    /** "Delete local app data": projects, snapshots, drafts, logs, history, settings, secrets and the Keystore key. */
    fun deleteAllLocalData() {
        synchronized(editors) { editors.values.forEach { it.shutdown() }; editors.clear() }
        vault.destroy()
        settings.clear()
        dataRoot.deleteRecursively()
        File(app.filesDir, "builds").deleteRecursively()
        app.cacheDir.deleteRecursively()
    }
}
