package dev.pocketforge.engine.common

import java.security.MessageDigest

/**
 * Typed, actionable failure. Every error carries a stable [code] (for tests and logs)
 * and a human-readable [message] that tells the user what to do next — never a
 * generic "something went wrong".
 */
open class PfException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

class SecurityViolation(code: String, message: String) : PfException(code, message)

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val code: String, val message: String) : Outcome<Nothing>

    companion object {
        inline fun <T> of(block: () -> T): Outcome<T> = try {
            Ok(block())
        } catch (e: PfException) {
            Err(e.code, e.message ?: e.code)
        }
    }
}

fun <T> Outcome<T>.getOrThrow(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw PfException(code, message)
}

/** Injectable clock so tests are deterministic. */
fun interface Clock { fun nowMillis(): Long }
object SystemClock : Clock { override fun nowMillis() = System.currentTimeMillis() }

object Hashing {
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
    fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) { append(HEX[(x.toInt() shr 4) and 0xF]); append(HEX[x.toInt() and 0xF]) }
    }
    private val HEX = "0123456789abcdef".toCharArray()
}

fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
}
