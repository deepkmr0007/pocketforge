package dev.pocketforge.ide.feature.builds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.ide.Routes
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.SectionTitle
import dev.pocketforge.ide.ui.io

/**
 * Test center. Tests cannot run on the phone itself in this version (no on-device JVM toolchain);
 * this screen says so plainly, lists the project's test files, and runs them on the paired
 * Build Host via the allow-listed `testDebugUnitTest` task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(pid: String, go: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val vm: BuildViewModel = viewModel(key = "build-$pid") { BuildViewModel(c, pid) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    var testFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        vm.refresh()
        io {
            val g = PathGuard(c.projects.dirOf(pid))
            g.walkFiles().map { g.relativize(it) }.filter { p ->
                p.contains("/test/") || p.contains("/androidTest/") || p.substringAfterLast('/').let { n -> n.startsWith("test_") || n.endsWith("Test.kt") || n.endsWith("Test.java") || n.endsWith(".test.js") }
            }.toList()
        }.onSuccess { testFiles = it }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Test center") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            s.message?.let { m -> item { MessageBanner(m, s.error) { vm.msg(null, false) } } }
            item {
                MessageBanner("Running tests on this phone is not available in this version. Unit tests run on your paired Build Host (Gradle testDebugUnitTest); results and logs appear below.")
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    when {
                        s.running != null -> { Text("Running ${s.running}…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        settings.buildHost == null || !s.hostTokenSet -> TextButton(onClick = { go(Routes.build(pid)) }) { Text("Pair a Build Host in Build center first") }
                        else -> Button(onClick = { vm.review("testDebugUnitTest") }, enabled = testFiles.isNotEmpty()) { Text("Run unit tests on ${settings.buildHost?.name}") }
                    }
                }
            }
            item { SectionTitle("Test files found (${testFiles.size})") }
            if (testFiles.isEmpty()) item { Text("No test files found (looked for src/test, src/androidTest, *Test.kt, test_*.py).", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(testFiles) { f ->
                Text(f, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { go(Routes.editor(pid, f)) }.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item { SectionTitle("Test runs") }
            val runs = s.history.filter { it.task.contains("test", ignoreCase = true) }
            if (runs.isEmpty()) item { Text("No test runs yet.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(runs, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth().clickable { vm.openLog(a) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${a.status.name} · exit ${a.exitCode ?: "?"}", color = if (a.status.name == "SUCCESS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(a.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    s.manifest?.let { m ->
        ManifestDialog("Files to upload for ${s.reviewTask}", "Only checked files go to your Build Host; a consent screen follows.", m, vm::toggle,
            confirm = "Continue (${m.count { it.include }} files)", onConfirm = vm::startRemote, onDismiss = vm::cancelReview)
    }
    s.log?.let { l ->
        AlertDialog(
            onDismissRequest = vm::closeLog, title = { Text("Test log") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(l.text.takeLast(80_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp) } } },
            confirmButton = { TextButton(onClick = vm::closeLog) { Text("Close") } },
        )
    }
}
