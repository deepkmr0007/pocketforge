package dev.pocketforge.ide.editor

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.HorizontalScrollView
import dev.pocketforge.ide.ui.Ui

/** Row of coding keys above the soft keyboard: indentation, brackets, quotes, operators and cursor keys. */
object KeyboardBar {
    private val keys = listOf(
        "⇥", "⇤", "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "`", ";", ":", "=", "+", "-", "*", "/", "\\",
        "|", "&", "!", "?", "_", "$", "#", "@", "%", ",", ".", "←", "→", "↑", "↓",
    )
    private val names = mapOf(
        "⇥" to "Indent", "⇤" to "Outdent", "←" to "Cursor left", "→" to "Cursor right", "↑" to "Cursor up", "↓" to "Cursor down",
        "\"" to "Double quote", "'" to "Single quote", "`" to "Backtick", "\\" to "Backslash",
    )

    fun build(ui: Ui, editor: () -> CodeEditText?): HorizontalScrollView {
        val row = ui.horizontal {
            setBackgroundColor(ui.p.surface)
            setPadding(ui.dp(2), 0, ui.dp(2), 0)
        }
        for (k in keys) {
            val b = ui.iconButton(k, names[k] ?: k) {
                val ed = editor() ?: return@iconButton
                when (k) {
                    "⇥" -> if (ed.selectionStart != ed.selectionEnd) ed.shiftLines(false) else ed.insertAtCursor(ed.indentUnit)
                    "⇤" -> ed.shiftLines(true)
                    "←" -> key(ed, KeyEvent.KEYCODE_DPAD_LEFT)
                    "→" -> key(ed, KeyEvent.KEYCODE_DPAD_RIGHT)
                    "↑" -> key(ed, KeyEvent.KEYCODE_DPAD_UP)
                    "↓" -> key(ed, KeyEvent.KEYCODE_DPAD_DOWN)
                    else -> ed.insertAtCursor(k)
                }
            }
            b.minWidth = ui.dp(44)
            b.typeface = ui.mono
            row.addView(b)
        }
        return ui.hscroll(row).apply { setBackgroundColor(ui.p.surface) }
    }

    private fun key(ed: CodeEditText, code: Int) {
        val t = SystemClock.uptimeMillis()
        ed.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        ed.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
