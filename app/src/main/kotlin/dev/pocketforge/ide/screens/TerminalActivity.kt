package dev.pocketforge.ide.screens

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketforge.engine.terminal.CancelSignal
import dev.pocketforge.engine.terminal.CommandAnalysis
import dev.pocketforge.engine.terminal.CommandAnalyzer
import dev.pocketforge.engine.terminal.ShellRequest
import dev.pocketforge.engine.terminal.ShellRunner
import dev.pocketforge.engine.terminal.TerminalPolicy
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.value
import java.io.File

/**
 * Terminal backed by Android's real /system/bin/sh (toybox), working directory = the project.
 * Shows the exact command, folder, exit code and duration. Commands that are not plainly read-only,
 * or that touch paths outside the project, need confirmation; network commands are blocked by default.
 * Nothing runs because an AI suggested it — suggestions can only be pasted here for you to run.
 */
class TerminalActivity : BaseActivity() {
    private lateinit var pid: String
    private lateinit var root: File
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var runBtn: TextView
    private val transcript = SpannableStringBuilder()
    private var running: CancelSignal? = null
    private val history = ArrayList<String>()
    private var historyPos = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        root = c.projects.dirOf(pid)
        val shellOk = ShellRunner.isAvailable()
        output = ui.text("", 13f, monoFont = true).apply { setTextIsSelectable(true); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        scroll = ScrollView(this).apply { addView(output); setBackgroundColor(p.gutterBg) }
        input = ui.input("command, e.g. ls -la", monoFont = true).apply {
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, ev -> if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false }
        }
        intent.getStringExtra(Nav.COMMAND)?.let { input.setText(it) }
        runBtn = ui.button("Run", Btn.PRIMARY, enabled = shellOk) { if (running != null) running?.cancel() else submit() }
        val bar = ui.topBar("Terminal", "sh · ${root.path}", { finish() }, listOf(
            ui.iconButton("⧉", "Copy transcript") { Dialogs.copy(this, "terminal", transcript.toString()) },
            ui.iconButton("⌫", "Clear") { transcript.clear(); output.text = "" },
        ))
        setScreen(ui.vertical {
            addView(bar, ui.lp())
            addView(ui.divider())
            addView(scroll, ui.lp(h = 0, weight = 1f))
            addView(ui.horizontal(8) {
                setBackgroundColor(p.surface)
                addView(ui.iconButton("↑", "Previous command") { recall(-1) })
                addView(ui.iconButton("↓", "Next command") { recall(1) })
                addView(input, ui.lp(0, weight = 1f))
                addView(ui.space(0), ui.lp(ui.dp(8), 1))
                addView(runBtn)
            }, ui.lp())
        })
        val s = c.settings.value
        append(if (shellOk) "Shell: /system/bin/sh · folder: ${root.path}\nClean environment (HOME = project). Timeout ${s.terminalTimeoutSec}s, output limit ${s.terminalOutputKb} KB. Network commands: ${if (s.terminalAllowNetwork) "allowed with confirmation" else "blocked"}.\nThis is Android's toybox shell, not a Linux distribution: there is no gcc, java or python here.\n\n"
        else "No usable shell was found on this device, so the terminal is disabled.\n", p.muted)
    }

    private fun append(s: CharSequence, color: Int? = null) {
        val start = transcript.length
        transcript.append(s)
        if (color != null) transcript.setSpan(ForegroundColorSpan(color), start, transcript.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (transcript.length > 400_000) transcript.delete(0, transcript.length - 300_000)
        output.text = transcript
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun recall(dir: Int) {
        if (history.isEmpty()) return
        historyPos = (historyPos + dir).coerceIn(0, history.size)
        input.setText(history.getOrNull(historyPos) ?: "")
        input.setSelection(input.text.length)
    }

    private fun submit() {
        val cmd = input.value().trim()
        if (cmd.isEmpty() || running != null) return
        val s = c.settings.value
        val a = CommandAnalyzer.analyze(cmd, root, TerminalPolicy(allowNetworkCommands = s.terminalAllowNetwork, projectTrusted = pid in s.trustedProjects))
        if (a.blockedReason != null) { append("\$ $cmd\n", p.accent); append("Blocked: ${a.blockedReason}\n\n", p.danger); return }
        if (!a.needsConfirmation) { run(a); return }
        confirm(a)
    }

    private fun confirm(a: CommandAnalysis) {
        val body = ui.vertical(20) {
            addView(ui.caption("Command"))
            addView(ui.text(a.command, 14f, monoFont = true).apply { setTextIsSelectable(true) })
            addView(ui.space(8))
            addView(ui.caption("Working folder"))
            addView(ui.text(root.path, 13f, monoFont = true))
            addView(ui.space(8))
            addView(ui.caption("Classified as: ${a.risks.joinToString { it.label }}"))
            a.reasons.forEach { addView(ui.caption("• $it", p.text)) }
            addView(ui.space(8))
            addView(ui.caption("Android's app sandbox limits what this shell can reach, but it can still change or delete anything inside PocketForge's own storage."))
        }
        val d = android.app.AlertDialog.Builder(this).setTitle("Run this command?").setView(ui.scroll(body))
            .setPositiveButton("Run once") { _, _ -> run(a) }.setNegativeButton("Cancel", null).show()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
    }

    private fun run(a: CommandAnalysis) {
        val s = c.settings.value
        history.remove(a.command); history.add(a.command); historyPos = history.size
        input.setText("")
        append("\$ ${a.command}\n", p.accent)
        val signal = CancelSignal()
        running = signal
        runBtn.text = "Stop"
        val req = ShellRequest(a.command, root, timeoutMillis = s.terminalTimeoutSec * 1000L, maxOutputBytes = s.terminalOutputKb * 1024, tmpDir = File(cacheDir, "term-tmp"))
        bg({ ShellRunner.run(req, signal) { chunk -> runOnUiThread { append(chunk) } } }) { r ->
            running = null
            runBtn.text = "Run"
            r.onSuccess { res ->
                c.log.info("terminal", ShellRunner.logLine(res))
                if (res.output.isNotEmpty() && !res.output.endsWith("\n")) append("\n")
                if (res.truncated) append("[output truncated at ${s.terminalOutputKb} KB]\n", p.warn)
                append("[${res.statusLabel} · ${res.durationMillis} ms]\n\n", if (res.succeeded) p.ok else p.danger)
                c.editor(pid).tabs.forEach { c.editor(pid).checkExternal(it) }
            }.onFailure { e -> append("Could not run: ${e.message}\n\n", p.danger) }
        }
    }

    override fun onDestroy() {
        running?.cancel()
        super.onDestroy()
    }
}
