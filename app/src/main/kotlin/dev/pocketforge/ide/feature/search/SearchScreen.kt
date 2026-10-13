package dev.pocketforge.ide.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.search.PlannedReplacement
import dev.pocketforge.engine.search.ProjectSearcher
import dev.pocketforge.engine.search.ReplaceUndo
import dev.pocketforge.engine.search.Replacer
import dev.pocketforge.engine.search.SearchQuery
import dev.pocketforge.engine.search.SearchResult
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class SearchUi(
    val query: SearchQuery = SearchQuery(""),
    val include: String = "",
    val replacement: String = "",
    val result: SearchResult? = null,
    val plan: List<PlannedReplacement>? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

class SearchViewModel(private val c: AppContainer, pid: String) : ViewModel() {
    val state = MutableStateFlow(SearchUi())
    private val root = c.projects.dirOf(pid)
    private var job: Job? = null
    private var cancel = AtomicBoolean(false)
    var lastUndo: ReplaceUndo? = null; private set

    fun edit(f: (SearchUi) -> SearchUi) = state.update { f(it).copy(plan = null) }

    fun run() {
        job?.cancel(); cancel.set(true); cancel = AtomicBoolean(false)
        val flag = cancel
        val s = state.value
        val q = s.query.copy(include = s.include.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        state.update { it.copy(busy = true, message = null, plan = null) }
        job = viewModelScope.launch {
            io { ProjectSearcher.search(root, q) { flag.get() } }
                .onSuccess { r -> state.update { it.copy(result = r, busy = false, message = if (r.truncated) "Showing first ${r.matches.size} matches." else null, error = false) } }
                .onFailure { e -> state.update { it.copy(busy = false, message = e.message, error = true) } }
        }
    }

    fun preview() = viewModelScope.launch {
        val s = state.value; val r = s.result ?: return@launch
        io { Replacer.plan(root, s.query, r.matches, s.replacement) }
            .onSuccess { p -> state.update { it.copy(plan = p) } }
            .onFailure { e -> state.update { it.copy(message = e.message, error = true) } }
    }

    fun togglePlan(i: Int) = state.update { s -> s.copy(plan = s.plan?.mapIndexed { idx, p -> if (idx == i) p.copy(selected = !p.selected) else p }) }

    fun applyPlan(onDone: (String) -> Unit) = viewModelScope.launch {
        val p = state.value.plan ?: return@launch
        io { Replacer.apply(root, p) }.onSuccess { o ->
            lastUndo = o.undo
            val stale = if (o.staleFiles.isNotEmpty()) " Skipped ${o.staleFiles.size} file(s) that changed since the search." else ""
            state.update { it.copy(plan = null, result = null) }
            onDone("Replaced ${o.replacements} in ${o.filesChanged} file(s).$stale")
        }.onFailure { e -> state.update { it.copy(message = e.message, error = true) } }
    }

    fun undo() = viewModelScope.launch {
        val u = lastUndo ?: return@launch
        io { Replacer.undo(root, u) }.onSuccess { conflicts ->
            lastUndo = null
            state.update { it.copy(message = if (conflicts.isEmpty()) "Replacement undone." else "Undone, except ${conflicts.size} file(s) edited since.", error = conflicts.isNotEmpty()) }
            run()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(pid: String, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: SearchViewModel = viewModel(key = "search-$pid") { SearchViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Search in project") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(s.query.text, { t -> vm.edit { it.copy(query = it.query.copy(text = t)) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(s.query.regex, { vm.edit { it.copy(query = it.query.copy(regex = !it.query.regex)) } }, label = { Text(".* Regex") })
                FilterChip(s.query.caseSensitive, { vm.edit { it.copy(query = it.query.copy(caseSensitive = !it.query.caseSensitive)) } }, label = { Text("Aa Case") })
                FilterChip(s.query.wholeWord, { vm.edit { it.copy(query = it.query.copy(wholeWord = !it.query.wholeWord)) } }, label = { Text("Word") })
            }
            OutlinedTextField(s.include, { t -> vm.edit { it.copy(include = t) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Only files (globs, comma-separated, e.g. **/*.kt)") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::run, enabled = s.query.text.isNotEmpty() && !s.busy) { Text(if (s.busy) "Searching…" else "Search") }
            }
            OutlinedTextField(s.replacement, { t -> vm.edit { it.copy(replacement = t) } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (s.query.regex) "Replace with (\$1 = group 1)" else "Replace with") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.preview() }, enabled = (s.result?.matches?.isNotEmpty() == true)) { Text("Preview replace") }
                s.plan?.let { p ->
                    Button(onClick = {
                        vm.applyPlan { msg -> scope.launch { if (snack.showSnackbar(msg, "Undo") == SnackbarResult.ActionPerformed) vm.undo() } }
                    }, enabled = p.any { it.selected }) { Text("Apply ${p.count { it.selected }}") }
                }
            }
            s.message?.let { MessageBanner(it, s.error) }
            val plan = s.plan
            LazyColumn(Modifier.fillMaxSize()) {
                if (plan != null) {
                    item { Text("Review each change. Files edited after the search are skipped automatically.", style = MaterialTheme.typography.bodySmall) }
                    items(plan.size) { i ->
                        val p = plan[i]; val m = p.match
                        val col = (m.column - 1).coerceIn(0, m.lineText.length)
                        val end = (col + m.length).coerceAtMost(m.lineText.length)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(p.selected, { vm.togglePlan(i) })
                            Column {
                                Text("${m.path}:${m.line}", style = MaterialTheme.typography.labelSmall)
                                Text("- " + m.lineText.trim().take(160), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Text("+ " + (m.lineText.substring(0, col) + p.replacement + m.lineText.substring(end)).trim().take(160), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else s.result?.let { r ->
                    item { Text("${r.matches.size} matches in ${r.matches.map { it.path }.distinct().size} files (${r.filesScanned} scanned)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp)) }
                    r.matches.groupBy { it.path }.forEach { (path, ms) ->
                        item(key = "h$path") { Text(path, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        items(ms, key = { "${it.path}:${it.offset}" }) { m ->
                            Text(
                                "${m.line}: ${m.lineText.trim().take(200)}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().clickable { go(Routes.editor(pid, m.path, m.line)) }.padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (r.skipped.isNotEmpty()) item { Text("Skipped: " + r.skipped.joinToString(), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
