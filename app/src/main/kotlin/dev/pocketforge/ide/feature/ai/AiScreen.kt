package dev.pocketforge.ide.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.ai.AiAction
import dev.pocketforge.engine.ai.AiReview
import dev.pocketforge.engine.ai.AiTask
import dev.pocketforge.engine.ai.ContextBuilder
import dev.pocketforge.engine.ai.ContextFile
import dev.pocketforge.engine.ai.ResponseParser
import dev.pocketforge.engine.ai.consentFor
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.LineEnding
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.engine.storage.TextEncoding
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.Chip
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.SectionTitle
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Selected(val path: String, val range: String = "")

data class AiUi(
    val action: AiAction = AiAction.EXPLAIN,
    val prompt: String = "",
    val selected: List<Selected> = emptyList(),
    val handoff: String? = null,
    val allFiles: List<String> = emptyList(),
    val running: Boolean = false,
    val review: AiReview? = null,
    val accepted: Set<String> = emptySet(),
    val message: String? = null,
    val error: Boolean = false,
)

class AiViewModel(private val c: AppContainer, private val pid: String) : ViewModel() {
    val state = MutableStateFlow(AiUi())
    private val ws = c.workspace(pid)

    init {
        c.aiHandoff.value?.let { h -> state.update { it.copy(handoff = h, action = AiAction.EXPLAIN_ERROR) }; c.aiHandoff.value = null }
        viewModelScope.launch { io { ws.guard.walkFiles().map { ws.guard.relativize(it) }.filter { !it.startsWith("build/") }.take(5000).toList() }.onSuccess { l -> state.update { it.copy(allFiles = l) } } }
    }

    fun set(f: (AiUi) -> AiUi) = state.update(f)

    fun send() {
        val s = state.value
        val provider = c.aiProvider() ?: run { msg("Set up an AI provider and key in Settings first. AI is optional.", true); return }
        state.update { it.copy(running = true, review = null, accepted = emptySet(), message = null) }
        viewModelScope.launch {
            io {
                val files = s.selected.map { sel ->
                    val text = TextCodec.decode(ws.guard.resolve(sel.path).readBytes()).text
                    val r = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(sel.range.trim())?.let { m -> m.groupValues[1].toInt()..m.groupValues[2].toInt() }
                    ContextFile(sel.path, text, r)
                } + listOfNotNull(s.handoff?.let { ContextFile("build-log.txt", it) })
                val ctx = ContextBuilder.build(files)
                val task = AiTask(s.action, s.prompt, ctx)
                val consent = consentFor(provider, task)
                val ticket = c.consentGateway.request(consent) // blocks until you answer the dialog
                val resp = c.gatedTransport.send(ticket, consent.request)
                val answer = provider.parseResponse(resp)
                ResponseParser.parse(answer, ws.guard) { p -> ws.guard.resolve(p).takeIf { it.isFile && it.length() < 2_000_000 }?.let { f -> TextCodec.decode(f.readBytes()).text } }
            }.onSuccess { r -> state.update { it.copy(running = false, review = r) } }
                .onFailure { e -> state.update { it.copy(running = false) }; msg(e.message, true) }
        }
    }

    fun toggleAccept(path: String) = state.update { it.copy(accepted = if (path in it.accepted) it.accepted - path else it.accepted + path) }

