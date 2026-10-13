package dev.pocketforge.ide.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.pocketforge.engine.projects.License
import dev.pocketforge.engine.projects.PackageNames
import dev.pocketforge.engine.projects.ProjectNames
import dev.pocketforge.engine.projects.TemplateInput
import dev.pocketforge.engine.projects.Templates
import dev.pocketforge.ide.appContainer
import dev.pocketforge.ide.ui.MessageBanner
import dev.pocketforge.ide.ui.io
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectScreen(onCreated: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var templateId by remember { mutableStateOf(Templates.all.first().id) }
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var pkgEdited by remember { mutableStateOf(false) }
    var minSdk by remember { mutableIntStateOf(26) }
    var readme by remember { mutableStateOf(true) }
    var license by remember { mutableStateOf(License.NONE) }
    var author by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val template = Templates.byId(templateId)
    val effectivePkg = if (pkgEdited) pkg else PackageNames.suggestFrom(name.ifBlank { "app" })
    val nameError = ProjectNames.validate(name)
    val pkgError = if (template.needsPackageId) PackageNames.validate(effectivePkg) else null

    Scaffold(topBar = {
        TopAppBar(title = { Text("New project") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { MessageBanner(it, isError = true) { error = null } }
            Text("Template", style = MaterialTheme.typography.titleSmall)
            Templates.all.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = t.id == templateId, role = Role.RadioButton) { templateId = t.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = t.id == templateId, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        Text(t.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = name.isNotEmpty() && nameError != null, supportingText = { if (name.isNotEmpty() && nameError != null) Text(nameError) },
            )
            if (template.needsPackageId) {
                OutlinedTextField(
                    value = effectivePkg, onValueChange = { pkg = it; pkgEdited = true }, label = { Text("Package name (application ID)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), isError = pkgError != null, supportingText = { Text(pkgError ?: "Cannot be changed easily after publishing.") },
                )
            }
            if (template.id == "android-compose") {
                Text("Minimum Android version: API $minSdk", style = MaterialTheme.typography.bodyMedium)
                Slider(value = minSdk.toFloat(), onValueChange = { minSdk = it.toInt() }, valueRange = 21f..36f, steps = 14)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = readme, onCheckedChange = { readme = it }); Text("  Add README with prerequisites")
            }
            Text("License", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                License.entries.forEach { l -> FilterChip(selected = license == l, onClick = { license = l }, label = { Text(l.label) }) }
            }
            if (license != License.NONE) OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Copyright holder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Prerequisites to build: " + template.prerequisites.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Text("Saved in PocketForge's private storage. Use Export to copy it elsewhere.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy && nameError == null && pkgError == null,
                onClick = {
                    busy = true
                    scope.launch {
                        io { c.projects.create(templateId, TemplateInput(name, effectivePkg, minSdk, readme, license, author.trim())) }
                            .onSuccess { onCreated(it.id) }
                            .onFailure { error = it.message; busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Creating…" else "Create project") }
        }
    }
}
