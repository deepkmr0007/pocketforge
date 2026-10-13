package dev.pocketforge.ide.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import dev.pocketforge.engine.editor.BracketMatcher
import dev.pocketforge.engine.editor.Highlighter
import dev.pocketforge.engine.editor.Language
import dev.pocketforge.engine.editor.Span
import dev.pocketforge.ide.ui.Palette
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Code editor on top of the framework EditText:
 *  - line-number gutter (logical lines, also with word wrap) with error markers,
 *  - syntax highlighting computed off the UI thread for the whole file (engine lexer, line-cached)
 *    but APPLIED only around the visible region, so big files stay responsive,
 *  - bracket-pair highlight, current-line highlight,
 *  - auto-indent on Enter, Tab/Shift+Tab, Ctrl shortcuts for hardware keyboards,
 *  - every user edit is reported as a minimal (start, removed, inserted) change for undo history.
 */
@SuppressLint("ViewConstructor")
class CodeEditText(ctx: Context, private val p: Palette) : EditText(ctx) {
    enum class Shortcut { SAVE, UNDO, REDO, FIND, GOTO, SAVE_ALL }

    interface Callbacks {
        fun onUserEdit(start: Int, removed: String, inserted: String, cursorBefore: Int, cursorAfter: Int)
        fun onCursor(line: Int, col: Int)
        fun onShortcut(s: Shortcut): Boolean
    }

    var callbacks: Callbacks? = null
    var showLineNumbers = true
        set(v) { field = v; updateGutter(force = true) }
    var indentUnit = "    "
    var highlighter: Highlighter? = null
        set(v) { field = v; allSpans = emptyList(); spansVersion = -1; clearSpans(HlSpan::class.java); scheduleHighlight(0) }
    var language: Language = Language.PLAIN
    var errorLines: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    /** True while the app itself changes the text (undo, redo, loading). Such changes are not recorded. */
    var applying = false

    private val main = Handler(Looper.getMainLooper())
    private val lexer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pf-highlight").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var textVersion = 0
    private var spansVersion = -1
    private var allSpans: List<Span> = emptyList()
    private var snapshot: String = ""
    private var appliedFrom = -1
    private var appliedTo = -1
    private var lineStarts: IntArray? = null

