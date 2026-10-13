package dev.pocketforge.engine.consent

import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

/** Raw transport. Deliberately NOT exposed to features — they only get [GatedTransport]. */
interface Transport { fun send(req: HttpRequest): HttpResponse }

/**
 * The only network entry point features can use. It refuses to send anything without a
 * valid, matching, unused [ConsentTicket] from [ConsentGateway].
 */
class GatedTransport(private val gateway: ConsentGateway, private val transport: Transport) {
    fun send(ticket: ConsentTicket, req: HttpRequest): HttpResponse {
        gateway.redeem(ticket, req) // throws ConsentDenied before any byte leaves the device
        return transport.send(req)
    }
}

class NetworkError(message: String, cause: Throwable? = null) : PfException("net.error", message, cause)

/**
 * HTTPS-only transport on top of the platform's HttpsURLConnection (works on Android and JVM).
 * Uses the platform trust store by default. When [pinnedSha256] is given (self-hosted build
 * host with its own certificate), the leaf certificate's SHA-256 must match exactly.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
    private val maxResponseBytes: Int = 64 * 1024 * 1024,
    private val pinnedSha256: String? = null,
) : Transport {
    override fun send(req: HttpRequest): HttpResponse {
        val uri = URI(req.url)
        if (uri.scheme?.lowercase() != "https") throw NetworkError("Refusing non-HTTPS URL.")
        val conn = uri.toURL().openConnection() as HttpsURLConnection
        try {
            pinnedSha256?.let { pin ->
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(PinnedTrustManager(pin.lowercase().replace(":", ""))), null)
                conn.sslSocketFactory = ctx.socketFactory
                // Hostname is not meaningful for a self-signed LAN host; the pin is the identity.
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.instanceFollowRedirects = false // a redirect could change the approved destination
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = req.method.uppercase()
            conn.useCaches = false
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (req.body.isNotEmpty()) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(req.body.size)
                conn.outputStream.use { it.write(req.body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxResponseBytes) throw NetworkError("Response exceeded ${maxResponseBytes / 1024 / 1024} MB limit.")
                    out.write(buf, 0, n)
                }
            }
            val headers = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return HttpResponse(status, headers, out.toByteArray())
        } catch (e: PfException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError("Network request failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}

/** Accepts exactly one leaf certificate, identified by the SHA-256 of its DER encoding. */
class PinnedTrustManager(private val expectedHex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("Client auth not supported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No certificate")
        leaf.checkValidity()
        val actual = Hashing.sha256(leaf.encoded)
        if (!java.security.MessageDigest.isEqual(actual.toByteArray(), expectedHex.toByteArray())) {
            throw java.security.cert.CertificateException("Certificate fingerprint mismatch (got $actual)")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** Platform default trust manager, for completeness/testing. */
        fun platformDefault(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
