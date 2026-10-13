package dev.pocketforge.engine.consent

import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SystemClock
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** One item of data that will leave the device, shown to the user before approval. */
data class DataItem(val label: String, val sizeBytes: Long, val preview: String)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    /**
     * Binds method, URL, every header (values hashed so secrets are never kept in clear) and the body.
     * Header *values* are never displayed or logged; the consent UI shows header names only.
     */
    fun fingerprint(): String {
        val headerNames = headers.entries
            .map { (k, v) -> k.lowercase() + "=" + Hashing.sha256(v) }
            .sorted().joinToString(",")
        val out = java.io.ByteArrayOutputStream()
        for (part in listOf(method.uppercase().toByteArray(), url.toByteArray(), headerNames.toByteArray())) {
            out.write(part); out.write(0)
        }
        out.write(body)
        return Hashing.sha256(out.toByteArray())
    }
    val host: String get() = URI(url).host?.lowercase() ?: ""
}

data class ConsentRequest(
    val feature: String,
    val provider: String,
    val purpose: String,
    val risks: List<String>,
    val dataItems: List<DataItem>,
    val request: HttpRequest,
    val sendsSourceCode: Boolean,
    val redactionsApplied: Int = 0,
) {
    val destination: String get() = request.host
    val totalBytes: Long get() = request.body.size.toLong()
}

enum class ConsentDecision { APPROVE_ONCE, DENY, CANCEL }

/**
 * UI hook. Implementations block the calling *background* thread until the user answers.
 * Any exception, timeout or missing UI is treated as DENY (fail closed).
 */
fun interface ConsentPrompter { fun ask(request: ConsentRequest): ConsentDecision }

class ConsentDenied(message: String) : PfException("consent.denied", message)

/**
 * A single-use, short-lived permission bound to the exact bytes of one HTTP request
 * (method + URL + header names + body). If anything about the request changes after the
 * user approved it, the ticket no longer matches and the request is refused.
 */
class ConsentTicket internal constructor(
    val id: String,
    val feature: String,
    internal val requestFingerprint: String,
    internal val host: String,
    internal val expiresAt: Long,
)

data class ConsentLogEntry(
    val timeMillis: Long,
    val feature: String,
    val provider: String,
    val destination: String,
    val decision: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val sendsSourceCode: Boolean,
    val redactions: Int,
)

/** Persistence for the audit log. Never receives payload contents — only hashes and sizes. */
interface ConsentLogSink { fun append(entry: ConsentLogEntry) }

class InMemoryConsentLog : ConsentLogSink {
    private val list = java.util.Collections.synchronizedList(ArrayList<ConsentLogEntry>())
    override fun append(entry: ConsentLogEntry) { list += entry }
    fun entries(): List<ConsentLogEntry> = synchronized(list) { list.toList() }
}

/** Durable audit log (JSON lines, bounded). Holds decisions, hashes and sizes — never payloads. */
class FileConsentLog(private val file: java.io.File, private val maxEntries: Int = 1000) : ConsentLogSink {
    @Synchronized override fun append(entry: ConsentLogEntry) {
        file.parentFile?.mkdirs()
        val line = dev.pocketforge.engine.common.Json.stringify(
            dev.pocketforge.engine.common.Json.obj(
                "t" to entry.timeMillis, "f" to entry.feature, "p" to entry.provider, "d" to entry.destination, "x" to entry.decision,
                "h" to entry.payloadSha256, "b" to entry.payloadBytes, "src" to entry.sendsSourceCode, "r" to entry.redactions,
            ),
        )
        file.appendText(line + "\n")
        if (file.length() > maxEntries * 600L) {
            val keep = file.readLines().takeLast(maxEntries / 2)
            dev.pocketforge.engine.storage.AtomicFileWriter.write(file, (keep.joinToString("\n") + "\n").toByteArray())
        }
    }

    @Synchronized fun entries(): List<ConsentLogEntry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
        runCatching {
            val o = dev.pocketforge.engine.common.Json.parseObject(l)
            ConsentLogEntry(o.long("t")!!, o.str("f")!!, o.str("p") ?: "", o.str("d") ?: "", o.str("x")!!, o.str("h") ?: "", o.long("b") ?: 0, o.bool("src") ?: false, o.int("r") ?: 0)
        }.getOrNull()
    }.asReversed()

    @Synchronized fun clear() { file.delete() }
}

/**
 * The one and only gate in front of the network. Every network-capable feature must obtain a
 * [ConsentTicket] here and present it to [GatedTransport]. There is no "remember my choice".
 */
class ConsentGateway(
    private val prompter: ConsentPrompter?,
    private val log: ConsentLogSink,
    private val clock: Clock = SystemClock,
    private val ticketTtlMillis: Long = 120_000,
    /** Global kill switch (Settings → "Block all network"). Checked on request AND on redeem. */
    private val networkAllowed: () -> Boolean = { true },
) {
    private val issued = ConcurrentHashMap<String, ConsentTicket>()
    private val rng = SecureRandom()

    fun request(req: ConsentRequest): ConsentTicket {
        val uri = try { URI(req.request.url) } catch (e: Exception) { throw ConsentDenied("Malformed destination URL.") }
        if (uri.scheme?.lowercase() != "https") {
            record(req, "BLOCKED_INSECURE")
            throw ConsentDenied("Only HTTPS destinations are allowed (got '${uri.scheme}').")
        }
        if (!networkAllowed()) {
            record(req, "BLOCKED_OFFLINE_MODE")
            throw ConsentDenied("Network is blocked in Settings. Nothing was sent.")
        }
        val p = prompter ?: run {
            record(req, "DENIED_NO_UI")
            throw ConsentDenied("Consent screen unavailable, so the request was not sent.")
        }
        val decision = try { p.ask(req) } catch (t: Throwable) { ConsentDecision.DENY }
        record(req, decision.name)
        if (decision != ConsentDecision.APPROVE_ONCE) throw ConsentDenied("You did not approve this request. Nothing was sent.")
        val bytes = ByteArray(16).also(rng::nextBytes)
        val t = ConsentTicket(
            id = Hashing.hex(bytes),
            feature = req.feature,
            requestFingerprint = req.request.fingerprint(),
            host = req.destination,
            expiresAt = clock.nowMillis() + ticketTtlMillis,
        )
        issued[t.id] = t
        return t
    }

    /** Consumes the ticket. Throws unless it was issued here, unused, unexpired and matches [req] exactly. */
    internal fun redeem(ticket: ConsentTicket, req: HttpRequest) {
        val stored = issued.remove(ticket.id) ?: throw ConsentDenied("Consent ticket already used or unknown.")
        if (stored !== ticket) throw ConsentDenied("Consent ticket forged.")
        if (clock.nowMillis() > stored.expiresAt) throw ConsentDenied("Consent expired; please approve again.")
        if (!networkAllowed()) throw ConsentDenied("Network was blocked after approval. Nothing was sent.")
        if (req.host != stored.host) throw ConsentDenied("Destination changed after approval.")
        if (req.fingerprint() != stored.requestFingerprint) throw ConsentDenied("Request contents changed after approval.")
    }

    private fun record(req: ConsentRequest, decision: String) {
        log.append(
            ConsentLogEntry(
                timeMillis = clock.nowMillis(),
                feature = req.feature,
                provider = req.provider,
                destination = req.destination,
                decision = decision,
                payloadSha256 = Hashing.sha256(req.request.body),
                payloadBytes = req.totalBytes,
                sendsSourceCode = req.sendsSourceCode,
                redactions = req.redactionsApplied,
            ),
        )
    }
}
