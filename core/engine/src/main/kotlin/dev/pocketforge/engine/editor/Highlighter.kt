package dev.pocketforge.engine.editor

enum class Language(val label: String) {
    KOTLIN("Kotlin"), JAVA("Java"), XML("XML"), HTML("HTML"), CSS("CSS"), JAVASCRIPT("JavaScript"),
    JSON("JSON"), PYTHON("Python"), MARKDOWN("Markdown"), GRADLE_GROOVY("Gradle"), PROPERTIES("Properties"), PLAIN("Plain text");

    companion object {
        fun forFileName(name: String): Language {
            val n = name.lowercase()
            return when {
                n.endsWith(".kt") || n.endsWith(".kts") -> KOTLIN
                n.endsWith(".java") -> JAVA
                n.endsWith(".xml") || n.endsWith(".svg") -> XML
                n.endsWith(".html") || n.endsWith(".htm") -> HTML
                n.endsWith(".css") -> CSS
                n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".ts") -> JAVASCRIPT
                n.endsWith(".json") -> JSON
                n.endsWith(".py") -> PYTHON
                n.endsWith(".md") || n.endsWith(".markdown") -> MARKDOWN
                n.endsWith(".gradle") -> GRADLE_GROOVY
                n.endsWith(".properties") || n == ".gitignore" || n.endsWith(".pro") -> PROPERTIES
                else -> PLAIN
            }
        }
    }
}

enum class TokenType { KEYWORD, TYPE, STRING, NUMBER, COMMENT, ANNOTATION, TAG, ATTRIBUTE, HEADING, EMPHASIS, CODE, PUNCTUATION }

data class Span(val start: Int, val end: Int, val type: TokenType)

/** Lexer state carried from the end of one line to the start of the next. */
enum class LineState { NORMAL, BLOCK_COMMENT, TRIPLE_DQ, TRIPLE_SQ, TEMPLATE, XML_COMMENT, MD_FENCE }

/**
 * Line-incremental syntax highlighter. Each line is lexed given the state at its start; results
 * are cached by (line text, start state), so after an edit only lines whose input changed are
 * re-lexed and lexing stops propagating as soon as states re-converge.
 */
