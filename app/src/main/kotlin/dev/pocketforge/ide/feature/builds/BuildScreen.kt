package dev.pocketforge.ide.feature.builds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.build.BuildAttempt
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.platform.ApkTools
import dev.pocketforge.ide.ui.CapabilityRow
import dev.pocketforge.ide.ui.ConfirmDialog
import dev.pocketforge.ide.ui.LabeledValue
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.SectionTitle
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(pid: String, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: BuildViewModel = viewModel(key = "build-$pid") { BuildViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var pairing by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.inspectUri(ctx, it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.export(ctx, uri) else vm.cancelExport() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Build center") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            s.message?.let { m -> item { MessageBanner(m, s.error) { vm.msg(null, false) } } }
            item { SectionTitle("What can build here") }
            items(s.caps, key = { it.id }) { Column(Modifier.padding(horizontal = 16.dp)) { CapabilityRow(it) } }

            item { SectionTitle("Export to a computer (always available)") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Creates a clean ZIP (no secrets, no build outputs) plus BUILD_INSTRUCTIONS.md for Android Studio or Gradle.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::prepareExport) { Text("Export project…") }
                }
            }

            item { SectionTitle("Build on your Build Host") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val host = settings.buildHost
                    if (host == null || !s.hostTokenSet) {
                        Text("Run tools/build-host/pocketforge_build_host.py on your own computer, then pair it here. Your code goes only to that machine, and only after you approve each build.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { pairing = true }) { Text("Pair Build Host") }
                    } else {
                        LabeledValue("Paired host", "${host.name} · ${host.baseUrl}")
                        LabeledValue("Identity", host.certSha256?.let { "Pinned certificate ${it.take(16)}…" } ?: "Publicly trusted certificate")
                        val running = s.running
                        if (running != null) {
                            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(running) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                            Text("Running $running on ${host.name}… ${(now - s.runningSince) / 1000}s (waiting for the host; no fake progress shown)")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = vm::stopWaiting) { Text("Stop waiting") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.review("assembleDebug") }) { Text("Build debug APK") }
                                OutlinedButton(onClick = { vm.review("assembleRelease") }) { Text("Release") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.review("lintDebug") }) { Text("Lint") }
                                OutlinedButton(onClick = { go(Routes.tests(pid)) }) { Text("Tests…") }
                                TextButton(onClick = vm::unpair) { Text("Unpair") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Inspect / install an APK") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Checks package, version, SDK levels, debug/release and signature. Installing always goes through Android's own installer.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("Pick an APK…") }
                }
            }

            item { SectionTitle("History") }
            if (s.history.isEmpty()) item { Text("No builds yet.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(s.history, key = { it.id }) { a -> HistoryCard(a, onLog = { vm.openLog(a) }, onInspect = { vm.inspectArtifact(ctx, a) }) }
        }
    }

    if (pairing) PairDialog(onPair = { n, u, f, t -> vm.pairHost(n, u, f, t); pairing = false }, onDismiss = { pairing = false })
    s.manifest?.let { m ->
        ManifestDialog("Files to upload for ${s.reviewTask}", "Only checked files go to your Build Host. You will see one more consent screen before anything is sent.", m, vm::toggle,
            confirm = "Continue (${m.count { it.include }} files)", onConfirm = vm::startRemote, onDismiss = vm::cancelReview)
    }
    s.exportManifest?.let { m ->
        ManifestDialog("Export manifest", "Unchecked files are left out of the ZIP.", m, vm::toggleExport,
            confirm = "Export ${m.count { it.include }} files", onConfirm = { exportLauncher.launch("project-export.zip") }, onDismiss = vm::cancelExport)
    }
    s.inspected?.let { i ->
        AlertDialog(
            onDismissRequest = vm::closeInspect,
            title = { Text(i.info.packageName ?: "APK") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledValue("Version", "${i.info.versionName ?: "?"} (code ${i.info.versionCode ?: "?"})")
                    LabeledValue("SDK", "min ${i.info.minSdk ?: "?"} · target ${i.info.targetSdk ?: "?"} · this device ${android.os.Build.VERSION.SDK_INT}")
                    LabeledValue("Type", i.info.kind + if (i.info.hasDex) "" else " · NO CODE (classes.dex missing)")
                    LabeledValue("Signature", if (i.info.signaturePresent) "Present: scheme ${i.info.signatureSchemes.joinToString("+")}" else "Unsigned — Android will refuse to install it")
                    i.platform?.signerSha256?.firstOrNull()?.let { LabeledValue("Signer SHA-256 (from Android)", it) }
                    i.platform?.installedVersion?.let { LabeledValue("Installed now", "$it — updating requires the same signing key") }
                    LabeledValue("File", "${humanBytes(i.info.sizeBytes)} · SHA-256 ${i.info.sha256}")
                    if ((i.info.minSdk ?: 0) > android.os.Build.VERSION.SDK_INT) Text("This device is too old for this APK.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = i.info.signaturePresent && i.info.hasDex, onClick = {
                    if (ApkTools.canInstall(ctx)) confirmInstall = true else ApkTools.openInstallPermissionSettings(ctx)
                }) { Text("Install…") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { runCatching { ApkTools.share(ctx, ApkTools.stageForShare(ctx, i.file.name, i.file.readBytes()), "application/vnd.android.package-archive") } }) { Text("Share") }
                    TextButton(onClick = vm::closeInspect) { Text("Close") }
                }
            },
        )
        if (confirmInstall) ConfirmDialog(
            "Install ${i.info.packageName}?", "Android's package installer will open and ask you to confirm. PocketForge never installs silently.", "Open installer",
            onConfirm = {
                val f = if (i.file.path.contains("/builds/artifacts/")) i.file else ApkTools.stageForShare(ctx, i.file.name, i.file.readBytes())
                ApkTools.install(ctx, f)?.let { vm.msg(it, true) }
            },
            onDismiss = { confirmInstall = false },
        )
    }
    s.log?.let { l ->
        AlertDialog(
            onDismissRequest = vm::closeLog,
            title = { Text("Build log") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    l.hints.forEach { h -> MessageBanner("${h.title}: ${h.detail} Fix: ${h.fix}") }
                    if (l.hints.isNotEmpty()) Text("Hints come from the offline rule-based explainer, not AI.", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(l.text.takeLast(80_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeLog) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    c.aiHandoff.value = "Build log for task ${l.attempt.task}:\n" + l.text.takeLast(12_000)
                    vm.closeLog(); go(Routes.ai(pid))
                }) { Text("Ask AI to explain") }
            },
        )
    }
}

