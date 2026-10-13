package dev.pocketforge.engine.diff

import dev.pocketforge.engine.common.PfException

enum class Op { EQUAL, INSERT, DELETE }
data class DiffLine(val op: Op, val text: String, val oldLine: Int?, val newLine: Int?)
data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<DiffLine>)

/**
 * Line diff using Myers' O(ND) algorithm, unified-diff rendering, and strict patch application.
 * Used for snapshot compare, external-change compare and AI change review.
 */
object Diff {
    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    fun diff(a: List<String>, b: List<String>, maxD: Int = 20_000): List<DiffLine> {
        // Trim common prefix/suffix for speed.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        val mid = myers(am, bm, maxD)
        val out = ArrayList<DiffLine>()
        for (i in 0 until pre) out += DiffLine(Op.EQUAL, a[i], i + 1, i + 1)
        var oi = pre; var ni = pre
        for ((op, t) in mid) when (op) {
            Op.EQUAL -> { out += DiffLine(op, t, ++oi, ++ni) }
            Op.DELETE -> { out += DiffLine(op, t, ++oi, null) }
            Op.INSERT -> { out += DiffLine(op, t, null, ++ni) }
        }
        for (k in 0 until suf) { out += DiffLine(Op.EQUAL, a[a.size - suf + k], ++oi, ++ni) }
        return out
    }

