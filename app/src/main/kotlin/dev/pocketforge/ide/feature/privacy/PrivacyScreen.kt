package dev.pocketforge.ide.feature.privacy

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.consent.ConsentLogEntry
import dev.pocketforge.ide.MainActivity
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.ui.ConfirmDialog
import dev.pocketforge.ide.ui.SectionTitle
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf<List<ConsentLogEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var aiKeySet by remember { mutableStateOf(false) }
    var hostTokenSet by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmClearLog by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        io { Triple(c.consentLog.entries(), c.vault.has(SecretVault.AI_KEY), c.vault.has(SecretVault.HOST_TOKEN)) }
            .onSuccess { (l, a, h) -> log = l; aiKeySet = a; hostTokenSet = h }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Privacy & permissions") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("What PocketForge does with your data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "• Projects, snapshots, drafts and logs stay in app-private storage on this phone and are excluded from cloud backup.\n" +
                            "• No analytics, ads, crash uploads, update checks or accounts.\n" +
                            "• The only network uses are an AI provider or your Build Host, each request shown to you first and approved once.\n" +
                            "• Android itself may still do system networking (e.g. Play services); PocketForge does not control that.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block all network", style = MaterialTheme.typography.bodyLarge)
                        Text("Kill switch: refuses every request, even ones already approved.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.networkBlocked, { v -> scope.launch { c.settingsRepo.update { it.copy(networkBlocked = v) } } })
                }
            }
            item { SectionTitle("Permissions this app declares") }
            item {
                Text(
                    "INTERNET — used only after your per-request approval.\nACCESS_NETWORK_STATE — shows online/offline; reads no traffic.\n" +
                        "REQUEST_INSTALL_PACKAGES — opens Android's installer when you tap Install.\nNot requested: storage, contacts, camera, microphone, location, accessibility, SMS, call logs.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            item { SectionTitle("Stored secrets (Android Keystore encrypted)") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI API key: ${if (aiKeySet) "stored" else "none"}", Modifier.weight(1f))
                        if (aiKeySet) TextButton(onClick = { c.vault.remove(SecretVault.AI_KEY); refresh++ }) { Text("Delete") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Build Host token: ${if (hostTokenSet) "stored" else "none"}", Modifier.weight(1f))
                        if (hostTokenSet) TextButton(onClick = { c.vault.remove(SecretVault.HOST_TOKEN); refresh++ }) { Text("Delete") }
                    }
                }
            }
            item { SectionTitle("Network decisions (${log.size})") }
            item { Text("Every request you approved, denied or that was blocked. Only hashes and sizes are kept — never the content.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(log.take(200)) { e ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${e.decision} · ${e.feature}", style = MaterialTheme.typography.bodyMedium, color = if (e.decision == "APPROVE_ONCE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text("${DateFormat.getDateTimeInstance().format(Date(e.timeMillis))} · ${e.destination} · ${humanBytes(e.payloadBytes)}${if (e.sendsSourceCode) " · source code" else ""} · sha256 ${e.payloadSha256.take(12)}…", style = MaterialTheme.typography.labelSmall)
                }
            }
            item { if (log.isNotEmpty()) TextButton(onClick = { confirmClearLog = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear decision history") } }
            item { SectionTitle("Delete local app data") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Permanently deletes ALL projects, snapshots, trash, drafts, build history and APKs, logs, decision history, settings, stored keys and the Keystore key. Export anything you want to keep first.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmWipe = true }) { Text("Delete everything…", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmClearLog) ConfirmDialog("Clear decision history?", "Removes the local record of past network decisions.", "Clear", destructive = true,
        onConfirm = { c.consentLog.clear(); refresh++ }, onDismiss = { confirmClearLog = false })
    if (confirmWipe) ConfirmDialog(
        "Delete all local data?", "This cannot be undone. All projects on this phone will be lost unless you exported them.", "Delete everything", destructive = true,
        onConfirm = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.deleteAllLocalData() } }
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                Runtime.getRuntime().exit(0)
            }
        },
        onDismiss = { confirmWipe = false },
    )
}
