package dev.pocketforge.ide

import android.app.Application
import dev.pocketforge.engine.ai.AiProvider
import dev.pocketforge.engine.ai.AnthropicProvider
import dev.pocketforge.engine.ai.OpenAiCompatibleProvider
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.FileConsentLog
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.UrlConnectionTransport
import dev.pocketforge.engine.diagnostics.LocalLog
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.snapshots.SnapshotStore
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.ide.platform.AiProviderKind
import dev.pocketforge.ide.platform.AppSettings
import dev.pocketforge.ide.platform.ConsentBroker
import dev.pocketforge.ide.platform.DeviceProbe
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.platform.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Manual dependency container (ADR-0005: no DI framework — the graph is small and explicit).
 * Every network-capable feature receives [gatedTransport]; the raw transport is private.
 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dataRoot: File = File(app.filesDir, "pocketforge").apply { mkdirs() }

    val log = LocalLog(File(dataRoot, "logs"))
    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    val vault = SecretVault(File(dataRoot, "vault.json"))
    val probe = DeviceProbe(app)

    val projects = ProjectManager(File(dataRoot, "workspace"))
    val recovery = RecoveryJournal(File(dataRoot, "recovery"))
    val buildHistory = BuildHistory(File(dataRoot, "builds/history.json"))
    val builds = BuildService(File(app.filesDir, "builds"), buildHistory)

    /** Text handed from another screen (e.g. a build log) to the AI screen as proposed context. */
    val aiHandoff = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val consentBroker = ConsentBroker()
    val consentLog = FileConsentLog(File(dataRoot, "consent-log.jsonl"))
    val consentGateway = ConsentGateway(
        prompter = consentBroker,
        log = consentLog,
        networkAllowed = { !settings.value.networkBlocked },
    )

    /** Transport for AI providers (platform trust store). */
    val gatedTransport = GatedTransport(consentGateway, UrlConnectionTransport())

    /** Transport for the paired Build Host: pinned to its certificate when a fingerprint was entered. */
    fun buildHostTransport(): GatedTransport {
        val host = settings.value.buildHost
        return GatedTransport(consentGateway, UrlConnectionTransport(readTimeoutMs = 25 * 60_000, pinnedSha256 = host?.certSha256))
    }

    fun workspace(projectId: String) = Workspace(projects.dirOf(projectId))
    fun snapshots(projectId: String) = SnapshotStore(File(dataRoot, "snapshots/$projectId"))

    fun aiProvider(): AiProvider? {
        val s = settings.value
        val key = vault.get(SecretVault.AI_KEY)
        return when (s.aiProvider) {
            AiProviderKind.NONE -> null
            AiProviderKind.ANTHROPIC -> key?.let { AnthropicProvider(it, s.aiModel.ifBlank { "claude-sonnet-5-5" }) }
            AiProviderKind.OPENAI_COMPATIBLE -> s.aiBaseUrl.takeIf { it.startsWith("https://") }?.let { OpenAiCompatibleProvider(it, key, s.aiModel) }
        }
    }

    /** "Delete local app data": projects, snapshots, drafts, logs, history, settings, secrets and the Keystore key. */
    suspend fun deleteAllLocalData() {
        vault.destroy()
        settingsRepo.clear()
        dataRoot.deleteRecursively()
        File(app.filesDir, "builds").deleteRecursively()
        app.cacheDir.deleteRecursively()
    }
}
