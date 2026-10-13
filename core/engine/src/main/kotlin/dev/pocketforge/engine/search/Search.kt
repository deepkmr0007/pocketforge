package dev.pocketforge.engine.search

import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.TextCodec
import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class SearchQuery(
    val text: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = listOf("build/**", ".gradle/**", ".idea/**", "node_modules/**", "**/*.min.js"),
    val maxResults: Int = 2_000,
    val maxFileBytes: Long = 2L * 1024 * 1024,
    val regexTimeoutMillis: Long = 1_500,
)

data class SearchMatch(
    val path: String,
    val line: Int,
    val column: Int,
    /** Offset in the `\n`-normalised decoded text. */
    val offset: Int,
    val length: Int,
    val lineText: String,
    val fileSha256: String,
)

data class SearchResult(val matches: List<SearchMatch>, val filesScanned: Int, val truncated: Boolean, val skipped: List<String>)

class RegexTimeout : PfException("search.regex_timeout", "The pattern took too long (possible catastrophic backtracking). Simplify it.")

/** Glob with `**`, `*`, `?` against `/`-separated relative paths. */
object Glob {
    fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.append("$").toString())
    }
}

object ProjectSearcher {
    fun compile(q: SearchQuery): Pattern {
        if (q.text.isEmpty()) throw PfException("search.empty", "Type something to search for.")
        if (q.regex) RegexSafety.check(q.text)
        var src = if (q.regex) q.text else Pattern.quote(q.text)
        if (q.wholeWord) src = "(?<![\\w])(?:$src)(?![\\w])"
        val flags = (if (q.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) or Pattern.MULTILINE
        return try { Pattern.compile(src, flags) } catch (e: PatternSyntaxException) {
            throw PfException("search.bad_regex", "Invalid regular expression: ${e.description}")
        }
    }

    fun search(root: File, q: SearchQuery, cancelled: () -> Boolean = { false }): SearchResult {
        val pattern = compile(q)
        val guard = PathGuard(root)
        val inc = q.include.map { Glob.toRegex(it) }
        val exc = q.exclude.map { Glob.toRegex(it) }
        val out = ArrayList<SearchMatch>()
        val skipped = ArrayList<String>()
        var scanned = 0
        for (f in guard.walkFiles()) {
            if (cancelled()) break
            val rel = guard.relativize(f)
            if (exc.any { it.matches(rel) }) continue
            if (inc.isNotEmpty() && inc.none { it.matches(rel) || it.matches(f.name) }) continue
            if (f.length() > q.maxFileBytes) { skipped += "$rel (too large)"; continue }
            val bytes = f.readBytes()
            if (TextCodec.looksBinary(bytes)) continue
            scanned++
            val text = TextCodec.decode(bytes).text
            val sha = Hashing.sha256(bytes)
            val m = pattern.matcher(Deadline(text, System.nanoTime() + q.regexTimeoutMillis * 1_000_000))
            val lineStarts = lineStarts(text)
            try {
                while (m.find()) {
                    if (m.end() == m.start()) { if (m.end() >= text.length) break; continue }
                    val line = lineIndex(lineStarts, m.start())
                    val ls = lineStarts[line]
                    val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
                    out += SearchMatch(rel, line + 1, m.start() - ls + 1, m.start(), m.end() - m.start(), text.substring(ls, le).take(400), sha)
                    if (out.size >= q.maxResults) return SearchResult(out, scanned, true, skipped)
                }
            } catch (e: RegexTimeout) { throw e }
        }
        return SearchResult(out, scanned, false, skipped)
    }

    internal fun lineStarts(t: String): IntArray {
        val l = ArrayList<Int>(); l += 0
        for (i in t.indices) if (t[i] == '\n') l += i + 1
        return l.toIntArray()
    }

    internal fun lineIndex(starts: IntArray, offset: Int): Int {
        val i = starts.binarySearch(offset)
        return if (i >= 0) i else -i - 2
    }

    /** CharSequence that aborts regex evaluation after a deadline (guards against ReDoS). */
    private class Deadline(private val s: CharSequence, private val deadline: Long) : CharSequence {
        private var n = 0
        override val length get() = s.length
        override fun get(index: Int): Char {
            if (++n and 0xFFF == 0 && System.nanoTime() > deadline) throw RegexTimeout()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadline(s.subSequence(startIndex, endIndex), deadline)
        override fun toString() = s.toString()
    }
}

/**
 * Static ReDoS guard. Android's java.util.regex is backed by ICU, which cannot be interrupted from
 * Kotlin, so the runtime deadline above only helps on the JVM. Here we reject the classic
 * catastrophic shape — a repeated group that itself contains a repetition, e.g. `(a+)+` or `(\w*\s?)*`.
 */
object RegexSafety {
    fun check(p: String) {
        if (p.length > 1000) throw PfException("search.unsafe_regex", "Pattern is too long (max 1000 characters).")
        val groupHasQuant = ArrayDeque<Boolean>()
        var i = 0
        var inClass = false
        var lastGroupHadQuant = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> groupHasQuant.addLast(false)
                c == ')' -> { lastGroupHadQuant = groupHasQuant.removeLastOrNull() ?: false
                    val next = p.getOrNull(i + 1)
                    if ((next == '+' || next == '*' || next == '{') && lastGroupHadQuant) {
                        throw PfException("search.unsafe_regex", "Nested repetition like (a+)+ can freeze the search. Rewrite it without a repeated group that repeats inside.")
                    }
                    if (groupHasQuant.isNotEmpty() && (lastGroupHadQuant || next == '+' || next == '*' || next == '{')) {
                        groupHasQuant[groupHasQuant.size - 1] = true
                    }
                }
                c == '+' || c == '*' || c == '{' -> if (groupHasQuant.isNotEmpty()) groupHasQuant[groupHasQuant.size - 1] = true
            }
            i++
        }
    }
}

