package dev.pocketforge.engine.common

/**
 * Small, dependency-free JSON model (RFC 8259) used for metadata files, the AI request
 * payloads and the remote build protocol. Kept in-house so the engine has zero third-party
 * runtime dependencies (smaller attack surface, nothing to license-audit).
 * Hard limits on depth and size defend against hostile input.
 */
sealed interface JsonValue
data object JsonNull : JsonValue
data class JsonBool(val value: Boolean) : JsonValue
data class JsonNumber(val raw: String) : JsonValue {
    fun toLong(): Long = raw.toDouble().toLong()
    fun toInt(): Int = toLong().toInt()
    fun toDouble(): Double = raw.toDouble()
}
data class JsonString(val value: String) : JsonValue
data class JsonArray(val items: List<JsonValue>) : JsonValue
data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
    fun str(key: String): String? = (fields[key] as? JsonString)?.value
    fun long(key: String): Long? = (fields[key] as? JsonNumber)?.toLong()
    fun int(key: String): Int? = (fields[key] as? JsonNumber)?.toInt()
    fun bool(key: String): Boolean? = (fields[key] as? JsonBool)?.value
    fun obj(key: String): JsonObject? = fields[key] as? JsonObject
    fun arr(key: String): List<JsonValue> = (fields[key] as? JsonArray)?.items ?: emptyList()
}

class JsonException(message: String, val offset: Int) : PfException("json.parse", "$message at offset $offset")

object Json {
    const val MAX_DEPTH = 64
    const val MAX_CHARS = 32 * 1024 * 1024

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(linkedMapOf<String, JsonValue>().apply { pairs.forEach { (k, v) -> put(k, wrap(v)) } })

    fun wrap(v: Any?): JsonValue = when (v) {
        null -> JsonNull
        is JsonValue -> v
        is String -> JsonString(v)
        is Boolean -> JsonBool(v)
        is Int, is Long, is Short, is Byte -> JsonNumber(v.toString())
        is Double -> { require(v.isFinite()) { "non-finite number" }; JsonNumber(v.toString()) }
        is Float -> { require(v.isFinite()) { "non-finite number" }; JsonNumber(v.toString()) }
        is Map<*, *> -> JsonObject(linkedMapOf<String, JsonValue>().apply { v.forEach { (k, x) -> put(k.toString(), wrap(x)) } })
        is Iterable<*> -> JsonArray(v.map { wrap(it) })
        is Array<*> -> JsonArray(v.map { wrap(it) })
        else -> JsonString(v.toString())
    }

    fun stringify(v: JsonValue, pretty: Boolean = false): String =
        StringBuilder().also { write(v, it, pretty, 0) }.toString()

    private fun write(v: JsonValue, sb: StringBuilder, pretty: Boolean, indent: Int) {
        when (v) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(v.value)
            is JsonNumber -> sb.append(v.raw)
            is JsonString -> quote(v.value, sb)
            is JsonArray -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (pretty) { sb.append('\n'); repeat(indent + 2) { sb.append(' ') } }
                    write(item, sb, pretty, indent + 2)
                }
                if (pretty) { sb.append('\n'); repeat(indent) { sb.append(' ') } }
                sb.append(']')
            }
            is JsonObject -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); repeat(indent + 2) { sb.append(' ') } }
                    quote(k, sb); sb.append(if (pretty) ": " else ":")
                    write(item, sb, pretty, indent + 2)
                }
                if (pretty) { sb.append('\n'); repeat(indent) { sb.append(' ') } }
                sb.append('}')
            }
        }
    }

    fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): JsonValue {
        if (text.length > MAX_CHARS) throw JsonException("document too large", 0)
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("trailing characters", p.i)
        return v
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonException("expected object", 0)

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonException("nesting too deep", i)
            if (i >= s.length) throw JsonException("unexpected end", i)
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonString(str())
                't' -> lit("true", JsonBool(true))
                'f' -> lit("false", JsonBool(false))
                'n' -> lit("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c'", i)
            }
        }
        fun lit(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) throw JsonException("invalid literal", i)
            i += word.length; return v
        }
        fun num(): JsonValue {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw JsonException("bad number", start)
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i].isDigit()) i++ } else throw JsonException("bad number", start)
            if (i < s.length && s[i] == '.') { i++; val d = i; while (i < s.length && s[i].isDigit()) i++; if (d == i) throw JsonException("bad fraction", start) }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i; while (i < s.length && s[i].isDigit()) i++; if (d == i) throw JsonException("bad exponent", start)
            }
            return JsonNumber(s.substring(start, i))
        }
        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string", i)
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("bad escape", i)
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad unicode escape", i)
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonException("bad unicode escape", i)
                                sb.append(code.toChar()); i += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'", i)
                        }
                    }
                    c < ' ' -> throw JsonException("control character in string", i - 1)
                    else -> sb.append(c)
                }
            }
        }
        fun arr(depth: Int): JsonValue {
            i++; ws()
            val items = ArrayList<JsonValue>()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(items) }
            while (true) {
                ws(); items.add(value(depth + 1)); ws()
                if (i >= s.length) throw JsonException("unterminated array", i)
                when (s[i++]) { ',' -> continue; ']' -> return JsonArray(items); else -> throw JsonException("expected , or ]", i - 1) }
            }
        }
        fun obj(depth: Int): JsonValue {
            i++; ws()
            val m = LinkedHashMap<String, JsonValue>()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(m) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonException("expected key", i)
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw JsonException("expected :", i)
                i++; ws()
                m[k] = value(depth + 1); ws()
                if (i >= s.length) throw JsonException("unterminated object", i)
                when (s[i++]) { ',' -> continue; '}' -> return JsonObject(m); else -> throw JsonException("expected , or }", i - 1) }
            }
        }
    }
}
