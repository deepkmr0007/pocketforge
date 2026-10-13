package dev.pocketforge.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.engine.toolchain.Capability
import kotlinx.coroutines.delay

/**
 * The per-request network consent dialog. Shows who, what, why and the risks; "Review data" reveals
 * the exact (redacted) bytes that will be sent. Approve is enabled after a short delay so it cannot
 * be hit by an accidental double-tap.
 */
@Composable
fun ConsentDialog(req: ConsentRequest, onDecision: (ConsentDecision) -> Unit) {
    var review by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(req) { delay(800); armed = true }
    AlertDialog(
        onDismissRequest = { onDecision(ConsentDecision.CANCEL) },
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Allow this network request?") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledValue("Feature", req.feature)
                LabeledValue("Sends to", "${req.provider}\n${req.destination}")
                LabeledValue("Why", req.purpose)
                LabeledValue("Size", humanBytes(req.totalBytes) + if (req.sendsSourceCode) " · includes source code" else " · no source code")
                if (req.redactionsApplied > 0) LabeledValue("Redacted", "${req.redactionsApplied} likely secret(s) masked")
                Text("Risks", style = MaterialTheme.typography.labelLarge)
                req.risks.forEach { Row { Text("• ", fontWeight = FontWeight.Bold); Text(it, style = MaterialTheme.typography.bodySmall) } }
                Text("Data (${req.dataItems.size})", style = MaterialTheme.typography.labelLarge)
                req.dataItems.take(30).forEach { Text("${it.label}  (${humanBytes(it.sizeBytes)})", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                if (req.dataItems.size > 30) Text("…and ${req.dataItems.size - 30} more", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { review = !review }) { Text(if (review) "Hide exact data" else "Review exact data") }
                if (review) {
                    Text("Headers: " + req.request.headers.keys.joinToString() + " (values hidden)", style = MaterialTheme.typography.bodySmall)
                    val body = req.request.body
                    val isText = body.take(4096).none { it == 0.toByte() }
                    val shown = if (isText) String(body, Charsets.UTF_8).take(60_000) else "[binary ZIP, ${humanBytes(body.size.toLong())} — file list above]"
                    SelectionContainer {
                        Text(
                            shown + if (isText && body.size > 60_000) "\n…(${humanBytes(body.size.toLong())} total)" else "",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp),
                        )
                    }
                }
                Text("This approval covers this one request only.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(enabled = armed, onClick = { onDecision(ConsentDecision.APPROVE_ONCE) }) { Text("Approve once") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecision(ConsentDecision.CANCEL) }) { Text("Cancel") }
                TextButton(onClick = { onDecision(ConsentDecision.DENY) }) { Text("Deny") }
            }
        },
    )
}

@Composable
fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (destructive) Icons.Filled.Warning else Icons.Filled.Info, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    validate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val error = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
                isError = error != null && value.isNotEmpty(), supportingText = { if (error != null && value.isNotEmpty()) Text(error) },
            )
        },
        confirmButton = { TextButton(enabled = error == null && value.isNotBlank(), onClick = { onConfirm(value.trim()); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MessageBanner(text: String, isError: Boolean = false, onDismiss: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.Error else Icons.Filled.Info, null)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
fun statusColor(s: CapStatus): Color = when (s) {
    CapStatus.SUPPORTED -> Color(0xFF2E7D32)
    CapStatus.DEVICE_DEPENDENT, CapStatus.EXPERIMENTAL, CapStatus.PERMISSION_REQUIRED -> Color(0xFFB26A00)
    CapStatus.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    CapStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
}

@Composable
fun CapabilityRow(c: Capability) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = "${c.title}: ${c.status.label}. ${c.detail}" }) {
        Icon(
            when (c.status) { CapStatus.SUPPORTED -> Icons.Filled.CheckCircle; CapStatus.UNAVAILABLE -> Icons.Filled.Error; else -> Icons.Filled.Info },
            null, tint = statusColor(c.status), modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(c.status.label, style = MaterialTheme.typography.labelSmall, color = statusColor(c.status))
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Unified-diff viewer with +/- colouring, horizontally scrollable, selectable. */
@Composable
fun DiffView(unified: String, modifier: Modifier = Modifier) {
    val add = Color(0x332E7D32); val del = Color(0x33C62828); val hdr = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.horizontalScroll(rememberScrollState())) {
        unified.lines().take(4000).forEach { l ->
            val bg = when {
                l.startsWith("+++") || l.startsWith("---") || l.startsWith("@@") -> hdr
                l.startsWith("+") -> add
                l.startsWith("-") -> del
                else -> Color.Transparent
            }
            Text(l.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.background(bg).padding(horizontal = 6.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun Chip(text: String) { AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) }) }

@Composable
fun Divider() = HorizontalDivider()