data class PlannedReplacement(val match: SearchMatch, val replacement: String, val selected: Boolean = true)

/** Everything needed to undo a replace-all, per file. */
class ReplaceUndo internal constructor(internal val files: Map<String, Pair<ByteArray, String>>) {
    val fileCount get() = files.size
}

data class ReplaceOutcome(val filesChanged: Int, val replacements: Int, val staleFiles: List<String>, val undo: ReplaceUndo)

object Replacer {
    /** Computes replacement text for each match so the user can preview exactly what will change. */
    fun plan(root: File, q: SearchQuery, matches: List<SearchMatch>, replacement: String): List<PlannedReplacement> {
        val pattern = ProjectSearcher.compile(q)
        val guard = PathGuard(root)
        val textCache = HashMap<String, String>()
        return matches.map { mt ->
            val text = textCache.getOrPut(mt.path) { TextCodec.decode(guard.resolve(mt.path).readBytes()).text }
            val repl = if (q.regex) expand(text, mt, pattern, replacement) else replacement
            PlannedReplacement(mt, repl)
        }
    }

    /** Expands `$1`-style group references for one match, honouring lookarounds outside the match. */
    private fun expand(text: String, mt: SearchMatch, pattern: Pattern, replacement: String): String {
        val m: Matcher = pattern.matcher(text)
        m.useTransparentBounds(true).useAnchoringBounds(false)
        m.region(mt.offset, text.length)
        if (!m.lookingAt() || m.end() != mt.offset + mt.length) return replacement
        val sb = StringBuffer()
        try { m.appendReplacement(sb, replacement) } catch (e: Exception) {
            throw PfException("search.bad_replacement", "Invalid replacement: ${e.message}. Use \\$ for a literal dollar sign.")
        }
        // appendReplacement copies text[0, matchStart) first; the tail is the expansion.
        return sb.substring(mt.offset)
    }

    /**
     * Applies the selected replacements. A file whose contents changed since the search is skipped
     * and reported (never partially edited). Encoding and line endings are preserved.
     */
    fun apply(root: File, plan: List<PlannedReplacement>): ReplaceOutcome {
        val guard = PathGuard(root)
        val backups = LinkedHashMap<String, Pair<ByteArray, String>>()
        val stale = ArrayList<String>()
        var count = 0
        for ((path, items) in plan.filter { it.selected }.groupBy { it.match.path }) {
            val f = guard.resolve(path)
            val bytes = f.readBytes()
            if (Hashing.sha256(bytes) != items.first().match.fileSha256) { stale += path; continue }
            val dec = TextCodec.decode(bytes)
            val sb = StringBuilder(dec.text)
            for (r in items.sortedByDescending { it.match.offset }) {
                sb.replace(r.match.offset, r.match.offset + r.match.length, r.replacement); count++
            }
            val newBytes = TextCodec.encode(sb.toString(), dec.encoding, dec.lineEnding)
            AtomicFileWriter.write(f, newBytes)
            backups[path] = bytes to Hashing.sha256(newBytes)
        }
        return ReplaceOutcome(backups.size, count, stale, ReplaceUndo(backups))
    }

    /** Restores originals for files that have not been edited again since the replace. */
    fun undo(root: File, undo: ReplaceUndo): List<String> {
        val guard = PathGuard(root)
        val conflicts = ArrayList<String>()
        for ((path, pair) in undo.files) {
            val f = guard.resolve(path)
            if (!f.exists() || Hashing.sha256(f.readBytes()) != pair.second) { conflicts += path; continue }
            AtomicFileWriter.write(f, pair.first)
        }
        return conflicts
    }
}