@Composable
private fun HistoryCard(a: BuildAttempt, onLog: () -> Unit, onInspect: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            val color = when (a.status) { BuildStatus.SUCCESS, BuildStatus.EXPORTED -> MaterialTheme.colorScheme.primary; BuildStatus.CANCELLED -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.error }
            Text("${a.status.name} · ${a.task} · ${a.backend}", color = color, style = MaterialTheme.typography.labelLarge)
            Text(a.message, style = MaterialTheme.typography.bodySmall)
            Text("${DateFormat.getDateTimeInstance().format(Date(a.startedAt))} · ${(a.finishedAt - a.startedAt) / 1000}s" + (a.toolchain["gradle"]?.let { " · Gradle $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
            a.apk?.let { Text("APK ${humanBytes(it.sizeBytes)} · SHA-256 ${it.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall) }
            Row {
                if (a.logFile != null) TextButton(onClick = onLog) { Text("Log") }
                if (a.artifactPath != null) TextButton(onClick = onInspect) { Text("Inspect / install") }
            }
        }
    }
}

@Composable
private fun PairDialog(onPair: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("My computer") }
    var url by remember { mutableStateOf("https://") }
    var fp by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair Build Host") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Copy these from the Build Host's terminal output.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL (https://192.168.x.x:8765)") }, singleLine = true)
                OutlinedTextField(fp, { fp = it }, label = { Text("Certificate fingerprint (SHA-256)") })
                OutlinedTextField(token, { token = it }, label = { Text("Pairing token") }, singleLine = true)
                Text("The token is encrypted with the Android Keystore. The fingerprint pins the host's identity.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPair(name.trim(), url.trim(), fp.trim(), token.trim()) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ManifestDialog(title: String, note: String, m: List<ManifestEntry>, toggle: (String) -> Unit, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text(note, style = MaterialTheme.typography.bodySmall) }
                items(m, key = { it.path }) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = e.include, onCheckedChange = { toggle(e.path) })
                        Column {
                            Text(e.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            e.reason?.let { Text("excluded: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = m.any { it.include }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
