package dev.pocketforge.ide.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.LinearLayout
import dev.pocketforge.engine.diff.Diff
import dev.pocketforge.engine.diff.Hunk
import dev.pocketforge.engine.diff.Op

/** Renders diff hunks; optionally with one checkbox per hunk so the user applies only what they accept. */
object DiffView {
    data class Model(val hunks: List<Hunk>, val selected: BooleanArray)

    fun model(oldText: String, newText: String): Model {
        val h = Diff.hunks(Diff.diff(Diff.lines(oldText), Diff.lines(newText)))
        return Model(h, BooleanArray(h.size) { true })
    }

    fun build(ui: Ui, m: Model, selectable: Boolean, maxLines: Int = 4000, onChange: () -> Unit = {}): LinearLayout = ui.vertical {
        if (m.hunks.isEmpty()) { addView(ui.caption("No differences.")); return@vertical }
        var shown = 0
        m.hunks.forEachIndexed { idx, h ->
            val header = "@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@"
            if (selectable) addView(ui.checkbox("Change ${idx + 1} of ${m.hunks.size}   $header", m.selected[idx]) { m.selected[idx] = it; onChange() })
            else addView(ui.caption(header).apply { typeface = ui.mono })
            val sb = SpannableStringBuilder()
            for (l in h.lines) {
                if (shown++ > maxLines) break
                val start = sb.length
                val prefix = when (l.op) { Op.INSERT -> "+ "; Op.DELETE -> "- "; Op.EQUAL -> "  " }
                sb.append(prefix).append(l.text).append('\n')
                when (l.op) {
                    Op.INSERT -> { sb.setSpan(BackgroundColorSpan(ui.p.diffAdd), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(ui.p.ok), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    Op.DELETE -> { sb.setSpan(BackgroundColorSpan(ui.p.diffDel), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(ui.p.danger), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    Op.EQUAL -> sb.setSpan(ForegroundColorSpan(ui.p.muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val tv = ui.text(sb, 12f, monoFont = true).apply {
                setTextIsSelectable(true)
                setHorizontallyScrolling(true)
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                background = ui.rounded(ui.p.surfaceHi, 8)
            }
            addView(ui.hscroll(tv), ui.lp().apply { bottomMargin = ui.dp(10) })
            if (shown > maxLines) { addView(ui.caption("Diff display truncated at $maxLines lines; the full change is still applied as selected.", ui.p.warn)); return@vertical }
        }
    }

    /** Applies only the selected hunks to [oldText]. */
    fun apply(oldText: String, m: Model): String {
        val chosen = m.hunks.filterIndexed { i, _ -> m.selected[i] }
        if (chosen.size == m.hunks.size && chosen.isNotEmpty()) return Diff.apply(oldText, chosen)
        return if (chosen.isEmpty()) oldText else Diff.apply(oldText, chosen)
    }

    fun summary(m: Model): CharSequence {
        val add = m.hunks.sumOf { h -> h.lines.count { it.op == Op.INSERT } }
        val del = m.hunks.sumOf { h -> h.lines.count { it.op == Op.DELETE } }
        val sb = SpannableStringBuilder("${m.hunks.size} change(s): ")
        val s1 = sb.length; sb.append("+$add"); sb.setSpan(StyleSpan(Typeface.BOLD), s1, sb.length, 0)
        sb.append(" / ")
        val s2 = sb.length; sb.append("−$del"); sb.setSpan(StyleSpan(Typeface.BOLD), s2, sb.length, 0)
        return sb
    }
}
