package dev.pocketforge.ide.platform

import android.content.Context
import android.content.SharedPreferences
import dev.pocketforge.engine.build.BuildHostConfig
import java.util.concurrent.CopyOnWriteArrayList

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

enum class AiProviderKind(val label: String) {
    NONE("Off"),
    ANTHROPIC("Anthropic (online)"),
    OPENAI_COMPATIBLE("OpenAI-compatible (online, or a model server on this phone at 127.0.0.1)"),
}

/** All user preferences. No secrets live here — API keys and tokens are in [SecretVault]. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontSizeSp: Int = 14,
    val tabWidth: Int = 4,
    val useSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val keyboardBar: Boolean = true,
    val noImeSuggestions: Boolean = true,
    val draftIntervalSec: Int = 5,
    val autosaveToFile: Boolean = false,
    val showHidden: Boolean = false,
    val largeFileEditMb: Int = 2,
    val networkBlocked: Boolean = false,
    val secureScreen: Boolean = false,
    val terminalTimeoutSec: Int = 60,
    val terminalAllowNetwork: Boolean = false,
    val terminalOutputKb: Int = 1024,
    val aiProvider: AiProviderKind = AiProviderKind.NONE,
    val aiModel: String = "claude-sonnet-5-5",
    val aiBaseUrl: String = "",
    val aiMaxContextKb: Int = 60,
    val hostName: String = "",
    val hostUrl: String = "",
    val hostCertSha256: String = "",
    val termuxTreeUri: String = "",
    val termuxPath: String = "/storage/emulated/0/PocketForge",
    val trustedProjects: Set<String> = emptySet(),
) {
    val indentUnit: String get() = if (useSpaces) " ".repeat(tabWidth) else "\t"
    val buildHost: BuildHostConfig?
        get() = if (hostUrl.isBlank()) null else BuildHostConfig(hostName.ifBlank { "Build Host" }, hostUrl, hostCertSha256.ifBlank { null })

    /** Summary for diagnostic reports: no secrets, no URLs, no paths. */
    fun summary(): Map<String, String> = mapOf(
        "theme" to theme.name, "fontSize" to "$fontSizeSp", "tabWidth" to "$tabWidth", "spaces" to "$useSpaces",
        "wordWrap" to "$wordWrap", "draftIntervalSec" to "$draftIntervalSec", "autosaveToFile" to "$autosaveToFile",
        "networkBlocked" to "$networkBlocked", "secureScreen" to "$secureScreen", "terminalAllowNetwork" to "$terminalAllowNetwork",
        "aiProvider" to aiProvider.name, "buildHostPaired" to "${hostUrl.isNotBlank()}", "termuxFolderSet" to "${termuxTreeUri.isNotBlank()}",
    )
}

/** SharedPreferences-backed store (app-private, excluded from backup). Listeners are notified on the main thread caller. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(AppSettings) -> Unit>()

    @Volatile var value: AppSettings = read()
        private set

    fun addListener(l: (AppSettings) -> Unit) { listeners += l }
    fun removeListener(l: (AppSettings) -> Unit) { listeners -= l }

    @Synchronized fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(value)
        write(n)
        value = n
        listeners.forEach { runCatching { it(n) } }
    }

    fun clear() { prefs.edit().clear().commit(); value = AppSettings() }

    private fun read(): AppSettings {
        val d = AppSettings()
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        return AppSettings(
            theme = runCatching { ThemeMode.valueOf(s("theme", d.theme.name)) }.getOrDefault(d.theme),
            fontSizeSp = prefs.getInt("font", d.fontSizeSp).coerceIn(10, 28),
            tabWidth = prefs.getInt("tab", d.tabWidth).coerceIn(2, 8),
            useSpaces = prefs.getBoolean("spaces", d.useSpaces),
            wordWrap = prefs.getBoolean("wrap", d.wordWrap),
            lineNumbers = prefs.getBoolean("lineNumbers", d.lineNumbers),
            keyboardBar = prefs.getBoolean("keyboardBar", d.keyboardBar),
            noImeSuggestions = prefs.getBoolean("noImeSuggestions", d.noImeSuggestions),
            draftIntervalSec = prefs.getInt("draft", d.draftIntervalSec).coerceIn(2, 120),
            autosaveToFile = prefs.getBoolean("autosave", d.autosaveToFile),
            showHidden = prefs.getBoolean("hidden", d.showHidden),
            largeFileEditMb = prefs.getInt("largeFileEditMb", d.largeFileEditMb).coerceIn(1, 8),
            networkBlocked = prefs.getBoolean("networkBlocked", d.networkBlocked),
            secureScreen = prefs.getBoolean("secureScreen", d.secureScreen),
            terminalTimeoutSec = prefs.getInt("termTimeout", d.terminalTimeoutSec).coerceIn(5, 600),
            terminalAllowNetwork = prefs.getBoolean("termNet", d.terminalAllowNetwork),
            terminalOutputKb = prefs.getInt("termOut", d.terminalOutputKb).coerceIn(64, 8192),
            aiProvider = runCatching { AiProviderKind.valueOf(s("aiProvider", d.aiProvider.name)) }.getOrDefault(d.aiProvider),
            aiModel = s("aiModel", d.aiModel),
            aiBaseUrl = s("aiBaseUrl", d.aiBaseUrl),
            aiMaxContextKb = prefs.getInt("aiCtx", d.aiMaxContextKb).coerceIn(8, 400),
            hostName = s("hostName", ""),
            hostUrl = s("hostUrl", ""),
            hostCertSha256 = s("hostFp", ""),
            termuxTreeUri = s("termuxTree", ""),
            termuxPath = s("termuxPath", d.termuxPath),
            trustedProjects = prefs.getStringSet("trusted", emptySet())?.toSet() ?: emptySet(),
        )
    }

    private fun write(n: AppSettings) {
        prefs.edit()
            .putString("theme", n.theme.name).putInt("font", n.fontSizeSp).putInt("tab", n.tabWidth)
            .putBoolean("spaces", n.useSpaces).putBoolean("wrap", n.wordWrap).putBoolean("lineNumbers", n.lineNumbers)
            .putBoolean("keyboardBar", n.keyboardBar).putBoolean("noImeSuggestions", n.noImeSuggestions)
            .putInt("draft", n.draftIntervalSec).putBoolean("autosave", n.autosaveToFile).putBoolean("hidden", n.showHidden)
            .putInt("largeFileEditMb", n.largeFileEditMb).putBoolean("networkBlocked", n.networkBlocked)
            .putBoolean("secureScreen", n.secureScreen).putInt("termTimeout", n.terminalTimeoutSec)
            .putBoolean("termNet", n.terminalAllowNetwork).putInt("termOut", n.terminalOutputKb)
            .putString("aiProvider", n.aiProvider.name).putString("aiModel", n.aiModel).putString("aiBaseUrl", n.aiBaseUrl)
            .putInt("aiCtx", n.aiMaxContextKb)
            .putString("hostName", n.hostName).putString("hostUrl", n.hostUrl).putString("hostFp", n.hostCertSha256)
            .putString("termuxTree", n.termuxTreeUri).putString("termuxPath", n.termuxPath)
            .putStringSet("trusted", HashSet(n.trustedProjects))
            .commit() // synchronous: settings changes are rare and must survive process death
    }
}
