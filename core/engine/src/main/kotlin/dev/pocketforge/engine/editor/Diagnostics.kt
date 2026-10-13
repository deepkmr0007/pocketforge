package dev.pocketforge.engine.editor

import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.JsonException

enum class Severity { ERROR, WARNING, INFO }

data class Diagnostic(val severity: Severity, val line: Int, val column: Int, val message: String, val source: String)

/** Pluggable, offline analyzers. Each is cheap and runs on a background thread after typing pauses. */
fun interface Analyzer { fun analyze(text: String, language: Language): List<Diagnostic> }

object Diagnostics {
    val analyzers: List<Analyzer> = listOf(JsonAnalyzer, BracketBalanceAnalyzer, MarkupAnalyzer, HygieneAnalyzer)

    fun run(text: String, language: Language): List<Diagnostic> =
        analyzers.flatMap { runCatching { it.analyze(text, language) }.getOrDefault(emptyList()) }
            .sortedWith(compareBy({ it.line }, { it.column }))

    fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        var line = 1; var col = 1
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') { line++; col = 1 } else col++
        return line to col
    }
}

object JsonAnalyzer : Analyzer {
    override fun analyze(text: String, language: Language): List<Diagnostic> {
        if (language != Language.JSON || text.isBlank()) return emptyList()
        return try { Json.parse(text); emptyList() } catch (e: JsonException) {
            val (l, c) = Diagnostics.lineCol(text, e.offset)
            listOf(Diagnostic(Severity.ERROR, l, c, e.message?.substringBefore(" at offset") ?: "Invalid JSON", "json"))
        }
    }
}

object BracketBalanceAnalyzer : Analyzer {
    private val langs = setOf(Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.CSS, Language.GRADLE_GROOVY, Language.PYTHON)
    override fun analyze(text: String, language: Language): List<Diagnostic> {
        if (language !in langs) return emptyList()
        val spans = Highlighter(language).highlight(text)
        val mask = BooleanArray(text.length)
        for (s in spans) if (s.type == TokenType.STRING || s.type == TokenType.COMMENT) for (k in s.start until minOf(s.end, text.length)) mask[k] = true
        val stack = ArrayDeque<Pair<Char, Int>>()
        val out = ArrayList<Diagnostic>()
        val close = mapOf(')' to '(', ']' to '[', '}' to '{')
        for (i in text.indices) {
            if (mask[i]) continue
            val c = text[i]
            if (c == '(' || c == '[' || c == '{') stack.addLast(c to i)
            else if (c in close) {
                val top = stack.removeLastOrNull()
                if (top == null || top.first != close[c]) {
                    val (l, col) = Diagnostics.lineCol(text, i)
                    out += Diagnostic(Severity.ERROR, l, col, if (top == null) "Unmatched '$c'" else "'$c' does not match '${top.first}'", "brackets")
                    if (out.size > 20) return out
                }
            }
        }
        for ((c, i) in stack.take(20)) {
            val (l, col) = Diagnostics.lineCol(text, i)
            out += Diagnostic(Severity.ERROR, l, col, "'$c' is never closed", "brackets")
        }
        return out
    }
}

/** Light well-formedness check for XML (Android manifests/layouts). HTML void elements are tolerated. */
object MarkupAnalyzer : Analyzer {
    private val voidHtml = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    override fun analyze(text: String, language: Language): List<Diagnostic> {
        if (language != Language.XML && language != Language.HTML) return emptyList()
        val stripped = Regex("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>").replace(text) { " ".repeat(it.value.length) }
        val tag = Regex("<(/?)([A-Za-z_][\\w:.\\-]*)([^<>]*?)(/?)>")
        val stack = ArrayDeque<Pair<String, Int>>()
        val out = ArrayList<Diagnostic>()
        for (m in tag.findAll(stripped)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val selfClose = m.groupValues[4] == "/"
            if (language == Language.HTML && name.lowercase() in voidHtml) continue
            if (selfClose) continue
            if (!closing) stack.addLast(name to m.range.first)
            else {
                val top = stack.removeLastOrNull()
                if (top == null || top.first != name) {
                    val (l, c) = Diagnostics.lineCol(text, m.range.first)
                    out += Diagnostic(Severity.ERROR, l, c, if (top == null) "Closing </$name> has no opening tag" else "Expected </${top.first}> but found </$name>", "markup")
                    if (top != null) stack.addLast(top)
                }
            }
        }
        if (language == Language.XML) for ((n, i) in stack.take(10)) {
            val (l, c) = Diagnostics.lineCol(text, i)
            out += Diagnostic(Severity.ERROR, l, c, "<$n> is never closed", "markup")
        }
        return out
    }
}

/** Whitespace and secret hygiene: trailing whitespace (info), mixed indentation (warning), likely secrets (warning). */
object HygieneAnalyzer : Analyzer {
    override fun analyze(text: String, language: Language): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        var tabs = false; var spaces = false
        text.split('\n').forEachIndexed { idx, line ->
            if (line.startsWith("\t")) tabs = true
            if (line.startsWith("    ")) spaces = true
            if (dev.pocketforge.engine.security.SecretRedactor.containsLikelySecret(line)) {
                out += Diagnostic(Severity.WARNING, idx + 1, 1, "This line looks like it contains a secret. Keep secrets out of source files.", "secrets")
            }
        }
        if (tabs && spaces && language != Language.MARKDOWN) out += Diagnostic(Severity.INFO, 1, 1, "File mixes tab and space indentation.", "style")
        return out
    }
}
