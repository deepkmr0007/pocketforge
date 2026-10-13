package dev.pocketforge.ide.ui

import android.app.AlertDialog
import android.app.Dialog
import android.widget.Button
import android.widget.ScrollView
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.consent.Loopback
import java.net.URI

/**
 * The one consent screen used by every network-capable feature. Shows destination, purpose, the
 * exact list of data, risks and (on request) the exact outgoing body. Approve is a single-use
 * "Approve once"; there is no "always allow". Tap-jacking protection is on, and Approve becomes
 * active only after a short delay so it cannot be hit by an accidental double tap.
 */
object ConsentDialog {
    fun show(a: BaseActivity, r: ConsentRequest, answer: (ConsentDecision) -> Unit): Dialog {
        val ui = a.ui; val p = a.p
        var answered = false
        fun reply(d: ConsentDecision) { if (!answered) { answered = true; answer(d) } }

        val uri = runCatching { URI(r.request.url) }.getOrNull()
        val local = Loopback.isLoopbackHost(uri?.host)
        val body = ui.vertical(20) {
            addView(ui.chip(if (local) "STAYS ON THIS PHONE (127.0.0.1)" else if (r.sendsSourceCode) "SOURCE CODE LEAVES THIS PHONE" else "DATA LEAVES THIS PHONE",
                if (local) p.ok else p.warn))
            addView(ui.space(10))
            addView(ui.text(r.feature, 17f, bold = true))
            addView(ui.space(6))
            addView(kv(ui, "To", "${r.provider}\n${uri?.scheme}://${uri?.host}${uri?.port?.takeIf { it > 0 }?.let { ":$it" } ?: ""}${uri?.path ?: ""}"))
            addView(kv(ui, "Why", r.purpose))
            addView(kv(ui, "Size", "${humanBytes(r.totalBytes)} request body" + if (r.redactionsApplied > 0) " · ${r.redactionsApplied} secret(s) masked" else ""))
            val headerNames = r.request.headers.keys.joinToString(", ")
            if (headerNames.isNotEmpty()) addView(kv(ui, "Headers", "$headerNames (values not shown; API keys are sent only in headers)"))
            addView(ui.section("What will be sent"))
            if (r.dataItems.isEmpty()) addView(ui.caption("Only the request body shown under Review data."))
            r.dataItems.take(40).forEach { addView(ui.caption("• ${it.label}  (${humanBytes(it.sizeBytes)})", p.text)) }
            if (r.dataItems.size > 40) addView(ui.caption("…and ${r.dataItems.size - 40} more"))
            addView(ui.section("Risks"))
            r.risks.forEach { addView(ui.caption("• $it", p.text)) }
            addView(ui.space(8))
            addView(ui.caption("This approval covers this one request only. Nothing is sent until you tap Approve once."))
        }
        val sv = ScrollView(a).apply { addView(body) }
        val d = AlertDialog.Builder(a)
            .setTitle("Send this request?")
            .setView(sv)
            .setPositiveButton("Approve once") { _, _ -> reply(ConsentDecision.APPROVE_ONCE) }
            .setNegativeButton("Deny") { _, _ -> reply(ConsentDecision.DENY) }
            .setNeutralButton("Review data", null)
            .setOnCancelListener { reply(ConsentDecision.CANCEL) }
            .setOnDismissListener { reply(ConsentDecision.CANCEL) }
            .create()
        d.setCanceledOnTouchOutside(false)
        d.show()
        val approve: Button = d.getButton(AlertDialog.BUTTON_POSITIVE)
        listOf(approve, d.getButton(AlertDialog.BUTTON_NEGATIVE), d.getButton(AlertDialog.BUTTON_NEUTRAL)).forEach { it?.filterTouchesWhenObscured = true }
        sv.filterTouchesWhenObscured = true
        approve.isEnabled = false
        approve.postDelayed({ approve.isEnabled = true }, 1200)
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { Dialogs.text(a, "Exact request body", payloadText(r)) }
        return d
    }

    private fun kv(ui: Ui, k: String, v: String) = ui.vertical {
        setPadding(0, ui.dp(4), 0, ui.dp(4))
        addView(ui.caption(k))
        addView(ui.body(v).apply { setTextIsSelectable(true) })
    }

    /** Pretty-prints JSON bodies; shows a summary for binary bodies (e.g. a project ZIP, whose files are listed above). */
    fun payloadText(r: ConsentRequest): String {
        val b = r.request.body
        if (b.isEmpty()) return "(empty body)"
        val asText = runCatching { String(b, Charsets.UTF_8) }.getOrNull()
        val printable = asText != null && asText.count { it == '�' || (it.code < 32 && it != '\n' && it != '\r' && it != '\t') } == 0
        return when {
            printable && (asText!!.trimStart().startsWith("{") || asText.trimStart().startsWith("[")) ->
                runCatching { Json.stringify(Json.parse(asText), pretty = true) }.getOrDefault(asText)
            printable -> asText!!
            else -> "Binary body (${humanBytes(b.size.toLong())}), e.g. a ZIP archive. Its file list is shown on the consent screen."
        }.let { if (it.length > 400_000) it.take(400_000) + "\n…[display truncated; the full body is what will be sent]" else it }
    }
}