    /** Applies only the proposals you accepted, after taking an automatic snapshot. */
    fun applyAccepted() {
        val r = state.value.review ?: return
        val chosen = r.proposals.filter { it.path in state.value.accepted }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            io {
                c.snapshots(pid).create(ws.root, "Before AI change (${chosen.size} file(s))")
                for (p in chosen) {
                    val f = ws.guard.resolve(p.path)
                    val current = if (f.isFile) TextCodec.decode(f.readBytes()) else null
                    if ((current?.text ?: "") != (p.oldText ?: "")) throw PfException("ai.stale", "${p.path} changed since the suggestion was made. Ask again.")
                    val bytes = TextCodec.encode(p.newText, current?.encoding ?: TextEncoding.UTF8, current?.lineEnding ?: LineEnding.LF)
                    AtomicFileWriter.write(f, bytes)
                }
                chosen.size
            }.onSuccess { n -> state.update { it.copy(review = null, accepted = emptySet()) }; msg("Applied $n file(s). A snapshot was taken first, so you can restore.", false) }
                .onFailure { e -> msg(e.message, true) }
        }
    }

    fun msg(m: String?, err: Boolean) = state.update { it.copy(message = m, error = err) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(pid: String, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: AiViewModel = viewModel(key = "ai-$pid") { AiViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var picking by remember { mutableStateOf(false) }
    var providerLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) { providerLabel = io { c.aiProvider()?.displayName }.getOrNull() }

    Scaffold(topBar = { TopAppBar(title = { Text("AI assistant") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            item {
                if (providerLabel == null) MessageBanner("AI is off. It is optional — the IDE works fully without it. To enable, choose a provider and add a key in Settings.")
                else Text("Online provider: $providerLabel · not on-device · every request asks you first", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                if (providerLabel == null) TextButton(onClick = { go(Routes.SETTINGS) }) { Text("Open Settings") }
            }
            s.message?.let { m -> item { MessageBanner(m, s.error) { vm.msg(null, false) } } }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiAction.entries.forEach { a -> FilterChip(s.action == a, { vm.set { it.copy(action = a) } }, label = { Text(a.title) }) }
                }
                OutlinedTextField(s.prompt, { t -> vm.set { it.copy(prompt = t) } }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text("Your request (editable, sent as written)") })
            }
            item { SectionTitle("Context you choose to share") }
            items(s.selected, key = { it.path }) { sel ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sel.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(sel.range, { t -> vm.set { st -> st.copy(selected = st.selected.map { if (it.path == sel.path) it.copy(range = t) else it }) } },
                        Modifier.width(110.dp), singleLine = true, label = { Text("lines") })
                    TextButton(onClick = { vm.set { st -> st.copy(selected = st.selected.filterNot { it.path == sel.path }) } }) { Text("Remove") }
                }
            }
            s.handoff?.let { item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Build log attached (${it.length} chars)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { vm.set { st -> st.copy(handoff = null) } }) { Text("Remove") } } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) { Text("Add files…") }
                    Button(onClick = vm::send, enabled = !s.running && providerLabel != null && (s.prompt.isNotBlank() || s.selected.isNotEmpty() || s.handoff != null)) { Text("Review & send") }
                }
                Text("Secrets are masked before you review the request. Nothing is sent until you approve the consent screen.", style = MaterialTheme.typography.labelSmall)
                if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            s.review?.let { r ->
                item { SectionTitle("Answer (AI output can be wrong)") }
                item { SelectionContainer { Text(r.explanation, style = MaterialTheme.typography.bodyMedium) } }
                if (r.rejected.isNotEmpty()) item { MessageBanner("Ignored unsafe suggestions: " + r.rejected.joinToString("; "), isError = true) }
                if (r.proposals.isNotEmpty()) item { SectionTitle("Proposed changes — accept per file") }
                items(r.proposals, key = { "p" + it.path }) { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(p.path in s.accepted, { vm.toggleAccept(p.path) })
                                Text((if (p.oldText == null) "New file: " else "") + p.path, fontFamily = FontFamily.Monospace)
                            }
                            DiffView(p.unifiedDiff, Modifier.heightIn(max = 360.dp))
                        }
                    }
                }
                if (r.proposals.isNotEmpty()) item {
                    Button(onClick = vm::applyAccepted, enabled = s.accepted.isNotEmpty()) { Text("Apply ${s.accepted.size} accepted (snapshot first)") }
                }
                if (r.commands.isNotEmpty()) {
                    item { SectionTitle("Suggested commands — never run automatically") }
                    items(r.commands) { cmd ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(cmd.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                cmd.risks.forEach { Chip(it.label) }
                                TextButton(onClick = { clipboard.setText(AnnotatedString(cmd.command)) }) { Text("Copy") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        var chosen by remember { mutableStateOf(s.selected.map { it.path }.toSet()) }
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Choose files (max 10)") },
            text = {
                Column {
                    OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Filter") })
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(s.allFiles.filter { it.contains(filter, true) }.take(500)) { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(f in chosen, { checked -> chosen = if (checked && chosen.size < 10) chosen + f else chosen - f })
                                Text(f, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.set { st -> st.copy(selected = chosen.map { p -> st.selected.firstOrNull { it.path == p } ?: Selected(p) }) }
                    picking = false
                }) { Text("Use ${chosen.size}") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}
