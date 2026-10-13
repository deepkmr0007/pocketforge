package dev.pocketforge.engine.diagnostics

import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.SystemClock
import dev.pocketforge.engine.security.SecretRedactor
import dev.pocketforge.engine.toolchain.Capability
import dev.pocketforge.engine.toolchain.DeviceFacts
import java.io.File
import java.time.Instant

/**
 * Structured local log with size-based rotation. Every line is redacted (secrets and private
 * paths) *before* it touches disk. Never uploaded; the user can export it explicitly.
 */
class LocalLog(private val dir: File, private val clock: Clock = SystemClock, private val maxBytes: Long = 512 * 1024, private val keep: Int = 3) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    init { dir.mkdirs() }
    private val current get() = File(dir, "pocketforge.log")

    @Synchronized
    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        val raw = message + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        val line = Json.stringify(
            Json.obj("t" to Instant.ofEpochMilli(clock.nowMillis()).toString(), "l" to level.name, "tag" to tag.take(40), "msg" to SecretRedactor.redact(raw, redactPaths = true).text.take(4000)),
        ) + "\n"
        if (current.exists() && current.length() + line.length > maxBytes) rotate()
        current.appendText(line)
    }

    fun info(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun warn(tag: String, msg: String, e: Throwable? = null) = log(Level.WARN, tag, msg, e)
    fun error(tag: String, msg: String, e: Throwable? = null) = log(Level.ERROR, tag, msg, e)

    private fun rotate() {
        File(dir, "pocketforge.log.$keep").delete()
        for (i in keep - 1 downTo 1) File(dir, "pocketforge.log.$i").takeIf { it.exists() }?.renameTo(File(dir, "pocketforge.log.${i + 1}"))
        current.renameTo(File(dir, "pocketforge.log.1"))
    }

    fun files(): List<File> = (listOf(current) + (1..keep).map { File(dir, "pocketforge.log.$it") }).filter { it.exists() }

    fun tail(maxLines: Int = 300): List<String> = files().reversed().flatMap { it.readLines() }.takeLast(maxLines)

    fun clear() { files().forEach { it.delete() } }
}

object DiagnosticReport {
    /** Human-readable report for the user to share by choice. Re-redacted as a whole before returning. */
    fun build(appVersion: String, facts: DeviceFacts, caps: List<Capability>, logTail: List<String>, settingsSummary: Map<String, String>): String {
        val body = buildString {
            append("PocketForge diagnostic report\n")
            append("App version: ").append(appVersion).append('\n')
            append("Android: ").append(facts.release).append(" (API ").append(facts.apiLevel).append(")\n")
            append("ABIs: ").append(facts.abis.joinToString()).append('\n')
            append("RAM: ").append(facts.totalRamBytes / (1024 * 1024)).append(" MB total\n")
            append("Free storage: ").append(facts.freeStorageBytes / (1024 * 1024)).append(" MB\n\n")
            append("Capabilities\n")
            caps.forEach { append("- ").append(it.title).append(": ").append(it.status.label).append(" — ").append(it.detail).append('\n') }
            append("\nSettings (no secrets)\n")
            settingsSummary.forEach { (k, v) -> append("- ").append(k).append(": ").append(v).append('\n') }
            append("\nRecent log (redacted)\n")
            logTail.forEach { append(it).append('\n') }
        }
        return SecretRedactor.redact(body, redactPaths = true).text
    }
}