    private fun myers(a: List<String>, b: List<String>, maxD: Int): List<Pair<Op, String>> {
        val n = a.size; val m = b.size
        if (n == 0) return b.map { Op.INSERT to it }
        if (m == 0) return a.map { Op.DELETE to it }
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        loop@ for (d in 0..minOf(max, maxD)) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break@loop }
                k += 2
            }
        }
        if (!found) { // fall back to delete-all/insert-all for pathological inputs
            return a.map { Op.DELETE to it } + b.map { Op.INSERT to it }
        }
        // Backtrack
        val result = ArrayList<Pair<Op, String>>()
        var x = n; var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { result += Op.EQUAL to a[x - 1]; x--; y-- }
            if (d > 0) {
                if (x == prevX) { result += Op.INSERT to b[y - 1]; y-- } else { result += Op.DELETE to a[x - 1]; x-- }
            }
        }
        while (x > 0 && y > 0) { result += Op.EQUAL to a[x - 1]; x--; y-- }
        return result.asReversed()
    }

    fun hunks(lines: List<DiffLine>, context: Int = 3): List<Hunk> {
        val changeIdx = lines.indices.filter { lines[it].op != Op.EQUAL }
        if (changeIdx.isEmpty()) return emptyList()
        val groups = ArrayList<IntRange>()
        var start = maxOf(0, changeIdx.first() - context)
        var end = minOf(lines.size - 1, changeIdx.first() + context)
        for (i in changeIdx.drop(1)) {
            if (i - context <= end + 1) end = minOf(lines.size - 1, i + context)
            else { groups += start..end; start = maxOf(0, i - context); end = minOf(lines.size - 1, i + context) }
        }
        groups += start..end
        return groups.map { r ->
            val sl = lines.subList(r.first, r.last + 1)
            val oldStart = sl.firstNotNullOfOrNull { it.oldLine } ?: (lines.subList(0, r.first).lastOrNull { it.oldLine != null }?.oldLine ?: 0)
            val newStart = sl.firstNotNullOfOrNull { it.newLine } ?: (lines.subList(0, r.first).lastOrNull { it.newLine != null }?.newLine ?: 0)
            Hunk(oldStart, sl.count { it.op != Op.INSERT }, newStart, sl.count { it.op != Op.DELETE }, sl)
        }
    }

    fun unified(oldText: String, newText: String, oldName: String, newName: String = oldName, context: Int = 3): String {
        val h = hunks(diff(lines(oldText), lines(newText)), context)
        if (h.isEmpty()) return ""
        return buildString {
            append("--- a/").append(oldName).append('\n')
            append("+++ b/").append(newName).append('\n')
            for (hk in h) {
                append("@@ -").append(hk.oldStart).append(',').append(hk.oldCount)
                append(" +").append(hk.newStart).append(',').append(hk.newCount).append(" @@\n")
                for (l in hk.lines) append(when (l.op) { Op.EQUAL -> ' '; Op.INSERT -> '+'; Op.DELETE -> '-' }).append(l.text).append('\n')
            }
        }
    }

    data class FilePatch(val oldPath: String, val newPath: String, val hunks: List<Hunk>)

    /** Parses a (possibly multi-file) unified diff. Paths are returned raw — callers MUST pass them through PathGuard. */
    fun parseUnified(patch: String): List<FilePatch> {
        val lines = patch.replace("\r\n", "\n").split('\n')
        val out = ArrayList<FilePatch>()
        var i = 0
        val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
        while (i < lines.size) {
            if (lines[i].startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ")) {
                val oldP = lines[i].removePrefix("--- ").substringBefore('\t').trim().removePrefix("a/")
                val newP = lines[i + 1].removePrefix("+++ ").substringBefore('\t').trim().removePrefix("b/")
                i += 2
                val hunks = ArrayList<Hunk>()
                while (i < lines.size && lines[i].startsWith("@@")) {
                    val m = header.matchEntire(lines[i]) ?: throw PfException("patch.bad_header", "Malformed hunk header: ${lines[i]}")
                    val os = m.groupValues[1].toInt(); val oc = m.groupValues[2].ifEmpty { "1" }.toInt()
                    val ns = m.groupValues[3].toInt(); val nc = m.groupValues[4].ifEmpty { "1" }.toInt()
                    i++
                    val hl = ArrayList<DiffLine>()
                    var seenOld = 0; var seenNew = 0
                    while (i < lines.size && (seenOld < oc || seenNew < nc)) {
                        val l = lines[i]
                        when {
                            l.startsWith("\\") -> {}
                            l.startsWith("+") -> { hl += DiffLine(Op.INSERT, l.substring(1), null, null); seenNew++ }
                            l.startsWith("-") -> { hl += DiffLine(Op.DELETE, l.substring(1), null, null); seenOld++ }
                            l.startsWith(" ") || l.isEmpty() -> { hl += DiffLine(Op.EQUAL, if (l.isEmpty()) "" else l.substring(1), null, null); seenOld++; seenNew++ }
                            else -> throw PfException("patch.bad_line", "Unexpected line in hunk: $l")
                        }
                        i++
                    }
                    if (seenOld != oc || seenNew != nc) throw PfException("patch.truncated", "Hunk line counts do not match its header.")
                    hunks += Hunk(os, oc, ns, nc, hl)
                }
                out += FilePatch(oldP, newP, hunks)
            } else i++
        }
        return out
    }

    /** Applies hunks exactly (context must match), allowing the hunk to be found within ±[fuzzLines]. */
    fun apply(original: String, hunks: List<Hunk>, fuzzLines: Int = 50): String {
        val src = lines(original).toMutableList()
        var delta = 0
        for (h in hunks) {
            val expected = h.lines.filter { it.op != Op.INSERT }.map { it.text }
            val replacement = h.lines.filter { it.op != Op.DELETE }.map { it.text }
            val nominal = (if (h.oldCount == 0) h.oldStart else h.oldStart - 1) + delta
            val at = (0..fuzzLines).asSequence().flatMap { sequenceOf(nominal + it, nominal - it) }.distinct()
                .firstOrNull { p -> p >= 0 && p + expected.size <= src.size && src.subList(p, p + expected.size) == expected }
                ?: throw PfException("patch.context_mismatch", "The file changed and this change no longer fits (near line ${h.oldStart}). Ask for a fresh suggestion.")
            repeat(expected.size) { src.removeAt(at) }
            src.addAll(at, replacement)
            delta += replacement.size - expected.size
        }
        val joined = src.joinToString("\n")
        // Unified diffs here are line-based; files keep (or, when new, get) a trailing newline.
        return if ((original.isEmpty() || original.endsWith("\n")) && joined.isNotEmpty()) joined + "\n" else joined
    }
}
