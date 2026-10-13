package dev.pocketforge.ide.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.build.BuildAttempt
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.projects.Project
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.ui.ConfirmDialog
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.SectionTitle
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

data class HomeState(
    val projects: List<Project> = emptyList(),
    val drafts: List<RecoveryJournal.Draft> = emptyList(),
    val builds: List<BuildAttempt> = emptyList(),
    val online: Boolean = false,
    val networkBlocked: Boolean = false,
    val freeBytes: Long = 0,
    val unavailableCaps: Int = 0,
    val crashedLastTime: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(HomeState())

    fun refresh() = viewModelScope.launch {
        io {
            val marker = File(c.dataRoot, "last-crash.marker")
            val crashed = marker.exists().also { if (it) marker.delete() }
            val caps = dev.pocketforge.engine.toolchain.CapabilityEvaluator.evaluate(c.probe.facts(c.settings.value, c.aiProvider() != null))
            HomeState(
                projects = c.projects.list(),
                drafts = c.recovery.drafts(),
                builds = c.buildHistory.list().take(3),
                online = c.probe.isOnline(),
                networkBlocked = c.settings.value.networkBlocked,
                freeBytes = c.probe.freeStorageBytes(),
                unavailableCaps = caps.count { it.status == CapStatus.UNAVAILABLE },
                crashedLastTime = crashed,
            )
        }.onSuccess { s -> state.update { s.copy(message = it.message, error = it.error) } }
    }

    fun import(block: () -> Project, onDone: (Project) -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        io(block).onSuccess { p ->
            c.log.info("import", "Imported project")
            state.update { it.copy(busy = false, message = "Imported \"${p.name}\".", error = false) }; refresh(); onDone(p)
        }.onFailure { e -> state.update { it.copy(busy = false, message = "Import failed: ${e.message}", error = true) } }
    }

    fun trashProject(p: Project, onTrashed: (String) -> Unit) = viewModelScope.launch {
        io { c.projects.moveProjectToTrash(p.id) }.onSuccess { onTrashed(it.id); refresh() }
            .onFailure { e -> state.update { it.copy(message = e.message, error = true) } }
    }

    fun restore(entryId: String) = viewModelScope.launch { io { c.projects.restore(entryId) }; refresh() }
    fun clearMessage() = state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(go: (String) -> Unit) {
    val c = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.import({ SafImport.importZip(ctx, c.projects, it) }) { p -> go(Routes.project(p.id)) } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.import({ SafImport.importTree(ctx, c.projects, it) }) { p -> go(Routes.project(p.id)) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketForge") },
                actions = {
                    IconButton(onClick = { go(Routes.PRIVACY) }) { Icon(Icons.Filled.PrivacyTip, "Privacy center") }
                    IconButton(onClick = { go(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings") }
                    IconButton(onClick = { go(Routes.HELP) }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Help and diagnostics") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { go(Routes.NEW) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New project") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { go(Routes.PRIVACY) }, label = { Text(if (s.networkBlocked) "Network blocked" else if (s.online) "Online · asks first" else "Offline") },
                        leadingIcon = { Icon(if (s.online && !s.networkBlocked) Icons.Filled.Wifi else Icons.Filled.CloudOff, null) })
                    AssistChip(onClick = { go(Routes.HELP) }, label = { Text("${humanBytes(s.freeBytes)} free") }, leadingIcon = { Icon(Icons.Filled.Storage, null) })
                }
            }
            if (s.crashedLastTime) item { MessageBanner("PocketForge closed unexpectedly last time. Unsaved edits were kept below; a local crash log is in Help.", isError = true) }
            s.message?.let { m -> item { MessageBanner(m, s.error) { vm.clearMessage() } } }
            if (s.busy) item { MessageBanner("Importing… large projects can take a moment.") }
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !s.busy) {
                        Icon(Icons.Filled.Archive, null); Spacer(Modifier.width(6.dp)); Text("Import ZIP")
                    }
                    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !s.busy) {
                        Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Import folder")
                    }
                }
            }
            if (s.drafts.isNotEmpty()) {
                item { SectionTitle("Recovered unsaved work") }
                items(s.drafts, key = { "d" + it.projectId + it.relativePath }) { d ->
                    val pname = s.projects.firstOrNull { it.id == d.projectId }?.name ?: "Deleted project"
                    Row(Modifier.fillMaxWidth().clickable(enabled = s.projects.any { it.id == d.projectId }) { go(Routes.editor(d.projectId, d.relativePath)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.relativePath, fontWeight = FontWeight.SemiBold)
                            Text("$pname · ${DateFormat.getDateTimeInstance().format(Date(d.savedAtMillis))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { SectionTitle("Projects") }
            if (s.projects.isEmpty()) item {
                Text("No projects yet. Create one from a template or import a ZIP/folder. Everything stays on this phone.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(s.projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { go(Routes.project(p.id)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("${p.language} · ${p.packageId ?: p.templateId} · opened ${DateFormat.getDateInstance().format(Date(p.lastOpenedAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.TextButton(onClick = { confirmDelete = p }) { Text("Delete") }
                    }
                }
            }
            if (s.builds.isNotEmpty()) {
                item { SectionTitle("Recent builds") }
                items(s.builds, key = { "b" + it.id }) { b ->
                    Text("${b.status.name.lowercase()} · ${b.backend} · ${b.message}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (s.unavailableCaps > 0) item {
                Text("${s.unavailableCaps} features are unavailable on this device — see Help › Capabilities for the honest list.", Modifier.padding(16.dp).clickable { go(Routes.HELP) }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirmDelete?.let { p ->
        ConfirmDialog(
            "Move \"${p.name}\" to trash?", "The project is moved to the in-app trash and can be restored from Help › Trash. Snapshots are kept.",
            "Move to trash", destructive = true,
            onConfirm = {
                vm.trashProject(p) { entryId ->
                    scope.launch { if (snack.showSnackbar("Moved to trash", actionLabel = "Undo") == SnackbarResult.ActionPerformed) vm.restore(entryId) }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}
