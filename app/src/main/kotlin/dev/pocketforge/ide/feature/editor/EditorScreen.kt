package dev.pocketforge.ide.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.diff.Diff
import dev.pocketforge.engine.editor.Severity
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.TextInputDialog
import java.text.DateFormat
import java.util.Date

private val TOOLBAR_KEYS = listOf("{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", ";", ":", "=", ".", ",", "/", "\\", "|", "&", "!", "?", "+", "-", "*", "_", "$", "#", "@", "`")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(pid: String, path: String?, line: Int, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: EditorViewModel = viewModel(key = "editor-$pid") { EditorViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var gotoDialog by remember { mutableStateOf(false) }
    var closeAsk by remember { mutableStateOf<Int?>(null) }
    var compare by remember { mutableStateOf(false) }
    LaunchedEffect(path, line) { if (path != null) vm.open(path, line) else vm.checkExternal() }
    val tab = s.tab

    val onKey: (KeyEvent) -> Boolean = { e ->
        if (e.type != KeyEventType.KeyDown) false
        else if (e.isCtrlPressed) when (e.key) {
            Key.S -> { vm.save(); true }
            Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
            Key.Y -> { vm.redo(); true }
            Key.F -> { vm.toggleFind(); true }
            Key.G -> { gotoDialog = true; true }
            else -> false
        } else if (e.key == Key.Tab) {
            val sel = tab?.value?.selection
            if (sel != null && sel.collapsed && !e.isShiftPressed) vm.insert(settings.indentUnit) else vm.indent(e.isShiftPressed)
            true
        } else false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab?.path?.substringAfterLast('/') ?: "Editor", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        tab?.let { Text(it.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.save() }, enabled = tab?.dirty == true) { Icon(Icons.Filled.Save, "Save") }
                    IconButton(onClick = vm::undo, enabled = tab != null) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = tab != null) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = vm::toggleFind, enabled = tab != null) { Icon(Icons.Filled.FindReplace, "Find and replace") }
                    IconButton(onClick = vm::toggleDiagnostics, enabled = tab != null) {
                        val n = tab?.diagnostics?.count { it.severity == Severity.ERROR } ?: 0
                        BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) { Icon(Icons.Filled.ErrorOutline, "Problems") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Save all") }, onClick = { menu = false; vm.saveAll() })
                        DropdownMenuItem(text = { Text("Go to line…") }, onClick = { menu = false; gotoDialog = true })
                        DropdownMenuItem(text = { Text("Project files") }, onClick = { menu = false; go(Routes.project(pid)) })
                        DropdownMenuItem(text = { Text("Ask AI about this file") }, onClick = { menu = false; go(Routes.ai(pid)) })
                        if (s.active >= 0) DropdownMenuItem(text = { Text("Close tab") }, onClick = { menu = false; if (tab?.dirty == true) closeAsk = s.active else vm.close(s.active) })
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (s.tabs.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = s.active.coerceAtLeast(0), edgePadding = 4.dp) {
                    s.tabs.forEachIndexed { i, t ->
                        Tab(selected = i == s.active, onClick = { vm.select(i) }, text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (t.dirty) "● " else "") + t.path.substringAfterLast('/'), maxLines = 1)
                                Icon(Icons.Filled.Close, "Close ${t.path}", Modifier.size(16.dp).padding(start = 2.dp).clickable { if (t.dirty) closeAsk = i else vm.close(i) })
                            }
                        })
                    }
                }
            }
            s.message?.let { MessageBanner(it, s.error) { vm.clearMessage() } }
            if (tab == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No file open.", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { go(Routes.project(pid)) }) { Text("Open from project files") }
                }
                return@Column
            }
            tab.draft?.let { d ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Unsaved edits from ${DateFormat.getTimeInstance().format(Date(d.savedAtMillis))} were recovered.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::restoreDraft) { Text("Restore") }
                        TextButton(onClick = vm::discardDraft) { Text("Discard") }
                    }
                }
            }
            if (tab.external != ExternalChange.NONE) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab.external == ExternalChange.DELETED) "File was deleted outside the editor." else "File changed outside the editor.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { compare = true }) { Text("Compare") }
                        TextButton(onClick = vm::reloadFromDisk, enabled = tab.external != ExternalChange.DELETED) { Text("Reload") }
                        TextButton(onClick = vm::keepMine) { Text("Keep mine") }
                    }
                }
            }
            when (tab.doc.mode) {
                LargeFilePolicy.Mode.READ_ONLY_PREVIEW -> MessageBanner("Large file (${tab.doc.sizeBytes / 1024} KB): showing the first part read-only to protect memory.")
                LargeFilePolicy.Mode.EDIT_NO_HIGHLIGHT -> MessageBanner("Large file: syntax highlighting is off to keep typing responsive.")
                else -> {}
            }
            if (s.find.visible) FindBar(s.find, vm)
            CodeEditor(
                value = tab.value,
                onValueChange = vm::onValueChange,
                spans = if (tab.highlight) tab.spans else emptyList(),
                bracket = tab.bracket,
                errorLines = tab.diagnostics.filter { it.severity == Severity.ERROR }.map { it.line }.toSet(),
                readOnly = tab.doc.readOnly,
                fontSizeSp = settings.fontSizeSp,
                wrap = settings.wordWrap,
                onKey = onKey,
                modifier = Modifier.weight(1f),
            )
            if (s.showDiagnostics) {
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    if (tab.diagnostics.isEmpty()) item { Text("No problems found by the offline analyzers.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                    items(tab.diagnostics) { d ->
                        Text(
                            "${d.severity.name.lowercase()}  L${d.line}:${d.column}  ${d.message}  [${d.source}]",
                            Modifier.fillMaxWidth().clickable { vm.goToLine(d.line) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            HorizontalDivider()
            // Coding keyboard toolbar
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.indent(false) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") }
                IconButton(onClick = { vm.indent(true) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent") }
                IconButton(onClick = { vm.moveCursor(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Cursor left") }
                IconButton(onClick = { vm.moveCursor(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Cursor right") }
                TOOLBAR_KEYS.forEach { k ->
                    TextButton(onClick = { vm.insert(k) }, enabled = !tab.doc.readOnly, modifier = Modifier.width(44.dp)) { Text(k, fontFamily = FontFamily.Monospace) }
                }
            }
            // Status line
            val sel = tab.value.selection.start
            val before = tab.value.text.substring(0, sel.coerceAtMost(tab.value.text.length))
            val ln = before.count { it == '\n' } + 1
            val col = sel - (before.lastIndexOf('\n') + 1) + 1
            Text(
                "Ln $ln, Col $col · ${tab.doc.decoded.encoding.label} · ${tab.doc.decoded.lineEnding.label}${if (tab.doc.decoded.mixedLineEndings) " (mixed)" else ""} · ${tab.language.label} · " +
                    if (tab.dirty) "Modified" else tab.lastSavedAt?.let { "Saved ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: "Unchanged",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    if (gotoDialog) TextInputDialog("Go to line", "Line number", validate = { if (it.toIntOrNull()?.let { n -> n > 0 } == true) null else "Enter a positive number" },
        onConfirm = { vm.goToLine(it.toInt()) }, onDismiss = { gotoDialog = false })
    closeAsk?.let { i ->
        AlertDialog(
            onDismissRequest = { closeAsk = null },
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to ${s.tabs.getOrNull(i)?.path} before closing?") },
            confirmButton = { TextButton(onClick = { s.tabs.getOrNull(i)?.let { vm.save(it.path) }; vm.close(i); closeAsk = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { closeAsk = null }) { Text("Cancel") }
                    TextButton(onClick = { vm.close(i); closeAsk = null }) { Text("Discard") }
                }
            },
        )
    }
    if (compare && tab != null) {
        AlertDialog(
            onDismissRequest = { compare = false },
            title = { Text("Disk version → your version") },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { DiffView(Diff.unified(tab.conflictDiskText ?: "", tab.value.text, tab.path).ifEmpty { "No differences." }) } },
            confirmButton = { TextButton(onClick = { compare = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun FindBar(f: FindState, vm: EditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(f.query, { vm.setFind(f.copy(query = it)) }, Modifier.weight(1f), singleLine = true, label = { Text("Find (${f.matchCount})") })
            TextButton(onClick = { vm.findNext(true) }) { Text("Prev") }
            TextButton(onClick = { vm.findNext() }) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(f.replacement, { vm.setFind(f.copy(replacement = it)) }, Modifier.weight(1f), singleLine = true, label = { Text("Replace") })
            TextButton(onClick = vm::replaceCurrent) { Text("Replace") }
            TextButton(onClick = vm::replaceAll) { Text("All") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = f.caseSensitive, onClick = { vm.setFind(f.copy(caseSensitive = !f.caseSensitive)) }, label = { Text("Match case") })
            Spacer(Modifier.width(8.dp))
            Text("Replace All is one undo step.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
