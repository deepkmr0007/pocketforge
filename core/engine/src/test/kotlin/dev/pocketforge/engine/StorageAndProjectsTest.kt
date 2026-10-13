package dev.pocketforge.engine

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.projects.PackageNames
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.ProjectRegistry
import dev.pocketforge.engine.projects.TemplateInput
import dev.pocketforge.engine.projects.Templates
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.snapshots.SnapshotStore
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.engine.storage.LineEnding
import dev.pocketforge.engine.storage.RecoveryJournal
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.engine.storage.TextEncoding
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageAndProjectsTest {
    @Test fun encodingsAndLineEndingsRoundTripByteForByte() {
        val samples = listOf(
            "héllo\r\nwörld\r\n".toByteArray(Charsets.UTF_8),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom\nline\n".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "utf16 ✓\n".toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x0A), // latin-1 "café\n" (invalid UTF-8)
            "old mac\rlines\r".toByteArray(),
        )
        for (b in samples) {
            val d = TextCodec.decode(b)
            assertFalse(d.text.contains('\r'))
            assertContentEquals(b, TextCodec.encode(d.text, d.encoding, d.lineEnding))
        }
        assertEquals(TextEncoding.LATIN1, TextCodec.decode(samples[3]).encoding)
        assertEquals(LineEnding.CRLF, TextCodec.decode(samples[0]).lineEnding)
        assertTrue(TextCodec.looksBinary(byteArrayOf(1, 0, 2)))
    }

    @Test fun atomicWriteReplacesAndLeavesNoTempFiles() {
        val d = tempDir(); val f = File(d, "a.txt")
        AtomicFileWriter.write(f, "one".toByteArray())
        AtomicFileWriter.write(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertEquals(listOf("a.txt"), d.list()!!.toList())
    }

    @Test fun recoveryJournalSurvivesRestartAndSkipsCorruptDrafts() {
        val dir = tempDir()
        RecoveryJournal(dir).saveDraft("p1", "src/A.kt", "unsaved work", "abc")
        RecoveryJournal(dir).saveDraft("p1", "src/B.kt", "other", null)
        // Simulate a torn write of B's draft.
        dir.listFiles()!!.first { it.readText().contains("other") }.appendText("garbage")
        val drafts = RecoveryJournal(dir).drafts() // "after process death"
        assertEquals(1, drafts.size)
        assertEquals("unsaved work", drafts[0].text)
        RecoveryJournal(dir).clear("p1", "src/A.kt")
        assertTrue(RecoveryJournal(dir).drafts().isEmpty())
    }

    @Test fun externalModificationIsDetectedAndNotOverwritten() {
        val root = tempDir()
        File(root, "a.txt").writeText("v1\n")
        val ws = Workspace(root)
        val doc = ws.open("a.txt")
        File(root, "a.txt").writeText("changed elsewhere\n")
        File(root, "a.txt").setLastModified(System.currentTimeMillis() + 5000)
        val r = ws.save(doc, "my edit\n")
        assertIs<Workspace.SaveResult.Conflict>(r)
        assertEquals(ExternalChange.MODIFIED, r.change)
        assertEquals("changed elsewhere\n", File(root, "a.txt").readText())
        assertIs<Workspace.SaveResult.Saved>(ws.save(doc, "my edit\n", force = true))
        assertEquals("my edit\n", File(root, "a.txt").readText())
    }

    @Test fun largeFilesOpenReadOnlyAndBinaryIsRefused() {
        val root = tempDir()
        File(root, "big.log").writeText("x".repeat(3 * 1024 * 1024))
        File(root, "img.png").writeBytes(byteArrayOf(-119, 80, 78, 71, 0, 0, 0))
        val ws = Workspace(root, LargeFilePolicy())
        val doc = ws.open("big.log")
        assertTrue(doc.readOnly)
        assertEquals(256 * 1024, doc.decoded.text.length)
        assertFailsWith<PfException> { ws.save(doc, "x") }
        assertEquals("file.binary", assertFailsWith<PfException> { ws.open("img.png") }.code)
    }

    @Test fun packageNameValidation() {
        assertNull(PackageNames.validate("com.example.app"))
        for (bad in listOf("app", "com..x", "com.1abc", "com.example.class", "com.ex-ample", ".com.x")) assertNotNull(PackageNames.validate(bad), bad)
        assertEquals("com.example.mycoolapp", PackageNames.suggestFrom("My Cool App!"))
    }

    @Test fun everyTemplateCreatesRealFilesWithCorrectPackage() {
        val pm = ProjectManager(tempDir(), FakeClock())
        for (t in Templates.all) {
            val p = pm.create(t.id, TemplateInput("Demo ${t.id}", "com.example.demo", license = dev.pocketforge.engine.projects.License.MIT, author = "Me"))
            val dir = pm.dirOf(p.id)
            assertTrue(File(dir, "README.md").isFile, t.id)
            assertTrue(File(dir, "LICENSE").readText().contains("MIT License"))
            if (t.id == "android-compose") {
                val main = File(dir, "app/src/main/java/com/example/demo/MainActivity.kt").readText()
                assertTrue(main.startsWith("package com.example.demo\n"))
                assertTrue(main.contains("\"Hello from \$name!\""))
                assertTrue(File(dir, "app/build.gradle.kts").readText().contains("applicationId = \"com.example.demo\""))
                assertTrue(File(dir, "app/src/main/AndroidManifest.xml").readText().contains("android:exported=\"true\""))
            }
        }
        assertEquals(Templates.all.size, pm.list().size)
        assertEquals("project.duplicate", assertFailsWith<PfException> { pm.create("empty", TemplateInput("demo EMPTY", "")) }.code)
        assertEquals("project.bad_package", assertFailsWith<PfException> { pm.create("kotlin-jvm", TemplateInput("X", "bad")) }.code)
    }

    @Test fun deleteGoesToTrashAndCanBeRestored() {
        val pm = ProjectManager(tempDir(), FakeClock())
        val p = pm.create("web", TemplateInput("Site", ""))
        val e = pm.moveToTrash(p.id, "index.html")
        assertFalse(File(pm.dirOf(p.id), "index.html").exists())
        pm.restore(e.id)
        assertTrue(File(pm.dirOf(p.id), "index.html").exists())
        val pe = pm.moveProjectToTrash(p.id)
        assertTrue(pm.list().isEmpty())
        pm.restore(pe.id)
        assertEquals("Site", pm.list().single().name)
    }

    @Test fun registryMigratesV1AndRefusesFutureVersions() {
        val f = File(tempDir(), "projects.json")
        f.writeText("""{"projects":[{"id":"x","name":"Old","templateId":"empty","opened":42}]}""")
        val p = ProjectRegistry(f).load().single()
        assertEquals(42, p.lastOpenedAt)
        assertEquals("Text", p.language)
        f.writeText("""{"schemaVersion":99,"projects":[]}""")
        assertEquals("meta.future_version", assertFailsWith<PfException> { ProjectRegistry(f).load() }.code)
    }

    @Test fun snapshotsDedupeRestoreAndRestoreIsUndoable() {
        val root = tempDir(); val store = SnapshotStore(tempDir(), FakeClock())
        File(root, "a.txt").writeText("A1"); File(root, "b.txt").writeText("B1")
        File(root, "build").mkdirs(); File(root, "build/x.o").writeText("ignored")
        val s1 = store.create(root, "first")
        assertEquals(setOf("a.txt", "b.txt"), s1.files.map { it.path }.toSet())
        File(root, "a.txt").writeText("A2"); File(root, "b.txt").delete(); File(root, "c.txt").writeText("C")
        val d = store.diff(s1.id, root)
        assertEquals(listOf("c.txt"), d.added); assertEquals(listOf("b.txt"), d.removed); assertEquals(listOf("a.txt"), d.modified)
        val safety = store.restore(s1.id, root)
        assertEquals("A1", File(root, "a.txt").readText()); assertEquals("B1", File(root, "b.txt").readText()); assertFalse(File(root, "c.txt").exists())
        store.restore(safety, root)
        assertEquals("A2", File(root, "a.txt").readText()); assertTrue(File(root, "c.txt").exists())
        store.delete(s1.id)
        assertTrue(store.list().none { it.id == s1.id })
    }
}
