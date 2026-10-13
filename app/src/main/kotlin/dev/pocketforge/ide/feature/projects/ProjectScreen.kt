package dev.pocketforge.ide.feature.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.projects.SortOrder
import dev.pocketforge.engine.projects.TreeNode
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.platform.SafImport
import dev.pocketforge.ide.ui.ConfirmDialog
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.TextInputDialog
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExplorerState(
    val name: String = "",
    val nodes: List<TreeNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val filter: String = "",
    val sort: SortOrder = SortOrder.NAME,
    val message: String? = null,
    val error: Boolean = false,
    val exportManifest: List<ManifestEntry>? = null,
)

class ExplorerViewModel(private val c: AppContainer, private val pid: String) : ViewModel() {
    val state = MutableStateFlow(ExplorerState())
    private val ws = c.workspace(pid)

    init { viewModelScope.launch { io { c.projects.touch(pid); c.projects.get(pid).name }.onSuccess { n -> state.update { it.copy(name = n) } }; reload() } }

    fun reload() = viewModelScope.launch {
        val s = state.value
        io { ws.tree(s.expanded, s.sort, s.filter, c.settings.value.showHidden) }
            .onSuccess { n -> state.update { it.copy(nodes = n) } }
            .onFailure { e -> fail(e) }
    }

    fun toggle(path: String) { state.update { it.copy(expanded = if (path in it.expanded) it.expanded - path else it.expanded + path) }; reload() }
    fun setFilter(f: String) { state.update { it.copy(filter = f) }; reload() }
    fun setSort(o: SortOrder) { state.update { it.copy(sort = o) }; reload() }
    private fun fail(e: Throwable) = state.update { it.copy(message = e.message, error = true) }
    fun clearMessage() = state.update { it.copy(message = null) }

    fun newFile(parent: String, name: String, onDone: (String) -> Unit) = viewModelScope.launch {
        val p = if (parent.isEmpty()) name else "$parent/$name"
        io { ws.createFile(p); ws.guard.normalize(p) }.onSuccess { state.update { s -> s.copy(expanded = s.expanded + parent) }; reload(); onDone(it) }.onFailure(::fail)
    }
    fun newFolder(parent: String, name: String) = viewModelScope.launch {
        val p = if (parent.isEmpty()) name else "$parent/$name"
        io { ws.createFolder(p) }.onSuccess { state.update { s -> s.copy(expanded = s.expanded + parent) }; reload() }.onFailure(::fail)
    }
    fun rename(path: String, newName: String) = viewModelScope.launch { io { ws.rename(path, newName) }.onSuccess { reload() }.onFailure(::fail) }
    fun trash(path: String, onTrashed: (String) -> Unit) = viewModelScope.launch {
        io { c.projects.moveToTrash(pid, path) }.onSuccess { onTrashed(it.id); reload() }.onFailure(::fail)
    }
    fun restore(id: String) = viewModelScope.launch { io { c.projects.restore(id) }.onSuccess { reload() }.onFailure(::fail) }
    fun renameProject(n: String) = viewModelScope.launch { io { c.projects.rename(pid, n) }.onSuccess { state.update { s -> s.copy(name = n) } }.onFailure(::fail) }