class Highlighter(val language: Language, private val cacheLimit: Int = 20_000) {
    private data class Key(val line: String, val state: LineState)
    private data class LineResult(val spans: List<Span>, val endState: LineState)
    private val cache = object : LinkedHashMap<Key, LineResult>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, LineResult>?) = size > cacheLimit
    }
    var lastLexedLines = 0
        private set

    @Synchronized
    fun highlight(text: String): List<Span> {
        if (language == Language.PLAIN) return emptyList()
        val out = ArrayList<Span>()
        var state = LineState.NORMAL
        var lineStart = 0
        lastLexedLines = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, nl)
            val key = Key(line, state)
            val r = cache[key] ?: lexLine(line, state).also { cache[key] = it; lastLexedLines++ }
            for (s in r.spans) out += Span(s.start + lineStart, s.end + lineStart, s.type)
            state = r.endState
            if (nl == text.length) break
            lineStart = nl + 1
        }
        return out
    }

    private fun lexLine(line: String, start: LineState): LineResult = when (language) {
        Language.XML, Language.HTML -> lexMarkup(line, start)
        Language.MARKDOWN -> lexMarkdown(line, start)
        Language.PROPERTIES -> lexProperties(line)
        Language.PYTHON -> lexCLike(line, start, PY)
        Language.JSON -> lexCLike(line, start, JSONSPEC)
        Language.CSS -> lexCLike(line, start, CSSSPEC)
        Language.JAVASCRIPT -> lexCLike(line, start, JS)
        Language.JAVA -> lexCLike(line, start, JAVASPEC)
        Language.GRADLE_GROOVY -> lexCLike(line, start, GROOVY)
        else -> lexCLike(line, start, KT)
    }

    private class Spec(
        val keywords: Set<String>,
        val lineComment: String?,
        val blockComments: Boolean,
        val tripleQuotes: Boolean,
        val singleQuoteStrings: Boolean,
        val backtickTemplates: Boolean = false,
        val annotations: Char? = '@',
        val hashComment: Boolean = false,
    )

    private fun lexCLike(line: String, startState: LineState, spec: Spec): LineResult {
        val spans = ArrayList<Span>()
        var i = 0
        var state = startState
        fun closeMulti(open: LineState): Int {
            val (closer, type) = when (open) {
                LineState.BLOCK_COMMENT -> "*/" to TokenType.COMMENT
                LineState.TRIPLE_DQ -> "\"\"\"" to TokenType.STRING
                LineState.TRIPLE_SQ -> "'''" to TokenType.STRING
                LineState.TEMPLATE -> "`" to TokenType.STRING
                else -> return i
            }
            val from = i
            val end = findCloser(line, i, closer, open == LineState.TEMPLATE)
            return if (end < 0) { spans += Span(from, line.length, type); line.length }
            else { spans += Span(from, end + closer.length, type); state = LineState.NORMAL; end + closer.length }
        }
        if (state != LineState.NORMAL) i = closeMulti(state)
        while (i < line.length) {
            val c = line[i]
            when {
                spec.lineComment != null && line.startsWith(spec.lineComment, i) -> { spans += Span(i, line.length, TokenType.COMMENT); i = line.length }
                spec.hashComment && c == '#' -> { spans += Span(i, line.length, TokenType.COMMENT); i = line.length }
                spec.blockComments && line.startsWith("/*", i) -> { state = LineState.BLOCK_COMMENT; i += 2; val s = i - 2; i = closeMulti(state); spans[spans.size - 1] = Span(s, spans.last().end, TokenType.COMMENT) }
                spec.tripleQuotes && line.startsWith("\"\"\"", i) -> { val s = i; state = LineState.TRIPLE_DQ; i += 3; i = closeMulti(state); spans[spans.size - 1] = Span(s, spans.last().end, TokenType.STRING) }
                spec.tripleQuotes && spec.singleQuoteStrings && line.startsWith("'''", i) -> { val s = i; state = LineState.TRIPLE_SQ; i += 3; i = closeMulti(state); spans[spans.size - 1] = Span(s, spans.last().end, TokenType.STRING) }
                spec.backtickTemplates && c == '`' -> { val s = i; state = LineState.TEMPLATE; i += 1; i = closeMulti(state); spans[spans.size - 1] = Span(s, spans.last().end, TokenType.STRING) }
                c == '"' || (c == '\'' && spec.singleQuoteStrings) || (c == '\'' && language in setOf(Language.KOTLIN, Language.JAVA)) -> {
                    val end = findCloser(line, i + 1, c.toString(), true)
                    val e = if (end < 0) line.length else end + 1
                    spans += Span(i, e, TokenType.STRING); i = e
                }
                c.isDigit() || (c == '.' && line.getOrNull(i + 1)?.isDigit() == true && line.getOrNull(i - 1)?.isLetterOrDigit() != true) -> {
                    if (i > 0 && (line[i - 1].isLetterOrDigit() || line[i - 1] == '_')) { i++; continue }
                    val s = i
                    i++
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
                    spans += Span(s, i, TokenType.NUMBER)
                }
                spec.annotations != null && c == spec.annotations && line.getOrNull(i + 1)?.isLetter() == true -> {
                    val s = i; i++
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '.')) i++
                    spans += Span(s, i, TokenType.ANNOTATION)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val s = i
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$' || (language == Language.CSS && line[i] == '-'))) i++
                    val word = line.substring(s, i)
                    when {
                        word in spec.keywords -> spans += Span(s, i, TokenType.KEYWORD)
                        word[0].isUpperCase() && language != Language.CSS -> spans += Span(s, i, TokenType.TYPE)
                        language == Language.JSON && (word == "true" || word == "false" || word == "null") -> spans += Span(s, i, TokenType.KEYWORD)
                    }
                }
                c in "{}()[]" -> { spans += Span(i, i + 1, TokenType.PUNCTUATION); i++ }
                else -> i++
            }
        }
        return LineResult(spans, state)
    }

    private fun findCloser(line: String, from: Int, closer: String, escapes: Boolean): Int {
        var j = from
        while (j < line.length) {
            if (escapes && line[j] == '\\') { j += 2; continue }
            if (line.startsWith(closer, j)) return j
            j++
        }
        return -1
    }

    private fun lexMarkup(line: String, start: LineState): LineResult {
        val spans = ArrayList<Span>()
        var i = 0
        var state = start
        if (state == LineState.XML_COMMENT) {
            val e = line.indexOf("-->")
            if (e < 0) return LineResult(listOf(Span(0, line.length, TokenType.COMMENT)), state)
            spans += Span(0, e + 3, TokenType.COMMENT); i = e + 3; state = LineState.NORMAL
        }
        while (i < line.length) {
            if (line.startsWith("<!--", i)) {
                val e = line.indexOf("-->", i + 4)
                if (e < 0) { spans += Span(i, line.length, TokenType.COMMENT); return LineResult(spans, LineState.XML_COMMENT) }
                spans += Span(i, e + 3, TokenType.COMMENT); i = e + 3; continue
            }
            if (line[i] == '<') {
                val s = i; i++
                if (i < line.length && (line[i] == '/' || line[i] == '?' || line[i] == '!')) i++
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] in ":-_.")) i++
                spans += Span(s, i, TokenType.TAG)
                continue
            }
            if (line[i] == '"' || line[i] == '\'') {
                val q = line[i]; val e = line.indexOf(q, i + 1).let { if (it < 0) line.length - 1 else it }
                spans += Span(i, e + 1, TokenType.STRING); i = e + 1; continue
            }
            if (line[i].isLetter() && i > 0 && line[i - 1] == ' ') {
                val s = i
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] in ":-_.")) i++
                if (line.getOrNull(i) == '=') spans += Span(s, i, TokenType.ATTRIBUTE)
                continue
            }
            if (line[i] == '>' || (line[i] == '/' && line.getOrNull(i + 1) == '>')) { val l = if (line[i] == '/') 2 else 1; spans += Span(i, i + l, TokenType.TAG); i += l; continue }
            i++
        }
        return LineResult(spans, state)
    }

    private fun lexMarkdown(line: String, start: LineState): LineResult {
        val t = line.trimStart()
        if (t.startsWith("```")) return LineResult(listOf(Span(0, line.length, TokenType.CODE)), if (start == LineState.MD_FENCE) LineState.NORMAL else LineState.MD_FENCE)
        if (start == LineState.MD_FENCE) return LineResult(listOf(Span(0, line.length, TokenType.CODE)), start)
        if (t.startsWith("#")) return LineResult(listOf(Span(0, line.length, TokenType.HEADING)), start)
        val spans = ArrayList<Span>()
        Regex("`[^`]+`").findAll(line).forEach { spans += Span(it.range.first, it.range.last + 1, TokenType.CODE) }
        Regex("(\\*\\*|__)[^*_]+\\1|(?<![*\\w])\\*[^*]+\\*(?!\\*)").findAll(line).forEach { spans += Span(it.range.first, it.range.last + 1, TokenType.EMPHASIS) }
        Regex("\\[[^\\]]+]\\([^)]+\\)").findAll(line).forEach { spans += Span(it.range.first, it.range.last + 1, TokenType.ATTRIBUTE) }
        return LineResult(spans.sortedBy { it.start }, start)
    }

    private fun lexProperties(line: String): LineResult {
        val t = line.trimStart()
        if (t.startsWith("#") || t.startsWith("!")) return LineResult(listOf(Span(0, line.length, TokenType.COMMENT)), LineState.NORMAL)
        val eq = line.indexOfFirst { it == '=' || it == ':' }
        return if (eq > 0) LineResult(listOf(Span(0, eq, TokenType.ATTRIBUTE), Span(eq + 1, line.length, TokenType.STRING)), LineState.NORMAL)
        else LineResult(emptyList(), LineState.NORMAL)
    }

    companion object {
        private val KT = Spec(setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return",
            "break", "continue", "throw", "try", "catch", "finally", "in", "is", "as", "null", "true", "false", "this", "super",
            "private", "public", "protected", "internal", "override", "open", "abstract", "data", "sealed", "enum", "companion",
            "suspend", "inline", "reified", "lateinit", "const", "typealias", "by", "init", "constructor", "vararg", "operator",
            "infix", "tailrec", "annotation", "inner", "out", "where", "value", "crossinline", "noinline", "expect", "actual",
        ), "//", true, true, false)
        private val JAVASPEC = Spec(setOf(
            "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static",
            "final", "abstract", "void", "int", "long", "short", "byte", "char", "boolean", "float", "double", "new", "return", "if",
            "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "throw", "throws", "try", "catch",
            "finally", "null", "true", "false", "this", "super", "synchronized", "volatile", "transient", "instanceof", "var", "record",
        ), "//", true, true, false)
        private val GROOVY = Spec(JAVASPEC.keywords + setOf("def", "apply", "plugin", "dependencies", "repositories", "android"), "//", true, true, true)
        private val JS = Spec(setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
            "continue", "new", "class", "extends", "import", "export", "from", "async", "await", "try", "catch", "finally", "throw",
            "typeof", "instanceof", "in", "of", "null", "undefined", "true", "false", "this", "super", "yield", "delete", "void",
            "interface", "type", "enum", "implements", "readonly", "public", "private", "protected", "static",
        ), "//", true, false, true, backtickTemplates = true)
        private val PY = Spec(setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "None", "True", "False",
            "import", "from", "as", "with", "try", "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield",
            "global", "nonlocal", "assert", "del", "async", "await", "match", "case", "self",
        ), null, false, true, true, hashComment = true)
        private val JSONSPEC = Spec(emptySet(), null, false, false, false, annotations = null)
        private val CSSSPEC = Spec(setOf("important", "media", "import", "keyframes", "from", "to", "root"), null, true, false, true, annotations = '@')
    }
}

/** Finds the bracket matching the one at or just before [offset], skipping strings and comments. */
object BracketMatcher {
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val reverse = pairs.entries.associate { (k, v) -> v to k }

    fun match(text: String, offset: Int, spans: List<Span>): Pair<Int, Int>? {
        val mask = BooleanArray(text.length)
        for (s in spans) if (s.type == TokenType.STRING || s.type == TokenType.COMMENT) {
            for (k in s.start.coerceAtLeast(0) until s.end.coerceAtMost(text.length)) mask[k] = true
        }
        fun skip(i: Int) = mask[i]
        val at = listOf(offset, offset - 1).firstOrNull { it in text.indices && (text[it] in pairs || text[it] in reverse) && !skip(it) } ?: return null
        val c = text[at]
        if (c in pairs) {
            val close = pairs.getValue(c); var depth = 0
            for (i in at until text.length) {
                if (skip(i)) continue
                if (text[i] == c) depth++ else if (text[i] == close && --depth == 0) return at to i
            }
        } else {
            val open = reverse.getValue(c); var depth = 0
            for (i in at downTo 0) {
                if (skip(i)) continue
                if (text[i] == c) depth++ else if (text[i] == open && --depth == 0) return i to at
            }
        }
        return null
    }
}
