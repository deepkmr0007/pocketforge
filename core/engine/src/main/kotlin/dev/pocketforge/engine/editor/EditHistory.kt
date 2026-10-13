package dev.pocketforge.engine.editor

/** A single replacement: text[start, start+removed.length) became [inserted]. */
data class Edit(val start: Int, val removed: String, val inserted: String, val timeMillis: Long, val cursorBefore: Int, val cursorAfter: Int)

data class TextState(val text: String, val cursor: Int)

/**
 * Undo/redo for the editor. Consecutive single-character typing (or deleting) within
 * [coalesceMillis] at adjacent positions merges into one undo step, like desktop IDEs.
 * Memory is bounded by [maxSteps] and [maxBytes].
 */
class EditHistory(
    private val coalesceMillis: Long = 1200,
    private val maxSteps: Int = 500,
    private val maxBytes: Long = 8L * 1024 * 1024,
) {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var bytes = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Records the change from [old] to [new]. No-op if text is unchanged (cursor moves are not undo steps). */
    fun record(old: String, new: String, cursorBefore: Int, cursorAfter: Int, now: Long) {
        if (old == new) return
        val e = minimalEdit(old, new, now, cursorBefore, cursorAfter)
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && canMerge(last, e)) {
            undo.removeLast(); bytes -= size(last)
            val merged = merge(last, e)
            undo.addLast(merged); bytes += size(merged)
        } else {
            undo.addLast(e); bytes += size(e)
        }
        while (undo.size > maxSteps || (bytes > maxBytes && undo.size > 1)) bytes -= size(undo.removeFirst())
    }

    fun undo(current: String): TextState? {
        val e = undo.removeLastOrNull() ?: return null
        bytes -= size(e)
        check(current.regionMatches(e.start, e.inserted, 0, e.inserted.length)) { "history out of sync" }
        redo.addLast(e)
        val text = current.substring(0, e.start) + e.removed + current.substring(e.start + e.inserted.length)
        return TextState(text, e.cursorBefore.coerceIn(0, text.length))
    }

    fun redo(current: String): TextState? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e); bytes += size(e)
        val text = current.substring(0, e.start) + e.inserted + current.substring(e.start + e.removed.length)
        return TextState(text, e.cursorAfter.coerceIn(0, text.length))
    }

    fun clear() { undo.clear(); redo.clear(); bytes = 0 }

    private fun size(e: Edit) = (e.removed.length + e.inserted.length) * 2L + 48

    private fun canMerge(a: Edit, b: Edit): Boolean {
        if (b.timeMillis - a.timeMillis > coalesceMillis) return false
        val typingA = a.removed.isEmpty() && a.inserted.length >= 1 && !a.inserted.endsWith("\n")
        val typingB = b.removed.isEmpty() && b.inserted.length == 1 && b.inserted != "\n"
        if (typingA && typingB && b.start == a.start + a.inserted.length) {
            // Break the group at word boundaries so undo goes word by word.
            return !(b.inserted[0].isWhitespace() && !a.inserted.last().isWhitespace())
        }
        val delA = a.inserted.isEmpty() && a.removed.length >= 1
        val delB = b.inserted.isEmpty() && b.removed.length == 1
        return delA && delB && (b.start + 1 == a.start || b.start == a.start)
    }

    private fun merge(a: Edit, b: Edit): Edit = if (a.removed.isEmpty()) {
        Edit(a.start, "", a.inserted + b.inserted, b.timeMillis, a.cursorBefore, b.cursorAfter)
    } else if (b.start + 1 == a.start) { // backspace
        Edit(b.start, b.removed + a.removed, "", b.timeMillis, a.cursorBefore, b.cursorAfter)
    } else { // forward delete
        Edit(a.start, a.removed + b.removed, "", b.timeMillis, a.cursorBefore, b.cursorAfter)
    }

    companion object {
        fun minimalEdit(old: String, new: String, now: Long, cursorBefore: Int, cursorAfter: Int): Edit {
            var p = 0
            val maxP = minOf(old.length, new.length)
            while (p < maxP && old[p] == new[p]) p++
            var s = 0
            while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
            return Edit(p, old.substring(p, old.length - s), new.substring(p, new.length - s), now, cursorBefore, cursorAfter)
        }
    }
}

object Indenter {
    /** Inserts a newline at [cursor], carrying indentation and adding a level after an opener. */
    fun newline(text: String, cursor: Int, unit: String, language: Language): TextState {
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val line = text.substring(lineStart, cursor)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.trimEnd()
        val opens = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[") ||
            (language == Language.PYTHON && trimmed.endsWith(":")) ||
            (language in setOf(Language.XML, Language.HTML) && trimmed.endsWith(">") && !trimmed.endsWith("/>") && !trimmed.contains("</") && trimmed.trimStart().startsWith("<") && !trimmed.trimStart().startsWith("<?") && !trimmed.trimStart().startsWith("<!"))
        val next = text.getOrNull(cursor)
        val closesNext = next == '}' || next == ')' || next == ']'
        return if (opens && closesNext) {
            val ins = "\n" + indent + unit + "\n" + indent
            TextState(text.substring(0, cursor) + ins + text.substring(cursor), cursor + 1 + indent.length + unit.length)
        } else {
            val ins = "\n" + indent + (if (opens) unit else "")
            TextState(text.substring(0, cursor) + ins + text.substring(cursor), cursor + ins.length)
        }
    }

    /** Indents (or outdents) every line touched by [selStart, selEnd]. */
    fun shiftLines(text: String, selStart: Int, selEnd: Int, unit: String, outdent: Boolean): Triple<String, Int, Int> {
        val a = text.lastIndexOf('\n', selStart - 1) + 1
        val endIdx = text.indexOf('\n', maxOf(selEnd - 1, selStart)).let { if (it < 0) text.length else it }
        val block = text.substring(a, endIdx)
        val lines = block.split('\n')
        val out = lines.map { l ->
            if (!outdent) unit + l
            else when {
                l.startsWith(unit) -> l.removePrefix(unit)
                l.startsWith("\t") -> l.substring(1)
                else -> l.trimStart(' ').let { t -> l.substring(minOf(l.length - t.length, unit.length)) }
            }
        }.joinToString("\n")
        val newText = text.substring(0, a) + out + text.substring(endIdx)
        return Triple(newText, a, a + out.length)
    }
}
