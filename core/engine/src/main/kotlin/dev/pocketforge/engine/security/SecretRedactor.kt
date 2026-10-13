package dev.pocketforge.engine.security

/**
 * Detects and masks likely secrets before text leaves the app (AI requests, remote builds,
 * diagnostic exports, logs). Detection is pattern-based and therefore best-effort: the
 * consent screen always shows the *redacted* payload so the user can catch anything missed.
 */
object SecretRedactor {
    data class Finding(val kind: String, val line: Int)
    data class Result(val text: String, val findings: List<Finding>) {
        val redactedCount get() = findings.size
    }

    private data class Rule(val kind: String, val regex: Regex, val group: Int = 0)

    private val rules = listOf(
        Rule("private-key", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
        Rule("anthropic-key", Regex("sk-ant-[A-Za-z0-9_\\-]{20,}")),
        Rule("openai-key", Regex("sk-(?:proj-)?[A-Za-z0-9_\\-]{20,}")),
        Rule("aws-access-key", Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
        Rule("github-token", Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\\b")),
        Rule("google-api-key", Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
        Rule("slack-token", Regex("\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b")),
        Rule("jwt", Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b")),
        Rule("bearer-token", Regex("(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=\\-]{16,})"), group = 2),
        Rule("url-credentials", Regex("(?i)(\\b[a-z][a-z0-9+.\\-]*://[^\\s:/@]+:)([^\\s@/]+)(@)"), group = 2),
        Rule(
            "assigned-secret",
            Regex("(?i)\\b((?:[a-z0-9_.\\-]*(?:password|passwd|pwd|secret|token|api[_\\-]?key|apikey|access[_\\-]?key|private[_\\-]?key|client[_\\-]?secret|storePassword|keyPassword))\\s*[:=]\\s*[\"']?)([^\"'\\s,;]{4,})"),
            group = 2,
        ),
    )

    private val pathRules = listOf(
        Regex("/data/(?:user/\\d+|data)/[A-Za-z0-9_.]+") to "<app-private>",
        Regex("/storage/emulated/\\d+") to "<shared-storage>",
        Regex("/sdcard") to "<shared-storage>",
        Regex("/(?:home|Users)/[^/\\s]+") to "<home>",
    )

    fun redact(input: String, redactPaths: Boolean = false): Result {
        var text = input
        val findings = ArrayList<Finding>()
        for (rule in rules) {
            text = rule.regex.replace(text) { m ->
                val line = lineOf(text, m.range.first)
                findings += Finding(rule.kind, line)
                if (rule.group == 0) "‹redacted:${rule.kind}›"
                else {
                    val g = m.groups[rule.group]!!
                    val start = g.range.first - m.range.first
                    val end = g.range.last + 1 - m.range.first
                    m.value.substring(0, start) + "‹redacted:${rule.kind}›" + m.value.substring(end)
                }
            }
        }
        if (redactPaths) for ((re, repl) in pathRules) text = re.replace(text, repl)
        return Result(text, findings.sortedBy { it.line })
    }

    fun containsLikelySecret(input: String): Boolean = rules.any { it.regex.containsMatchIn(input) }

    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }
}
