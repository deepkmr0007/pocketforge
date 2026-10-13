package dev.pocketforge.ide.feature.snapshots

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.diff.Diff
import dev.pocketforge.engine.snapshots.Snapshot
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.ConfirmDialog
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.TextInputDialog
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class SnapUi(val list: List<Snapshot> = emptyList(), val compareTitle: String? = null, val compareText: String = "", val message: String? = null, val error: Boolean = false, val busy: Boolean = false)

class SnapshotViewModel(private val c: AppContainer, private val pid: String) : ViewModel() {
    val state = MutableStateFlow(SnapUi())
    private val store = c.snapshots(pid)
    private val root = c.projects.dirOf(pid)

    fun load() = viewModelScope.launch { io { store.list() }.onSuccess { l -> state.update { it.copy(list = l) } } }

    private fun run(label: String, block: () -> String) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        io(block).onSuccess { m -> state.update { it.copy(message = m, error = false, busy = false) }; load() }
            .onFailure { e -> state.update { it.copy(message = "$label failed: ${e.message}", error = true, busy = false) } }
    }

    fun create(label: String) = run("Snapshot") { val s = store.create(root, label); "Saved \"${s.label}\" (${s.files.size} files)." }
    fun restore(s: Snapshot) = run("Restore") { store.restore(s.id, root); "Restored \"${s.label}\". A \"Before restore\" snapshot was saved so you can undo." }
    fun delete(s: Snapshot) = run("Delete") { store.delete(s.id); "Deleted \"${s.label}\"." }

    fun compare(s: Snapshot) = viewModelScope.launch {
        io {
            val d = store.diff(s.id, root)
            if (d.isEmpty) "No differences: the project matches this snapshot."
            else buildString {
                d.added.forEach { append("+++ added since snapshot: $it\n") }
                d.removed.forEach { append("--- removed since snapshot: $it\n") }
                for (p in d.modified.take(50)) {
                    val old = s.files.first { it.path == p }
                    val oldBytes = store.contentOf(old)
                    val newBytes = root.resolve(p).readBytes()
                    if (TextCodec.looksBinary(oldBytes) || TextCodec.looksBinary(newBytes)) append("@@ binary file changed: $p\n")
                    else append(Diff.unified(TextCodec.decode(oldBytes).text, TextCodec.decode(newBytes).text, p))
                }
                if (d.modified.size > 50) append("@@ …and ${d.modified.size - 50} more modified files\n")
            }
        }.onSuccess { t -> state.update { it.copy(compareTitle = "Snapshot \"${s.label}\" → now", compareText = t) } }
            .onFailure { e -> state.update { it.copy(message = e.message, error = true) } }
    }

    fun closeCompare() = state.update { it.copy(compareTitle = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotScreen(pid: String, onBack: () -> Unit) {
    val c = appContainer()
    val vm: SnapshotViewModel = viewModel(key = "snap-$pid") { SnapshotViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Snapshot?>(null) }
    var confirmDelete by remember { mutableStateOf<Snapshot?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Snapshots") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { create = true }) { Icon(Icons.Filled.Add, "New snapshot") } },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { Text("Local version history. Unchanged files are stored once (deduplicated). Build outputs are skipped.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            s.message?.let { m -> item { MessageBanner(m, s.error) } }
            items(s.list, key = { it.id }) { snap ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(snap.label, style = MaterialTheme.typography.titleSmall)
                        Text("${DateFormat.getDateTimeInstance().format(Date(snap.createdAt))} · ${snap.files.size} files · ${humanBytes(snap.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.compare(snap) }) { Text("Compare") }
                            TextButton(onClick = { confirmRestore = snap }, enabled = !s.busy) { Text("Restore") }
                            TextButton(onClick = { confirmDelete = snap }, enabled = !s.busy) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    if (create) TextInputDialog("New snapshot", "Label", initial = "Snapshot " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()), confirmLabel = "Save",
        onConfirm = { vm.create(it) }, onDismiss = { create = false })
    confirmRestore?.let { snap ->
        ConfirmDialog("Restore \"${snap.label}\"?", "Your current files are saved as a \"Before restore\" snapshot first, so this can be undone. Close open editor tabs for affected files first.", "Restore",
            onConfirm = { vm.restore(snap) }, onDismiss = { confirmRestore = null })
    }
    confirmDelete?.let { snap ->
        ConfirmDialog("Delete \"${snap.label}\"?", "This snapshot cannot be recovered after deletion. Your project files are not affected.", "Delete", destructive = true,
            onConfirm = { vm.delete(snap) }, onDismiss = { confirmDelete = null })
    }
    s.compareTitle?.let { t ->
        AlertDialog(onDismissRequest = vm::closeCompare, title = { Text(t) },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { DiffView(s.compareText) } },
            confirmButton = { TextButton(onClick = vm::closeCompare) { Text("Close") } })
    }
}
