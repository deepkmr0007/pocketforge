package dev.pocketforge.engine

import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentDenied
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.HttpRequest
import dev.pocketforge.engine.consent.HttpResponse
import dev.pocketforge.engine.consent.InMemoryConsentLog
import dev.pocketforge.engine.consent.Transport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsentTest {
    private class CountingTransport : Transport {
        var calls = 0
        override fun send(req: HttpRequest): HttpResponse { calls++; return HttpResponse(200, emptyMap(), "ok".toByteArray()) }
    }

    private fun req(body: String = "SECRET_SOURCE_CODE", url: String = "https://api.example.com/v1") = ConsentRequest(
        "AI", "Example", "test", emptyList(), emptyList(),
        HttpRequest("POST", url, mapOf("x-api-key" to "k1"), body.toByteArray()), sendsSourceCode = true,
    )

    private fun setup(decision: () -> ConsentDecision, clock: FakeClock = FakeClock(), online: () -> Boolean = { true }): Triple<ConsentGateway, GatedTransport, CountingTransport> {
        val t = CountingTransport()
        val g = ConsentGateway({ decision() }, InMemoryConsentLog(), clock, networkAllowed = online)
        return Triple(g, GatedTransport(g, t), t)
    }

    @Test fun noRequestIsSentWithoutApproval() {
        for (d in listOf(ConsentDecision.DENY, ConsentDecision.CANCEL)) {
            val (g, _, t) = setup({ d })
            assertFailsWith<ConsentDenied> { g.request(req()) }
            assertEquals(0, t.calls)
        }
    }

    @Test fun prompterCrashOrMissingUiFailsClosed() {
        val (g, _, t) = setup({ throw IllegalStateException("ui gone") })
        assertFailsWith<ConsentDenied> { g.request(req()) }
        val noUi = ConsentGateway(null, InMemoryConsentLog())
        assertFailsWith<ConsentDenied> { noUi.request(req()) }
        assertEquals(0, t.calls)
    }

    @Test fun approvalIsSingleUse() {
        val (g, gt, t) = setup({ ConsentDecision.APPROVE_ONCE })
        val r = req()
        val ticket = g.request(r)
        gt.send(ticket, r.request)
        assertFailsWith<ConsentDenied> { gt.send(ticket, r.request) }
        assertEquals(1, t.calls)
    }

    @Test fun ticketIsBoundToExactPayloadHeadersAndHost() {
        val (g, gt, t) = setup({ ConsentDecision.APPROVE_ONCE })
        val r = req()
        assertFailsWith<ConsentDenied> { gt.send(g.request(r), r.request.copy(body = "MORE_CODE".toByteArray())) }
        assertFailsWith<ConsentDenied> { gt.send(g.request(r), r.request.copy(url = "https://evil.example.net/v1")) }
        assertFailsWith<ConsentDenied> { gt.send(g.request(r), r.request.copy(headers = mapOf("x-api-key" to "other"))) }
        assertEquals(0, t.calls)
    }

    @Test fun ticketExpires() {
        val clock = FakeClock()
        val (g, gt, t) = setup({ ConsentDecision.APPROVE_ONCE }, clock)
        val r = req()
        val ticket = g.request(r)
        clock.now += 10 * 60_000
        assertFailsWith<ConsentDenied> { gt.send(ticket, r.request) }
        assertEquals(0, t.calls)
    }

    @Test fun offlineModeBlocksEvenAfterApproval() {
        var online = true
        val (g, gt, t) = setup({ ConsentDecision.APPROVE_ONCE }, online = { online })
        val r = req()
        val ticket = g.request(r)
        online = false
        assertFailsWith<ConsentDenied> { gt.send(ticket, r.request) }
        assertFailsWith<ConsentDenied> { g.request(r) }
        assertEquals(0, t.calls)
    }

    @Test fun plainHttpIsRefusedWithoutPrompting() {
        var asked = false
        val (g, _, _) = setup({ asked = true; ConsentDecision.APPROVE_ONCE })
        assertFailsWith<ConsentDenied> { g.request(req(url = "http://api.example.com/v1")) }
        assertFalse(asked)
    }

    @Test fun auditLogNeverContainsPayloadOrKeys() {
        val log = InMemoryConsentLog()
        val g = ConsentGateway({ ConsentDecision.DENY }, log)
        runCatching { g.request(req()) }
        val entry = log.entries().single()
        val dump = entry.toString()
        assertFalse(dump.contains("SECRET_SOURCE_CODE"))
        assertFalse(dump.contains("k1"))
        assertEquals("DENY", entry.decision)
        assertTrue(entry.payloadSha256.length == 64)
    }

    @Test fun previousApprovalIsNotBlanketApproval() {
        var answers = ArrayDeque(listOf(ConsentDecision.APPROVE_ONCE, ConsentDecision.DENY))
        val (g, gt, t) = setup({ answers.removeFirst() })
        val r = req()
        gt.send(g.request(r), r.request)
        assertFailsWith<ConsentDenied> { g.request(r) }
        assertEquals(1, t.calls)
    }
}
