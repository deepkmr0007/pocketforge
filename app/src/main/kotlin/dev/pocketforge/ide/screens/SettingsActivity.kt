package dev.pocketforge.ide.screens

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import dev.pocketforge.ide.platform.AiProviderKind
import dev.pocketforge.ide.platform.AppSettings
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.platform.ThemeMode
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.value

/** Settings: appearance, editor, autosave & recovery, terminal, AI provider, privacy shortcuts. */
class SettingsActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, column) = ui.page(ui.topBar("Settings", null, { finish() }))
        col = column
        setScreen(page)
        render()
    }

    private fun set(f: (AppSettings) -> AppSettings) = c.settings.update(f)

    private fun number(label: String, value: Int, range: IntRange, help: String, apply: (Int) -> Unit) =
        ui.button("$label: $value", Btn.SECONDARY) {
            Dialogs.input(this, label, "${range.first}–${range.last}", value.toString(), "Set", help = help,
                validate = { v -> if (v.trim().toIntOrNull() in range) null else "Enter a number from ${range.first} to ${range.last}" }) { v ->
                apply(v.trim().toInt()); render()
            }
        }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL }

    private fun render() {
        col.removeAllViews()
        val s = c.settings.value

        col.addView(ui.section("Appearance"))
        col.addView(ui.labeled("Theme", ui.spinner(ThemeMode.values().map { it.label }, s.theme.ordinal) { i -> set { it.copy(theme = ThemeMode.values()[i]) }; recreate() }))
        col.addView(ui.switchRow("Hide app content in recents and screenshots", "Uses Android's secure-window flag (FLAG_SECURE).", s.secureScreen) { v -> set { it.copy(secureScreen = v) }; recreate() })

        col.addView(ui.section("Editor"))
        col.addView(number("Font size (sp)", s.fontSizeSp, 10..28, "Applies to the code editor.") { v -> set { it.copy(fontSizeSp = v) } })
        col.addView(ui.space(6))
        col.addView(number("Tab width", s.tabWidth, 2..8, "Spaces per indentation level.") { v -> set { it.copy(tabWidth = v) } })
        col.addView(ui.switchRow("Indent with spaces", "Off: indent with tab characters.", s.useSpaces) { v -> set { it.copy(useSpaces = v) } })
        col.addView(ui.switchRow("Word wrap", "Off: long lines scroll horizontally (usual for code).", s.wordWrap) { v -> set { it.copy(wordWrap = v) } })
        col.addView(ui.switchRow("Line numbers", null, s.lineNumbers) { v -> set { it.copy(lineNumbers = v) } })
        col.addView(ui.switchRow("Coding keyboard bar", "Row of brackets, quotes, Tab and arrows above the keyboard.", s.keyboardBar) { v -> set { it.copy(keyboardBar = v) } })
        col.addView(ui.switchRow("Disable keyboard suggestions", "Recommended: stops auto-correct and auto-capitalisation from changing code.", s.noImeSuggestions) { v -> set { it.copy(noImeSuggestions = v) } })
        col.addView(ui.switchRow("Show hidden files", "Files and folders starting with a dot.", s.showHidden) { v -> set { it.copy(showHidden = v) } })
        col.addView(number("Largest editable file (MB)", s.largeFileEditMb, 1..8, "Bigger files open as a read-only preview of the first 256 KB, so the phone stays responsive.") { v -> set { it.copy(largeFileEditMb = v) } })

        col.addView(ui.section("Autosave & recovery"))
        col.addView(number("Recovery journal interval (s)", s.draftIntervalSec, 2..120, "Unsaved edits are written to an app-private journal this often, so a crash or the system closing the app loses at most this much typing.") { v -> set { it.copy(draftIntervalSec = v) } })
        col.addView(ui.switchRow("Autosave to file", "Also write changes to the real file automatically. Off: only the recovery journal is written until you tap Save.", s.autosaveToFile) { v -> set { it.copy(autosaveToFile = v) } })

        col.addView(ui.section("Terminal"))
        col.addView(number("Command timeout (s)", s.terminalTimeoutSec, 5..600, "Commands still running after this are stopped.") { v -> set { it.copy(terminalTimeoutSec = v) } })
        col.addView(ui.space(6))
        col.addView(number("Output limit (KB)", s.terminalOutputKb, 64..8192, "Output beyond this is discarded (the command keeps running).") { v -> set { it.copy(terminalOutputKb = v) } })
        col.addView(ui.switchRow("Allow network commands", "Off by default. When on, each network command still needs confirmation.", s.terminalAllowNetwork) { v -> set { it.copy(terminalAllowNetwork = v) } })

        col.addView(ui.section("AI assistant (optional)"))
        col.addView(ui.labeled("Provider", ui.spinner(AiProviderKind.values().map { it.label }, s.aiProvider.ordinal) { i -> set { it.copy(aiProvider = AiProviderKind.values()[i]) }; render() }))
        if (s.aiProvider != AiProviderKind.NONE) {
            val model = ui.input("Model name", s.aiModel, monoFont = true)
            col.addView(ui.labeled("Model", model, if (s.aiProvider == AiProviderKind.ANTHROPIC) "Exact model id from your provider's documentation." else "As named by the server, e.g. a model loaded in llama.cpp or Ollama."))
            val base = ui.input("https://api.example.com/v1   or   http://127.0.0.1:8080/v1", s.aiBaseUrl, monoFont = true)
            if (s.aiProvider == AiProviderKind.OPENAI_COMPATIBLE) col.addView(ui.labeled("Base URL", base, "HTTPS for remote services. Plain HTTP is accepted only for 127.0.0.1 / localhost — a model server running on this phone (data stays on the device)."))
            col.addView(number("Max context sent (KB)", s.aiMaxContextKb, 8..400, "Upper bound on code included in one request.") { v -> set { it.copy(aiMaxContextKb = v) } })
            col.addView(ui.space(6))
            col.addView(ui.button("Save model settings", Btn.SECONDARY) {
                val url = base.value().trim()
                if (s.aiProvider == AiProviderKind.OPENAI_COMPATIBLE && !(url.startsWith("https://") || Regex("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$").matches(url))) {
                    toastLong("Base URL must be https://… or http://127.0.0.1…/localhost"); return@button
                }
                set { it.copy(aiModel = model.value().trim(), aiBaseUrl = url) }; toast("Saved")
            })
            col.addView(ui.space(6))
            col.addView(ui.button(if (c.vault.has(SecretVault.AI_KEY)) "Replace API key…" else "Set API key…", Btn.SECONDARY) {
                val field = ui.input("API key", monoFont = true, password = true)
                Dialogs.custom(this, "API key", ui.vertical(20) {
                    addView(ui.caption("Stored encrypted with a non-exportable Android Keystore key. Sent only in the request header to the provider you configured, after you approve each request. Leave empty for local servers that need no key."))
                    addView(ui.space(8)); addView(field, ui.lp())
                }, "Save") {
                    val k = field.value().trim()
                    if (k.isEmpty()) c.vault.remove(SecretVault.AI_KEY) else bg({ c.vault.put(SecretVault.AI_KEY, k) }) { r -> r.onFailure { showError(it) } }
                    render()
                }
            })
        }

        col.addView(ui.section("Storage"))
        val usage = ui.caption("Measuring…")
        col.addView(usage)
        bg({ c.storageUsage() }) { r -> r.onSuccess { list -> usage.text = list.joinToString("\n") { (k, v) -> "$k: ${dev.pocketforge.engine.common.humanBytes(v)}" } } }
        col.addView(ui.button("Clear temporary cache", Btn.SECONDARY) {
            bg({ c.clearCache() }) { r -> r.onSuccess { toast("Freed ${dev.pocketforge.engine.common.humanBytes(it)}. Projects, snapshots and drafts are untouched."); render() } }
        })
        col.addView(ui.caption("Clearing the cache never deletes source files, snapshots or recovery drafts. Old snapshots can be deleted under each project's Snapshots; trash under Privacy."))

        col.addView(ui.section("Privacy"))
        col.addView(ui.switchRow("Block all network", "Kill switch for every network feature.", s.networkBlocked) { v -> set { it.copy(networkBlocked = v) } })
        col.addView(ui.button("Privacy & permissions…", Btn.TEXT) { Nav.privacy(this) })
        col.addView(ui.space(24))
    }
}
