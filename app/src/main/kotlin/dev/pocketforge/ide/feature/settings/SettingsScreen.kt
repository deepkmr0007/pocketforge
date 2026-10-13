package dev.pocketforge.ide.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.platform.AiProviderKind
import dev.pocketforge.ide.platform.AppSettings
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.platform.ThemeMode
import dev.pocketforge.ide.ui.SectionTitle
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val s by c.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) { scope.launch { c.settingsRepo.update(f) } }
    var keyInput by remember { mutableStateOf("") }
    var keyStored by remember { mutableStateOf(false) }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { keyStored = io { c.vault.has(SecretVault.AI_KEY) }.getOrDefault(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Appearance")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> FilterChip(s.theme == m, { upd { it.copy(theme = m) } }, label = { Text(m.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }) }) }
            }
            Text("Editor font size: ${s.fontSizeSp} sp", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Slider(s.fontSizeSp.toFloat(), { v -> upd { it.copy(fontSizeSp = v.toInt()) } }, valueRange = 10f..28f, steps = 17, modifier = Modifier.padding(horizontal = 16.dp))

            SectionTitle("Editor")
            Text("Indent width: ${s.tabWidth}", Modifier.padding(horizontal = 16.dp))
            Slider(s.tabWidth.toFloat(), { v -> upd { it.copy(tabWidth = v.toInt()) } }, valueRange = 2f..8f, steps = 5, modifier = Modifier.padding(horizontal = 16.dp))
            Toggle("Indent with spaces", "Off = tab characters", s.useSpaces) { v -> upd { it.copy(useSpaces = v) } }
            Toggle("Word wrap", "Long lines wrap instead of scrolling sideways", s.wordWrap) { v -> upd { it.copy(wordWrap = v) } }
            Toggle("Show hidden files", "Files starting with a dot", s.showHidden) { v -> upd { it.copy(showHidden = v) } }
            Text("Recovery draft every ${s.draftIntervalSec}s while you have unsaved edits", Modifier.padding(horizontal = 16.dp))
            Slider(s.draftIntervalSec.toFloat(), { v -> upd { it.copy(draftIntervalSec = v.toInt()) } }, valueRange = 2f..60f, modifier = Modifier.padding(horizontal = 16.dp))
            Toggle("Autosave to file", "Also write files automatically (off = only recovery drafts until you tap Save)", s.autosaveToFile) { v -> upd { it.copy(autosaveToFile = v) } }

            SectionTitle("AI assistant (optional, online)")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviderKind.entries.forEach { k -> FilterChip(s.aiProvider == k, { upd { it.copy(aiProvider = k) } }, label = { Text(k.label) }) }
            }
            if (s.aiProvider != AiProviderKind.NONE) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Local state keeps typing smooth; each change is persisted.
                    var model by remember(s.aiProvider) { mutableStateOf(s.aiModel) }
                    var baseUrl by remember(s.aiProvider) { mutableStateOf(s.aiBaseUrl) }
                    OutlinedTextField(model, { t -> model = t; upd { it.copy(aiModel = t.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model ID") },
                        supportingText = { Text("Check your provider's documentation for current model IDs.") })
                    if (s.aiProvider == AiProviderKind.OPENAI_COMPATIBLE) {
                        OutlinedTextField(baseUrl, { t -> baseUrl = t; upd { it.copy(aiBaseUrl = t.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Base URL (https only)") },
                            isError = baseUrl.isNotEmpty() && !baseUrl.trim().startsWith("https://"), supportingText = { Text("e.g. https://my-server:8443/v1 — a self-hosted model keeps code on your own machines.") })
                    }
                    OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (keyStored) "API key stored — enter to replace" else "API key") },
                        visualTransformation = PasswordVisualTransformation())
                    Button(enabled = keyInput.length >= 8, onClick = {
                        val k = keyInput; keyInput = ""
                        scope.launch { io { c.vault.put(SecretVault.AI_KEY, k) }.onSuccess { keyStored = true; keyMsg = "Key encrypted with Android Keystore." }.onFailure { keyMsg = it.message } }
                    }) { Text("Store key securely") }
                    keyMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Text("The key is never shown again, never logged, and never included in diagnostic reports.", style = MaterialTheme.typography.labelSmall)
                }
            }

            SectionTitle("Network")
            Toggle("Block all network", "Kill switch for AI and Build Host requests", s.networkBlocked) { v -> upd { it.copy(networkBlocked = v) } }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}
