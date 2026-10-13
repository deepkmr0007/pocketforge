package dev.pocketforge.engine

import dev.pocketforge.engine.ai.AiAction
import dev.pocketforge.engine.ai.AiTask
import dev.pocketforge.engine.ai.AnthropicProvider
import dev.pocketforge.engine.ai.ContextBuilder
import dev.pocketforge.engine.ai.ContextFile
import dev.pocketforge.engine.ai.OpenAiCompatibleProvider
import dev.pocketforge.engine.ai.consentFor
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentDenied
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.consent.HttpRequest
import dev.pocketforge.engine.consent.InMemoryConsentLog
import dev.pocketforge.engine.consent.Loopback
import dev.pocketforge.engine.consent.NetworkError
import dev.pocketforge.engine.consent.UrlConnectionTransport
import dev.pocketforge.engine.editor.EditHistory
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorAndLocalAiTest {
    /** Simulates the editor: apply edits to a StringBuilder and record them incrementally. */
    private class Doc(var sb: StringBuilder = StringBuilder()) {
        val h = EditHistory()
        var t = 0L
        fun type(s: String) { for (c in s) { val at = sb.length; sb.append(c); h.recordEdit(at, "", c.toString(), at, at + 1, t); t += 50 } }
        fun backspace() { val at = sb.length - 1; val r = sb.substring(at); sb.deleteCharAt(at); h.recordEdit(at, r, "", at + 1, at, t); t += 50 }
        fun undo(): Boolean { val e = h.undoEdit(sb) ?: return false; sb.replace(e.start, e.start + e.inserted.length, e.removed); return true }
        fun redo(): Boolean { val e = h.redoEdit(sb) ?: return false; sb.replace(e.start, e.start + e.removed.length, e.inserted); return true }
    }

    @Test fun incrementalHistoryUndoesWordByWordAndRedoes() {
        val d = Doc()
        d.type("hello world")
        assertTrue(d.undo()); assertEquals("hello", d.sb.toString())
        assertTrue(d.undo()); assertEquals("", d.sb.toString())
        assertFalse(d.undo())
        assertTrue(d.redo()); assertTrue(d.redo()); assertEquals("hello world", d.sb.toString())
        d.backspace(); d.backspace()
        assertEquals("hello wor", d.sb.toString())
        assertTrue(d.undo()); assertEquals("hello world", d.sb.toString())
    }

    @Test fun historyOutOfSyncIsDroppedNotApplied() {
        val d = Doc(); d.type("abc")
        d.sb.setLength(0); d.sb.append("zzz") // document changed behind the history's back (e.g. reload)
        assertNull(d.h.undoEdit(d.sb))
        assertEquals("zzz", d.sb.toString())
        assertFalse(d.h.canUndo)
    }

    @Test fun loopbackHttpIsTheOnlyPlainHttpAllowed() {
        assertTrue(Loopback.isLoopbackHost("127.0.0.1")); assertTrue(Loopback.isLoopbackHost("localhost")); assertTrue(Loopback.isLoopbackHost("[::1]"))
        assertFalse(Loopback.isLoopbackHost("192.168.1.5")); assertFalse(Loopback.isLoopbackHost("127.0.0.1.evil.com")); assertFalse(Loopback.isLoopbackHost(null))
        val g = ConsentGateway({ ConsentDecision.APPROVE_ONCE }, InMemoryConsentLog())
        fun req(url: String) = ConsentRequest("t", "p", "x", emptyList(), emptyList(), HttpRequest("POST", url), false)
        assertNotNull(g.request(req("http://127.0.0.1:8080/v1/chat/completions")))
        assertFailsWith<ConsentDenied> { g.request(req("http://192.168.1.5:8080/v1")) }
        assertFailsWith<ConsentDenied> { g.request(req("http://localhost.example.com/v1")) }
        assertFailsWith<NetworkError> { UrlConnectionTransport().send(HttpRequest("GET", "http://10.0.0.1/x")) }
    }

    @Test fun localModelServerIsLabelledLocalOnlyForLoopback() {
        val ctx = ContextBuilder.build(listOf(ContextFile("A.kt", "fun a() = 1\n")))
        val task = AiTask(AiAction.EXPLAIN, "why?", ctx)
        val local = OpenAiCompatibleProvider("http://127.0.0.1:11434/v1", null, "qwen2.5-coder")
        assertTrue(local.isLocal)
        assertTrue(local.displayName.startsWith("On-device"))
        assertTrue(consentFor(local, task).purpose.contains("THIS phone"))
        val remote = OpenAiCompatibleProvider("https://example.com/v1", "k", "m")
        assertFalse(remote.isLocal)
        assertFalse(AnthropicProvider("k", "m").isLocal)
        assertTrue(consentFor(remote, task).risks.first().contains("leaves this phone"))
    }

    @Test fun testRunsAreRecordedByExitCodeOnly() {
        val w = tempDir()
        val svc = BuildService(w, BuildHistory(File(w, "h.json")), FakeClock())
        assertEquals(BuildStatus.SUCCESS, svc.recordTestRun("p", "termux", "python -m unittest", 0, "OK", 0).status)
        assertEquals(BuildStatus.FAILED, svc.recordTestRun("p", "termux", "python -m unittest", 0, "FAILED (failures=1)\npassword=hunter22", 1).status)
        val none = svc.recordTestRun("p", "termux", "x", 0, "", null)
        assertEquals(BuildStatus.ERROR, none.status)
        val log = svc.logText(BuildHistory(File(w, "h.json")).list()[1])!!
        assertFalse(log.contains("hunter22"))
    }
}