    private val gutterText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = p.gutterText; textAlign = Paint.Align.RIGHT }
    private val gutterBg = Paint().apply { color = p.gutterBg }
    private val gutterEdge = Paint().apply { color = p.border }
    private val errDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.danger }
    private val currentLine = Paint().apply { color = if (p.dark) 0x14FFFFFF else 0x0A000000 }
    private var gutterWidth = 0
    private var gutterDigits = 0

    private var pendingStart = 0
    private var pendingRemoved = ""
    private var cursorBefore = 0
    private var pendingIndentAt = -1
    private var ready = false

    private class HlSpan(color: Int) : ForegroundColorSpan(color)
    private class BracketSpan(color: Int) : BackgroundColorSpan(color)


    fun configure(sizeSp: Int, wrap: Boolean, noSuggestions: Boolean) {
        lastConfig = Triple(sizeSp, wrap, noSuggestions)
        val base = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // VISIBLE_PASSWORD stops most keyboards from auto-correcting, auto-capitalising and composing words — essential for code.
        inputType = if (noSuggestions) base or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else base
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        setHorizontallyScrolling(!wrap)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        gutterText.textSize = textSize * 0.82f
        updateGutter(force = true)
    }

    private var lastConfig = Triple(14, false, true)

    /** Read-only mode keeps selection/copy but removes the key listener and keyboard. */
    fun setReadOnly(ro: Boolean) {
        if (ro) { keyListener = null; showSoftInputOnFocus = false }
        else { configure(lastConfig.first, lastConfig.second, lastConfig.third); showSoftInputOnFocus = true }
    }

    /** Replaces the whole text without recording history (opening a tab, reload). */
    fun load(text: CharSequence, selStart: Int, selEnd: Int, scrollY: Int) {
        applying = true
        try {
            setText(text)
            val n = this.text.length
            setSelection(selStart.coerceIn(0, n), selEnd.coerceIn(0, n))
        } finally { applying = false }
        post { scrollTo(0, scrollY) }
    }

    /** Applies an edit (undo/redo/insert from toolbar) as one programmatic change. */
    fun replaceRange(start: Int, end: Int, with: CharSequence, record: Boolean) {
        val e = text ?: return
        val s = start.coerceIn(0, e.length); val en = end.coerceIn(s, e.length)
        if (!record) applying = true
        try { e.replace(s, en, with) } finally { applying = false }
    }

    // ---------------- editing ----------------

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (!ready || applying) return
            pendingStart = start
            pendingRemoved = if (count > 0) s.subSequence(start, start + count).toString() else ""
            cursorBefore = selectionStart
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (!ready) return
            textVersion++
            lineStarts = null
            if (applying) return
            val inserted = if (count > 0) s.subSequence(start, start + count).toString() else ""
            callbacks?.onUserEdit(start, pendingRemoved, inserted, cursorBefore, start + count)
            if (inserted == "\n" && before == 0) pendingIndentAt = start + 1
        }

        override fun afterTextChanged(e: Editable) {
            if (!ready) return
            if (pendingIndentAt >= 0) {
                val at = pendingIndentAt
                pendingIndentAt = -1
                autoIndent(e, at)
            }
            removeBracketSpans()
            updateGutter()
            scheduleHighlight(120)
        }
    }

    private fun autoIndent(e: Editable, at: Int) {
        if (at > e.length) return
        val prevEnd = at - 1
        val prevStart = lastIndexOf(e, '\n', prevEnd - 1) + 1
        val prevLine = e.subSequence(prevStart, prevEnd).toString()
        val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val trimmed = prevLine.trimEnd()
        val opens = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[") || (language == Language.PYTHON && trimmed.endsWith(":"))
        val next = if (at < e.length) e[at] else ' '
        val closes = next == '}' || next == ')' || next == ']'
        val insert = when {
            opens && closes -> indent + indentUnit + "\n" + indent
            opens -> indent + indentUnit
            else -> indent
        }
        if (insert.isEmpty()) return
        e.insert(at, insert) // recorded as its own (small) edit
        val caret = at + (if (opens && closes) indent.length + indentUnit.length else insert.length)
        setSelection(caret.coerceAtMost(e.length))
    }

    private fun lastIndexOf(cs: CharSequence, ch: Char, from: Int): Int {
        var i = from.coerceAtMost(cs.length - 1)
        while (i >= 0) { if (cs[i] == ch) return i; i-- }
        return -1
    }

    fun insertAtCursor(s: String) {
        val e = text ?: return
        val a = selectionStart.coerceAtLeast(0); val b = selectionEnd.coerceAtLeast(0)
        e.replace(minOf(a, b), maxOf(a, b), s)
    }

    /** Indent / outdent the selected lines (or the current line). One undo step per line touched. */
    fun shiftLines(outdent: Boolean) {
        val e = text ?: return
        val a = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val first = lastIndexOf(e, '\n', a - 1) + 1
        val starts = ArrayList<Int>()
        var s = first
        while (true) {
            starts += s
            val nl = indexOf(e, '\n', s)
            if (nl < 0) break
            val next = nl + 1
            if (next > b || (next == b && b > a)) break
            s = next
        }
        var delta = 0
        for (ls in starts.asReversed()) {
            if (!outdent) { e.insert(ls, indentUnit); delta += indentUnit.length }
            else {
                var k = 0
                if (ls < e.length && e[ls] == '\t') k = 1
                else while (k < indentUnit.length && ls + k < e.length && e[ls + k] == ' ') k++
                if (k > 0) { e.delete(ls, ls + k); delta -= k }
            }
        }
        if (a == b) setSelection((a + delta).coerceIn(first, e.length))
        else {
            val endLine = indexOf(e, '\n', starts.last() + 0).let { if (it < 0) e.length else it }
            setSelection(first.coerceIn(0, e.length), endLine.coerceIn(0, e.length))
        }
    }

    private fun indexOf(cs: CharSequence, ch: Char, from: Int): Int {
        for (k in from until cs.length) if (cs[k] == ch) return k
        return -1
    }

    // ---------------- shortcuts ----------------

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            val s = when (keyCode) {
                KeyEvent.KEYCODE_S -> if (event.isShiftPressed) Shortcut.SAVE_ALL else Shortcut.SAVE
                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) Shortcut.REDO else Shortcut.UNDO
                KeyEvent.KEYCODE_Y -> Shortcut.REDO
                KeyEvent.KEYCODE_F -> Shortcut.FIND
                KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_L -> Shortcut.GOTO
                else -> null
            }
            if (s != null && callbacks?.onShortcut(s) == true) return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && keyListener != null) {
            if (event.isShiftPressed) shiftLines(true)
            else if (selectionStart != selectionEnd) shiftLines(false)
            else insertAtCursor(indentUnit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || callbacks == null) return
        val (line, col) = lineCol(selStart)
        callbacks?.onCursor(line, col)
        highlightBracket(selStart)
        invalidate()
    }

    // ---------------- line numbers ----------------

    private fun starts(): IntArray {
        lineStarts?.let { return it }
        val t = text ?: return intArrayOf(0)
        val list = IntArray(countLines(t))
        var n = 0
        list[n++] = 0
        for (i in 0 until t.length) if (t[i] == '\n') list[n++] = i + 1
        lineStarts = list
        return list
    }

    private fun countLines(t: CharSequence): Int { var n = 1; for (i in 0 until t.length) if (t[i] == '\n') n++; return n }

    /** 1-based line and column of an offset. */
    fun lineCol(offset: Int): Pair<Int, Int> {
        val s = starts()
        var lo = 0; var hi = s.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (s[mid] <= offset) lo = mid else hi = mid - 1 }
        return (lo + 1) to (offset - s[lo] + 1)
    }

    fun lineCount(): Int = starts().size

    fun offsetOfLine(line: Int): Int { val s = starts(); return s[(line - 1).coerceIn(0, s.size - 1)] }

    fun goToLine(line: Int, col: Int = 1) {
        val off = (offsetOfLine(line) + col - 1).coerceIn(0, text.length)
        requestFocus()
        setSelection(off)
        post { bringPointIntoView(off) }
    }

    private fun updateGutter(force: Boolean = false) {
        if (!showLineNumbers) {
            if (force || gutterWidth != 0) { gutterWidth = 0; setPadding(dp(10), dp(6), dp(10), dp(6)) }
            return
        }
        val digits = maxOf(3, lineCount().toString().length)
        if (!force && digits == gutterDigits) return
        gutterDigits = digits
        gutterWidth = (gutterText.measureText("9".repeat(digits)) + dp(18)).toInt()
        setPadding(gutterWidth + dp(8), dp(6), dp(10), dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDraw(canvas: Canvas) {
        if (!ready) { super.onDraw(canvas); return }
        val l = layout
        if (l != null && hasFocus() && selectionStart == selectionEnd && selectionStart >= 0) {
            val line = l.getLineForOffset(selectionStart)
            val top = l.getLineTop(line) + totalPaddingTop
            val bottom = l.getLineBottom(line) + totalPaddingTop
            canvas.drawRect(scrollX.toFloat(), top.toFloat(), (scrollX + width).toFloat(), bottom.toFloat(), currentLine)
        }
        super.onDraw(canvas)
        if (!showLineNumbers || l == null) return
        val left = scrollX.toFloat()
        val top = scrollY.toFloat()
        canvas.drawRect(left, top, left + gutterWidth, top + height, gutterBg)
        canvas.drawRect(left + gutterWidth - dp(1), top, left + gutterWidth, top + height, gutterEdge)
        val padTop = totalPaddingTop
        val first = l.getLineForVertical((scrollY - padTop).coerceAtLeast(0))
        val last = l.getLineForVertical(scrollY + height - padTop)
        val t = text
        var logical = lineCol(l.getLineStart(first)).first
        for (i in first..last) {
            val ls = l.getLineStart(i)
            val isNew = ls == 0 || (ls - 1 < t.length && t[ls - 1] == '\n')
            if (i > first && isNew) logical++
            if (!isNew) continue
            val y = (l.getLineBaseline(i) + padTop).toFloat()
            canvas.drawText(logical.toString(), left + gutterWidth - dp(8), y, gutterText)
            if (logical in errorLines) canvas.drawCircle(left + dp(6), y - gutterText.textSize / 3, dp(3).toFloat(), errDot)
        }
    }

    // ---------------- highlighting ----------------

    private val highlightRunnable = Runnable { computeHighlight() }

    fun scheduleHighlight(delayMs: Long) {
        main.removeCallbacks(highlightRunnable)
        if (highlighter == null) return
        main.postDelayed(highlightRunnable, delayMs)
    }

    private fun computeHighlight() {
        val h = highlighter ?: return
        val version = textVersion
        val snap = text?.toString() ?: return
        lexer.execute {
            val spans = try { h.highlight(snap) } catch (t: Throwable) { emptyList() }
            main.post {
                if (version != textVersion || highlighter !== h) return@post
                allSpans = spans
                snapshot = snap
                spansVersion = version
                appliedFrom = -1; appliedTo = -1
                applyVisible()
                highlightBracket(selectionStart)
            }
        }
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (!ready) return
        if (spansVersion == textVersion && allSpans.isNotEmpty()) {
            val (from, to) = visibleRange(20)
            if (from < appliedFrom || to > appliedTo) applyVisible()
        }
    }

    private fun visibleRange(marginLines: Int): Pair<Int, Int> {
        val l = layout ?: return 0 to 0
        val padTop = totalPaddingTop
        val first = (l.getLineForVertical((scrollY - padTop).coerceAtLeast(0)) - marginLines).coerceAtLeast(0)
        val last = (l.getLineForVertical(scrollY + height - padTop) + marginLines).coerceAtMost(l.lineCount - 1)
        return l.getLineStart(first) to l.getLineEnd(last)
    }

    private fun applyVisible() {
        val e = text ?: return
        val (from, to) = visibleRange(150)
        e.getSpans(from, to, HlSpan::class.java).forEach { e.removeSpan(it) }
        var lo = 0; var hi = allSpans.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (allSpans[mid].end <= from) lo = mid + 1 else hi = mid }
        var i = lo
        val n = e.length
        while (i < allSpans.size) {
            val s = allSpans[i]
            if (s.start >= to) break
            val a = s.start.coerceIn(0, n); val b = s.end.coerceIn(0, n)
            if (b > a) e.setSpan(HlSpan(p.token(s.type)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i++
        }
        appliedFrom = from; appliedTo = to
    }

    private fun <T> clearSpans(cls: Class<T>) { val e = text ?: return; e.getSpans(0, e.length, cls).forEach { e.removeSpan(it) } }

    private fun removeBracketSpans() = clearSpans(BracketSpan::class.java)

    private fun highlightBracket(offset: Int) {
        removeBracketSpans()
        if (spansVersion != textVersion || snapshot.length > 300_000 || offset < 0) return
        val m = BracketMatcher.match(snapshot, offset, allSpans) ?: return
        val e = text ?: return
        if (m.second + 1 > e.length) return
        e.setSpan(BracketSpan(p.bracketBg), m.first, m.first + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e.setSpan(BracketSpan(p.bracketBg), m.second, m.second + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun release() {
        main.removeCallbacks(highlightRunnable)
        lexer.shutdownNow()
    }

    /** Declared last: EditText's constructor calls overridden methods before Kotlin field initialisers run. */
    init {
        typeface = Typeface.MONOSPACE
        setTextColor(p.text)
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.TOP or Gravity.START
        highlightColor = p.selection
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        setLineSpacing(0f, 1.12f)
        contentDescription = "Code editor"
        addTextChangedListener(watcher)
        configure(14, wrap = false, noSuggestions = true)
        ready = true
    }
}
