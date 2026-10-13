package dev.pocketforge.ide.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Small, safe Markdown renderer for help pages and AI answers: headings, bold, inline code,
 * fenced code, bullets. No HTML, no links are made clickable, no images are loaded (offline, no tracking).
 */
object Markdown {
    fun render(md: String, p: Palette): CharSequence {
        val out = SpannableStringBuilder()
        var inFence = false
        for (raw in md.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                if (!inFence) out.append('\n')
                continue
            }
            val start = out.length
            when {
                inFence -> {
                    out.append(line).append('\n')
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(p.surfaceHi), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(p.token(dev.pocketforge.engine.editor.TokenType.CODE)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(4)
                    inline(out, line.drop(level).trim(), p)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(when (level) { 1 -> 1.45f; 2 -> 1.25f; 3 -> 1.1f; else -> 1f }), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append("\n")
                }
                Regex("^\\s*([-*+]|\\d+\\.)\\s+").containsMatchIn(line) -> {
                    val m = Regex("^\\s*([-*+]|\\d+\\.)\\s+").find(line)!!
                    val numbered = m.groupValues[1].first().isDigit()
                    if (numbered) out.append(m.groupValues[1]).append(' ')
                    inline(out, line.substring(m.range.last + 1), p)
                    out.append('\n')
                    if (!numbered) out.setSpan(BulletSpan(24, p.accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> { inline(out, line, p); out.append('\n') }
            }
        }
        return out
    }

    private fun inline(out: SpannableStringBuilder, s: String, p: Palette) {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> {
                    val e = s.indexOf("**", i + 2); val st = out.length
                    out.append(s, i + 2, e); out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = e + 2
                }
                s[i] == '`' && s.indexOf('`', i + 1) > i -> {
                    val e = s.indexOf('`', i + 1); val st = out.length
                    out.append(s, i + 1, e)
                    out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(p.surfaceHi), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = e + 1
                }
                else -> { out.append(s[i]); i++ }
            }
        }
    }
}
