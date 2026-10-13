package dev.pocketforge.ide.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pocketforge.engine.build.BuildHostConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AiProviderKind(val label: String) { NONE("Off"), ANTHROPIC("Anthropic"), OPENAI_COMPATIBLE("OpenAI-compatible / self-hosted") }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontSizeSp: Int = 14,
    val tabWidth: Int = 4,
    val useSpaces: Boolean = true,
    val wordWrap: Boolean = true,
    val draftIntervalSec: Int = 5,
    val autosaveToFile: Boolean = false,
    val showHidden: Boolean = false,
    val networkBlocked: Boolean = false,
    val aiProvider: AiProviderKind = AiProviderKind.NONE,
    val aiModel: String = "claude-sonnet-5-5",
    val aiBaseUrl: String = "",
    val hostName: String = "",
    val hostUrl: String = "",
    val hostCertSha256: String = "",
) {
    val indentUnit: String get() = if (useSpaces) " ".repeat(tabWidth) else "\t"
    val buildHost: BuildHostConfig? get() =
        if (hostUrl.isBlank()) null else BuildHostConfig(hostName.ifBlank { "Build Host" }, hostUrl, hostCertSha256.ifBlank { null })

    /** Summary for diagnostic reports: no secrets, no URLs. */
    fun summary(): Map<String, String> = mapOf(
        "theme" to theme.name, "fontSize" to "$fontSizeSp", "tabWidth" to "$tabWidth", "spaces" to "$useSpaces",
        "wordWrap" to "$wordWrap", "draftIntervalSec" to "$draftIntervalSec", "autosaveToFile" to "$autosaveToFile",
        "networkBlocked" to "$networkBlocked", "aiProvider" to aiProvider.name, "buildHostPaired" to "${hostUrl.isNotBlank()}",
    )
}

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val font = intPreferencesKey("font")
        val tab = intPreferencesKey("tab")
        val spaces = booleanPreferencesKey("spaces")
        val wrap = booleanPreferencesKey("wrap")
        val draft = intPreferencesKey("draft")
        val autosave = booleanPreferencesKey("autosave")
        val hidden = booleanPreferencesKey("hidden")
        val net = booleanPreferencesKey("networkBlocked")
        val ai = stringPreferencesKey("aiProvider")
        val model = stringPreferencesKey("aiModel")
        val base = stringPreferencesKey("aiBaseUrl")
        val hostName = stringPreferencesKey("hostName")
        val hostUrl = stringPreferencesKey("hostUrl")
        val hostFp = stringPreferencesKey("hostFp")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            fontSizeSp = (p[K.font] ?: d.fontSizeSp).coerceIn(10, 28),
            tabWidth = (p[K.tab] ?: d.tabWidth).coerceIn(2, 8),
            useSpaces = p[K.spaces] ?: d.useSpaces,
            wordWrap = p[K.wrap] ?: d.wordWrap,
            draftIntervalSec = (p[K.draft] ?: d.draftIntervalSec).coerceIn(2, 120),
            autosaveToFile = p[K.autosave] ?: d.autosaveToFile,
            showHidden = p[K.hidden] ?: d.showHidden,
            networkBlocked = p[K.net] ?: d.networkBlocked,
            aiProvider = p[K.ai]?.let { runCatching { AiProviderKind.valueOf(it) }.getOrNull() } ?: d.aiProvider,
            aiModel = p[K.model] ?: d.aiModel,
            aiBaseUrl = p[K.base] ?: d.aiBaseUrl,
            hostName = p[K.hostName] ?: "",
            hostUrl = p[K.hostUrl] ?: "",
            hostCertSha256 = p[K.hostFp] ?: "",
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                fontSizeSp = p[K.font] ?: 14, tabWidth = p[K.tab] ?: 4, useSpaces = p[K.spaces] ?: true,
                wordWrap = p[K.wrap] ?: true, draftIntervalSec = p[K.draft] ?: 5, autosaveToFile = p[K.autosave] ?: false,
                showHidden = p[K.hidden] ?: false, networkBlocked = p[K.net] ?: false,
                aiProvider = p[K.ai]?.let { runCatching { AiProviderKind.valueOf(it) }.getOrNull() } ?: AiProviderKind.NONE,
                aiModel = p[K.model] ?: "claude-sonnet-5-5", aiBaseUrl = p[K.base] ?: "",
                hostName = p[K.hostName] ?: "", hostUrl = p[K.hostUrl] ?: "", hostCertSha256 = p[K.hostFp] ?: "",
            )
            val n = transform(cur)
            p[K.theme] = n.theme.name; p[K.font] = n.fontSizeSp; p[K.tab] = n.tabWidth; p[K.spaces] = n.useSpaces
            p[K.wrap] = n.wordWrap; p[K.draft] = n.draftIntervalSec; p[K.autosave] = n.autosaveToFile; p[K.hidden] = n.showHidden
            p[K.net] = n.networkBlocked; p[K.ai] = n.aiProvider.name; p[K.model] = n.aiModel; p[K.base] = n.aiBaseUrl
            p[K.hostName] = n.hostName; p[K.hostUrl] = n.hostUrl; p[K.hostFp] = n.hostCertSha256
        }
    }

    suspend fun clear() { context.dataStore.edit { it.clear() } }
}
