package dev.pocketforge.ide.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object Dialogs {
    fun info(a: BaseActivity, title: String, message: CharSequence, ok: String = "OK", onOk: () -> Unit = {}) {
        AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton(ok) { _, _ -> onOk() }.show()
    }

    fun confirm(a: BaseActivity, title: String, message: CharSequence, confirmLabel: String, destructive: Boolean = false, onConfirm: () -> Unit) {
        val d = AlertDialog.Builder(a).setTitle(title).setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
        if (destructive) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(a.p.danger)
    }

    /** For irreversible actions: the user must type [word] to enable the button. */
    fun typedConfirm(a: BaseActivity, title: String, message: String, word: String, confirmLabel: String, onConfirm: () -> Unit) {
        val ui = a.ui
        val input = ui.input("Type $word to confirm")
        val body = ui.vertical(20) {
            addView(ui.body(message))
            addView(ui.space(12))
            addView(input, ui.lp())
        }
        val d = AlertDialog.Builder(a).setTitle(title).setView(body)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
        val btn = d.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.isEnabled = false
        btn.setTextColor(a.p.danger)
        input.addTextChangedListener(simpleWatcher { btn.isEnabled = it.trim() == word })
    }

    /** Text input with inline validation; [validate] returns an error message or null. */
    fun input(
        a: BaseActivity, title: String, hint: String, initial: String = "", okLabel: String = "OK",
        help: String? = null, validate: (String) -> String? = { null }, onOk: (String) -> Unit,
    ) {
        val ui = a.ui
        val field = ui.input(hint, initial)
        val err = ui.caption("", a.p.danger)
        val body = ui.vertical(20) {
            if (help != null) addView(ui.caption(help).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(field, ui.lp())
            addView(err)
        }
        val d = AlertDialog.Builder(a).setTitle(title).setView(body).setPositiveButton(okLabel, null).setNegativeButton("Cancel", null).show()
        field.setSelection(field.text.length)
        field.requestFocus()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = field.text.toString()
            val e = validate(v)
            if (e != null) err.text = e else { d.dismiss(); onOk(v) }
        }
    }

    fun choose(a: BaseActivity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }.setNegativeButton("Cancel", null).show()
    }

    fun multiChoose(a: BaseActivity, title: String, items: List<String>, checked: BooleanArray, onDone: (BooleanArray) -> Unit) {
        AlertDialog.Builder(a).setTitle(title)
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Done") { _, _ -> onDone(checked) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Shows long text (logs, payloads, scripts) in a selectable monospace view with Copy. */
    fun text(a: BaseActivity, title: String, content: CharSequence, extra: Pair<String, () -> Unit>? = null) {
        val ui = a.ui
        val tv = ui.text(content, 12f, monoFont = true).apply { setTextIsSelectable(true); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        val sv = ScrollView(a).apply { addView(tv) }
        val b = AlertDialog.Builder(a).setTitle(title).setView(sv).setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> copy(a, title, content.toString()) }
        if (extra != null) b.setNegativeButton(extra.first) { _, _ -> extra.second() }
        b.show()
    }

    fun custom(a: BaseActivity, title: String, view: View, positive: String = "Close", onPositive: () -> Unit = {}): AlertDialog =
        AlertDialog.Builder(a).setTitle(title).setView(view).setPositiveButton(positive) { _, _ -> onPositive() }.show()

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}

fun simpleWatcher(after: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) { after(s?.toString() ?: "") }
}

fun EditText.value(): String = text?.toString() ?: ""
fun TextView.setTextOrHide(s: CharSequence?) { if (s.isNullOrEmpty()) visibility = View.GONE else { text = s; visibility = View.VISIBLE } }
