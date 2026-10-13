package dev.pocketforge.engine

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.diff.Diff
import dev.pocketforge.engine.editor.BracketMatcher
import dev.pocketforge.engine.editor.Diagnostics
import dev.pocketforge.engine.editor.EditHistory
import dev.pocketforge.engine.editor.Highlighter
import dev.pocketforge.engine.editor.Indenter
import dev.pocketforge.engine.editor.Language
import dev.pocketforge.engine.editor.TokenType
import dev.pocketforge.engine.search.Glob
import dev.pocketforge.engine.search.ProjectSearcher
import dev.pocketforge.engine.search.Replacer
import dev.pocketforge.engine.search.SearchQuery
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSearchDiffTest {
    @Test fun undoRedoCoalescesTypingByWord() {
        val h = EditHistory()
        var text = ""; var t = 0L
        for (c in "hello world") { val n = text + c; h.record(text, n, text.length, n.length, t); text = n; t += 100 }
        var s = h.undo(text)!!; assertEquals("hello", s.text)
        s = h.undo(s.text)!!; assertEquals("", s.text)
        s = h.redo(s.text)!!; assertEquals("hello", s.text)
        s = h.redo(s.text)!!; assertEquals("hello world", s.text)
        assertFalse(h.canRedo)
    }

    @Test fun undoAfterPauseIsSeparateStepAndNewEditClearsRedo() {
        val h = EditHistory()
        h.record("", "a", 0, 1, 0); h.record("a", "ab", 1, 2, 5_000)
        assertEquals("a", h.undo("ab")!!.text)
        h.record("a", "ax", 1, 2, 6_000)
        assertFalse(h.canRedo)
    }

    @Test fun highlighterTokensAndIncrementalReuse() {
        val code = "package a\n/* multi\nline */ val s = \"x\" // c\n@Test fun f() = 42\n"
        val hl = Highlighter(Language.KOTLIN)
        val spans = hl.highlight(code)
        fun typeAt(sub: String) = spans.first { code.indexOf(sub) in it.start until it.end }.type
        assertEquals(TokenType.KEYWORD, typeAt("package"))
        assertEquals(TokenType.COMMENT, typeAt("line */"))
        assertEquals(TokenType.STRING, typeAt("\"x\""))
        assertEquals(TokenType.COMMENT, typeAt("// c"))
        assertEquals(TokenType.ANNOTATION, typeAt("@Test"))
        assertEquals(TokenType.NUMBER, typeAt("42"))
        hl.highlight(code + "val y = 1\n")
        assertEquals(1, hl.lastLexedLines) // only the new line was lexed
    }

    @Test fun highlightLargeFileIsFast() {
        val big = (1..20_000).joinToString("\n") { "fun f$it(x: Int): Int { return x * $it } // c" }
        val t0 = System.nanoTime()
        Highlighter(Language.KOTLIN).highlight(big)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test fun bracketMatchingSkipsStrings() {
        val code = "f(\")\", g(1))"
        val spans = Highlighter(Language.KOTLIN).highlight(code)
        assertEquals(1 to code.length - 1, BracketMatcher.match(code, 1, spans))
    }

    @Test fun autoIndentBetweenBraces() {
        val s = Indenter.newline("fun a() {}", 9, "    ", Language.KOTLIN)
        assertEquals("fun a() {\n    \n}", s.text)
        assertEquals(14, s.cursor)
        val (t, _, _) = Indenter.shiftLines("a\nb", 0, 3, "  ", outdent = false)
        assertEquals("  a\n  b", t)
        assertEquals("a\nb", Indenter.shiftLines(t, 0, t.length, "  ", outdent = true).first)
    }

    @Test fun diagnosticsFindJsonAndBracketAndXmlErrors() {
        assertEquals(2, Diagnostics.run("{\n \"a\": }", Language.JSON).first().line)
        assertTrue(Diagnostics.run("fun a() {\n val s = \"}\"\n", Language.KOTLIN).any { it.message.contains("never closed") })
        assertTrue(Diagnostics.run("<a><b></a>", Language.XML).isNotEmpty())
        assertTrue(Diagnostics.run("<p>hi<br></p>", Language.HTML).isEmpty())
        assertTrue(Diagnostics.run("val k = \"sk-ant-api03-aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"", Language.KOTLIN).any { it.source == "secrets" })
    }

    private fun project(): File = tempDir().also { r ->
        File(r, "src").mkdirs()
        File(r, "src/A.kt").writeText("val foo = 1\nval foobar = foo + 2\n")
        File(r, "src/B.kt").writeText("// FOO here\r\nfun foo() {}\r\n")
        File(r, "build").mkdirs(); File(r, "build/gen.kt").writeText("foo")
    }

    @Test fun searchRespectsCaseWholeWordGlobsAndExcludes() {
        val r = project()
        assertEquals(5, ProjectSearcher.search(r, SearchQuery("foo")).matches.size)
        assertEquals(3, ProjectSearcher.search(r, SearchQuery("foo", wholeWord = true, caseSensitive = true)).matches.size)
        assertEquals(2, ProjectSearcher.search(r, SearchQuery("foo", include = listOf("src/B.kt"))).matches.size)
        val hit = ProjectSearcher.search(r, SearchQuery("fun foo", include = listOf("**/*.kt"))).matches.single()
        assertEquals("src/B.kt" to 2, hit.path to hit.line)
        assertEquals(1, hit.column)
    }

    @Test fun replacePreviewApplyUndoAndPreserveCrlf() {
        val r = project()
        val q = SearchQuery("foo(\\w*)", regex = true, caseSensitive = true)
        val res = ProjectSearcher.search(r, q)
        val plan = Replacer.plan(r, q, res.matches, "baz$1")
        assertEquals(setOf("baz", "bazbar"), plan.map { it.replacement }.toSet())
        val out = Replacer.apply(r, plan)
        assertEquals("val baz = 1\nval bazbar = baz + 2\n", File(r, "src/A.kt").readText())
        assertTrue(File(r, "src/B.kt").readText().contains("\r\nfun baz() {}\r\n"))
        assertTrue(Replacer.undo(r, out.undo).isEmpty())
        assertEquals("val foo = 1\nval foobar = foo + 2\n", File(r, "src/A.kt").readText())
    }

    @Test fun replaceSkipsFilesChangedAfterSearch() {
        val r = project()
        val q = SearchQuery("foo")
        val plan = Replacer.plan(r, q, ProjectSearcher.search(r, q).matches, "x")
        File(r, "src/A.kt").writeText("someone else edited this foo\n")
        val out = Replacer.apply(r, plan)
        assertEquals(listOf("src/A.kt"), out.staleFiles)
        assertEquals("someone else edited this foo\n", File(r, "src/A.kt").readText())
    }

    @Test fun catastrophicRegexIsStopped() {
        val r = tempDir(); File(r, "x.txt").writeText("a".repeat(40) + "!")
        for (bad in listOf("(a+)+$", "(\\w+\\s?)*$", "((ab)*c)+", "(x{2,})+")) {
            assertEquals("search.unsafe_regex", assertFailsWith<PfException>(bad) { ProjectSearcher.search(r, SearchQuery(bad, regex = true)) }.code)
        }
        for (ok in listOf("foo(\\w*)", "(ab)+", "[(a+)]+", "a+b*")) ProjectSearcher.search(r, SearchQuery(ok, regex = true))
    }

    @Test fun globs() {
        assertTrue(Glob.toRegex("**/*.kt").matches("a/b/C.kt"))
        assertTrue(Glob.toRegex("**/*.kt").matches("C.kt"))
        assertFalse(Glob.toRegex("src/*.kt").matches("src/a/b.kt"))
    }

    @Test fun diffApplyRoundTripsOnRandomInputs() {
        val rnd = Random(7)
        repeat(300) {
            val a = List(rnd.nextInt(0, 40)) { "l${rnd.nextInt(0, 8)}" }
            val b = a.toMutableList().apply {
                repeat(rnd.nextInt(0, 10)) {
                    when (rnd.nextInt(3)) {
                        0 -> if (isNotEmpty()) removeAt(rnd.nextInt(size))
                        1 -> add(rnd.nextInt(size + 1), "n${rnd.nextInt(0, 8)}")
                        else -> if (isNotEmpty()) set(rnd.nextInt(size), "m${rnd.nextInt(0, 8)}")
                    }
                }
            }
            val old = if (a.isEmpty()) "" else a.joinToString("\n", postfix = "\n")
            val new = if (b.isEmpty()) "" else b.joinToString("\n", postfix = "\n")
            val patch = Diff.unified(old, new, "f.txt")
            val parsed = Diff.parseUnified(patch)
            val applied = if (parsed.isEmpty()) old else Diff.apply(old, parsed.single().hunks)
            assertEquals(new, applied, "patch:\n$patch")
        }
    }

    @Test fun patchWithStaleContextIsRejected() {
        val patch = Diff.unified("a\nb\nc\n", "a\nB\nc\n", "f")
        assertFailsWith<PfException> { Diff.apply("x\ny\nz\n", Diff.parseUnified(patch).single().hunks) }
    }
}
