package dev.pocketforge.engine.selftest

import dev.pocketforge.engine.ai.ResponseParser
import dev.pocketforge.engine.archive.ImportLimits
import dev.pocketforge.engine.archive.SafeZipImporter
import dev.pocketforge.engine.build.ApkInspector
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentDenied
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.HttpRequest
import dev.pocketforge.engine.consent.HttpResponse
import dev.pocketforge.engine.consent.InMemoryConsentLog
import dev.pocketforge.engine.consent.Transport
import dev.pocketforge.engine.diff.Diff
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.security.SecretRedactor
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.LineEnding
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.engine.storage.TextEncoding
import dev.pocketforge.engine.terminal.CommandAnalyzer
import dev.pocketforge.engine.terminal.TerminalPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs the app's security and data-safety invariants ON THE DEVICE, in a scratch folder, with
 * no network and no access to user projects. Lets the user (and us) verify that the shipped
 * build behaves as documented on their exact Android version — not just on the developer's machine.
 */
object SelfTest {
    data class Check(val name: String, val passed: Boolean, val detail: String, val skipped: Boolean = false)

    fun run(scratch: File): List<Check> {
        scratch.deleteRecursively()
        scratch.mkdirs()
        val out = ArrayList<Check>()
        fun check(name: String, block: () -> String) {
            out += try { Check(name, true, block()) } catch (e: Skip) { Check(name, true, e.message ?: "skipped", skipped = true) }
            catch (e: Throwable) { Check(name, false, "${e.javaClass.simpleName}: ${e.message}") }
        }
        try {
            check("Consent: nothing is sent when no consent screen is available") {
                val t = CountingTransport()
                val gw = ConsentGateway(null, InMemoryConsentLog())
                val req = sample()
                expect<ConsentDenied> { GatedTransport(gw, t).send(gw.request(req), req.request) }
                require(t.calls == 0) { "transport was called ${t.calls} times" }
                "denied, 0 bytes sent"
            }
            check("Consent: a denial sends nothing") {
                val t = CountingTransport()
                val gw = ConsentGateway({ ConsentDecision.DENY }, InMemoryConsentLog())
                val req = sample()
                expect<ConsentDenied> { GatedTransport(gw, t).send(gw.request(req), req.request) }
                require(t.calls == 0)
                "denied, 0 bytes sent"
            }
            check("Consent: one approval = exactly one request") {
                val t = CountingTransport()
                val gw = ConsentGateway({ ConsentDecision.APPROVE_ONCE }, InMemoryConsentLog())
                val req = sample()
                val ticket = gw.request(req)
                val gated = GatedTransport(gw, t)
                gated.send(ticket, req.request)
                expect<ConsentDenied> { gated.send(ticket, req.request) }
                require(t.calls == 1) { "expected 1 call, got ${t.calls}" }
                "first send allowed, reuse refused"
            }
            check("Consent: changing the payload after approval is refused") {
                val t = CountingTransport()
                val gw = ConsentGateway({ ConsentDecision.APPROVE_ONCE }, InMemoryConsentLog())
                val req = sample()
                val ticket = gw.request(req)
                expect<ConsentDenied> { GatedTransport(gw, t).send(ticket, req.request.copy(body = "tampered".toByteArray())) }
                require(t.calls == 0)
                "tampered request refused"
            }
            check("Consent: plain HTTP and offline mode are blocked") {
                val gw = ConsentGateway({ ConsentDecision.APPROVE_ONCE }, InMemoryConsentLog())
                expect<ConsentDenied> { gw.request(sample(url = "http://example.invalid/x")) }
                val off = ConsentGateway({ ConsentDecision.APPROVE_ONCE }, InMemoryConsentLog(), networkAllowed = { false })
                expect<ConsentDenied> { off.request(sample()) }
                "http:// refused, offline switch honoured"
            }
            check("ZIP import: path traversal is blocked and nothing escapes") {
                val dest = File(scratch, "zipslip/dest").apply { mkdirs() }
                val zip = zipOf("ok.txt" to "a".toByteArray(), "../../escaped.txt" to "x".toByteArray())
                expect<PfException> { SafeZipImporter().import({ zip.inputStream() }, dest) }
                require(!File(scratch, "escaped.txt").exists() && !File(scratch, "zipslip/escaped.txt").exists())
                "archive rejected"
            }
            check("ZIP import: compression bombs are stopped") {
                val dest = File(scratch, "bomb").apply { mkdirs() }
                val zip = zipOf("big.txt" to ByteArray(4 * 1024 * 1024))
                expect<PfException> { SafeZipImporter(ImportLimits(maxCompressionRatio = 20, ratioGraceBytes = 64 * 1024)).import({ zip.inputStream() }, dest) }
                "ratio limit enforced"
            }
            check("Paths: symbolic links are never followed") {
                val root = File(scratch, "links").apply { mkdirs() }
                val outside = File(scratch, "outside.txt").apply { writeText("secret") }
                try { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) } catch (e: Exception) { throw Skip("this storage does not allow symlinks (${e.javaClass.simpleName})") }
                expect<PfException> { PathGuard(root).resolve("link") }
                "symlink refused"
            }
            check("Secrets: common key formats are redacted") {
                val r = SecretRedactor.redact("key = \"sk-ant-api03-abcdefghijklmnopqrstuvwx\"\npassword=hunter2hunter2\n-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----")
                require(!r.text.contains("abcdefghijklmnop") && !r.text.contains("hunter2hunter2") && !r.text.contains("AAA")) { r.text }
                "${r.redactedCount} secrets masked"
            }
            check("Files: atomic save replaces content and leaves no temp files") {
                val f = File(scratch, "atomic/a.txt")
                AtomicFileWriter.write(f, "one".toByteArray())
                AtomicFileWriter.write(f, "two".toByteArray())
                require(f.readText() == "two")
                require(f.parentFile!!.listFiles()!!.size == 1) { "temp files left behind" }
                "ok"
            }
            check("Files: encodings and line endings round-trip byte-for-byte") {
                val original = TextCodec.encode("héllo\nwörld\n", TextEncoding.UTF16LE, LineEnding.CRLF)
                val d = TextCodec.decode(original)
                require(d.encoding == TextEncoding.UTF16LE && d.lineEnding == LineEnding.CRLF)
                require(TextCodec.encode(d.text, d.encoding, d.lineEnding).contentEquals(original))
                "UTF-16 LE + CRLF preserved"
            }
            check("Recovery: drafts survive restart; corrupt drafts are skipped") {
                val dir = File(scratch, "journal")
                RecoveryJournal(dir).saveDraft("p", "a.kt", "draft text", null)
                File(dir, "garbage.draft").writeText("{\"sha\":\"0\"}\nbroken")
                val drafts = RecoveryJournal(dir).drafts()
                require(drafts.size == 1 && drafts[0].text == "draft text")
                "1 draft restored, 1 corrupt draft ignored"
            }
            check("Diff: applying all hunks reproduces the new text") {
                val a = "a\nb\nc\nd\ne\n"; val b = "a\nB\nc\nd\ne\nf\n"
                val applied = Diff.apply(a, Diff.hunks(Diff.diff(Diff.lines(a), Diff.lines(b))))
                require(applied == b) { "got: $applied" }
                "ok"
            }
            check("AI: suggestions are proposals only; escaping paths are rejected") {
                val root = File(scratch, "ai").apply { mkdirs() }
                File(root, "A.kt").writeText("fun a() = 1\n")
                val answer = "x\n```pocketforge-file path=\"A.kt\"\nfun a() = 2\n```\n```pocketforge-file path=\"../evil.kt\"\nboom\n```\n```bash\nrm -rf /\n```"
                val review = ResponseParser.parse(answer, PathGuard(root)) { p -> File(root, p).takeIf { it.isFile }?.readText() }
                require(review.proposals.size == 1 && review.rejected.size == 1)
                require(File(root, "A.kt").readText() == "fun a() = 1\n") { "file was modified" }
                require(!File(scratch, "evil.kt").exists())
                "1 proposal, 1 rejected path, ${review.commands.size} command shown (never run)"
            }
            check("Terminal: risky and out-of-project commands need confirmation") {
                val ws = File(scratch, "ws").apply { mkdirs() }
                val ro = CommandAnalyzer.analyze("ls -la", ws, TerminalPolicy())
                val del = CommandAnalyzer.analyze("rm -rf src", ws, TerminalPolicy())
                val outside = CommandAnalyzer.analyze("cat /data/system/packages.xml", ws, TerminalPolicy())
                val net = CommandAnalyzer.analyze("curl https://example.com", ws, TerminalPolicy(allowNetworkCommands = false))
                require(!ro.needsConfirmation && del.needsConfirmation && outside.needsConfirmation && outside.outsidePaths.isNotEmpty())
                require(net.blockedReason != null)
                "read-only runs directly; delete/outside paths ask; network blocked by default"
            }
            check("Builds: a non-APK can never be reported as a build success") {
                val f = File(scratch, "fake.apk").apply { writeBytes(zipOf("hello.txt" to "hi".toByteArray())) }
                expect<PfException> { ApkInspector.inspect(f) }
                "fake APK rejected"
            }
        } finally {
            scratch.deleteRecursively()
        }
        return out
    }

    private class Skip(message: String) : Exception(message)

    private class CountingTransport : Transport {
        var calls = 0
        override fun send(req: HttpRequest): HttpResponse { calls++; return HttpResponse(200, emptyMap(), ByteArray(0)) }
    }

    private fun sample(url: String = "https://example.invalid/v1") = ConsentRequest(
        feature = "Self-test", provider = "Nobody (no network is used)", purpose = "Check the consent gate", risks = emptyList(),
        dataItems = emptyList(), request = HttpRequest("POST", url, mapOf("content-type" to "text/plain"), "hello".toByteArray()), sendsSourceCode = false,
    )

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is T) return; throw IllegalStateException("expected ${T::class.java.simpleName}, got ${e.javaClass.simpleName}: ${e.message}") }
        throw IllegalStateException("expected ${T::class.java.simpleName}, but nothing was thrown")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }
}