    fun prepareExport() = viewModelScope.launch { io { ExportManifest.build(ws.root) }.onSuccess { m -> state.update { it.copy(exportManifest = m) } }.onFailure(::fail) }
    fun toggleExport(path: String) = state.update { s -> s.copy(exportManifest = s.exportManifest?.map { if (it.path == path) it.copy(include = !it.include) else it }) }
    fun cancelExport() = state.update { it.copy(exportManifest = null) }
    fun export(ctx: android.content.Context, uri: Uri) = viewModelScope.launch {
        val m = state.value.exportManifest ?: return@launch
        io { SafImport.writeTo(ctx, uri) { os -> c.builds.exportForDesktop(pid, ws.root, m, state.value.name, os) } }
            .onSuccess { state.update { it.copy(exportManifest = null, message = "Exported ${m.count { e -> e.include }} files with BUILD_INSTRUCTIONS.md.", error = false) } }
            .onFailure(::fail)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectScreen(pid: String, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: ExplorerViewModel = viewModel(key = "explorer-$pid") { ExplorerViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "file:<parent>", "folder:<parent>", "rename:<path>", "project"
    var actionsFor by remember { mutableStateOf<TreeNode?>(null) }
    var confirmTrash by remember { mutableStateOf<TreeNode?>(null) }
    LaunchedEffect(Unit) { vm.reload() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.export(ctx, uri) else vm.cancelExport() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { go(Routes.search(pid)) }) { Icon(Icons.Filled.Search, "Search project") }
                    IconButton(onClick = { go(Routes.build(pid)) }) { Icon(Icons.Filled.Build, "Build center") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New file") }, leadingIcon = { Icon(Icons.Filled.NoteAdd, null) }, onClick = { menu = false; dialog = "file:" })
                        DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) }, onClick = { menu = false; dialog = "folder:" })
                        DropdownMenuItem(text = { Text("Editor") }, leadingIcon = { Icon(Icons.Filled.Code, null) }, onClick = { menu = false; go(Routes.editor(pid)) })
                        DropdownMenuItem(text = { Text("Tests") }, leadingIcon = { Icon(Icons.Filled.Science, null) }, onClick = { menu = false; go(Routes.tests(pid)) })
                        DropdownMenuItem(text = { Text("AI assistant") }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) }, onClick = { menu = false; go(Routes.ai(pid)) })
                        DropdownMenuItem(text = { Text("Snapshots / versions") }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = { menu = false; go(Routes.snapshots(pid)) })
                        DropdownMenuItem(text = { Text("Export ZIP…") }, onClick = { menu = false; vm.prepareExport() })
                        DropdownMenuItem(text = { Text("Rename project") }, onClick = { menu = false; dialog = "project" })
                        SortOrder.entries.forEach { o -> DropdownMenuItem(text = { Text("Sort by ${o.name.lowercase()}" + if (s.sort == o) " ✓" else "") }, onClick = { menu = false; vm.setSort(o) }) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            s.message?.let { MessageBanner(it, s.error) { vm.clearMessage() } }
            OutlinedTextField(
                value = s.filter, onValueChange = vm::setFilter, singleLine = true, placeholder = { Text("Filter files by name") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (s.nodes.isEmpty()) Text("Empty project. Use ⋮ › New file.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.nodes, key = { it.path }) { n ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .combinedClickable(
                                onClick = { if (n.isDir) vm.toggle(n.path) else go(Routes.editor(pid, n.path)) },
                                onLongClick = { actionsFor = n },
                                onLongClickLabel = "File actions",
                            )
                            .padding(start = (12 + n.depth * 16).dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (n.isDir) Icon(if (n.path in s.expanded || s.filter.isNotEmpty()) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp))
                        else Spacer(Modifier.width(18.dp))
                        Icon(iconFor(n), null, Modifier.size(20.dp), tint = if (n.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(n.name, Modifier.weight(1f), maxLines = 1)
                        if (!n.isDir) Text(humanBytes(n.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    actionsFor?.let { n ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(n.name) },
            text = {
                Column {
                    if (n.isDir) {
                        TextButton(onClick = { actionsFor = null; dialog = "file:${n.path}" }) { Text("New file here") }
                        TextButton(onClick = { actionsFor = null; dialog = "folder:${n.path}" }) { Text("New folder here") }
                    }
                    TextButton(onClick = { actionsFor = null; dialog = "rename:${n.path}" }) { Text("Rename") }
                    TextButton(onClick = { actionsFor = null; confirmTrash = n }) { Text("Delete (move to trash)") }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } },
        )
    }
    confirmTrash?.let { n ->
        ConfirmDialog("Delete ${n.name}?", "It will be moved to the in-app trash. You can undo right away or restore later from Help › Trash.", "Move to trash", true,
            onConfirm = { vm.trash(n.path) { id -> scope.launch { if (snack.showSnackbar("Moved ${n.name} to trash", "Undo") == SnackbarResult.ActionPerformed) vm.restore(id) } } },
            onDismiss = { confirmTrash = null })
    }
    dialog?.let { d ->
        val nameRule: (String) -> String? = { v -> if (v.contains('/') || v.contains('\\') || v == "." || v == "..") "Use a plain name" else null }
        when {
            d.startsWith("file:") -> TextInputDialog("New file", "File name (e.g. Main.kt)", validate = nameRule, confirmLabel = "Create",
                onConfirm = { v -> vm.newFile(d.removePrefix("file:"), v) { p -> go(Routes.editor(pid, p)) } }, onDismiss = { dialog = null })
            d.startsWith("folder:") -> TextInputDialog("New folder", "Folder name", validate = nameRule, confirmLabel = "Create",
                onConfirm = { v -> vm.newFolder(d.removePrefix("folder:"), v) }, onDismiss = { dialog = null })
            d.startsWith("rename:") -> TextInputDialog("Rename", "New name", initial = d.removePrefix("rename:").substringAfterLast('/'), validate = nameRule, confirmLabel = "Rename",
                onConfirm = { v -> vm.rename(d.removePrefix("rename:"), v) }, onDismiss = { dialog = null })
            else -> TextInputDialog("Rename project", "Project name", initial = s.name, validate = { dev.pocketforge.engine.projects.ProjectNames.validate(it) },
                onConfirm = { vm.renameProject(it) }, onDismiss = { dialog = null })
        }
    }
    s.exportManifest?.let { m ->
        AlertDialog(
            onDismissRequest = vm::cancelExport,
            title = { Text("Export manifest") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    item { Text("Unchecked files are excluded. Secrets, build outputs and signing keys are excluded by default.", style = MaterialTheme.typography.bodySmall) }
                    items(m, key = { it.path }) { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = e.include, onCheckedChange = { vm.toggleExport(e.path) })
                            Column {
                                Text(e.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                e.reason?.let { Text("excluded: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exportLauncher.launch("${s.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip") }) { Text("Export ${m.count { it.include }} files") } },
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } },
        )
    }
}

private fun iconFor(n: TreeNode) = when {
    n.isDir -> Icons.Filled.Folder
    n.name.endsWith(".png") || n.name.endsWith(".jpg") || n.name.endsWith(".webp") || n.name.endsWith(".svg") -> Icons.Filled.Image
    n.name.endsWith(".md") || n.name.endsWith(".txt") -> Icons.Filled.Description
    n.name.substringAfterLast('.', "") in setOf("kt", "kts", "java", "xml", "json", "py", "js", "ts", "html", "css", "gradle") -> Icons.Filled.Code
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
