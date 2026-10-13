package dev.pocketforge.engine

import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.archive.ImportLimits
import dev.pocketforge.engine.archive.SafeZipImporter
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SecurityViolation
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.security.SecretRedactor
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun pathGuardRejectsTraversalAbsoluteAndControlChars() {
        val g = PathGuard(tempDir())
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "C:\\win", "a\u0000b", "..\\..\\x", "")) {
            assertFailsWith<SecurityViolation>(bad) { g.resolve(bad) }
        }
        assertEquals("a/b/c.txt", g.normalize("./a//b/./c.txt"))
    }

    @Test fun pathGuardRefusesSymlinks() {
        val root = tempDir(); val outside = tempDir()
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val e = assertFailsWith<SecurityViolation> { PathGuard(root).resolve("link/secret.txt") }
        assertEquals("path.symlink", e.code)
        assertEquals(0, PathGuard(root).walkFiles().count())
    }

    @Test fun zipSlipIsBlockedAndNothingEscapes() {
        val dest = tempDir(); val parent = dest.parentFile
        val zip = zipOf("ok.txt" to "fine".toByteArray(), "../../evil.txt" to "pwned".toByteArray())
        val e = assertFailsWith<SecurityViolation> { SafeZipImporter().import({ zip.inputStream() }, dest) }
        assertEquals("path.traversal", e.code)
        assertFalse(File(parent, "evil.txt").exists())
    }

    @Test fun absoluteZipEntryRejected() {
        val zip = zipOf("/abs.txt" to "x".toByteArray())
        assertFailsWith<SecurityViolation> { SafeZipImporter().import({ zip.inputStream() }, tempDir()) }
    }

    @Test fun zipBombStoppedByRatio() {
        val zeros = ByteArray(40 * 1024 * 1024) // compresses ~1000:1
        val zip = zipOf("bomb.bin" to zeros)
        val e = assertFailsWith<SecurityViolation> { SafeZipImporter().import({ zip.inputStream() }, tempDir()) }
        assertEquals("zip.bomb", e.code)
    }

    @Test fun zipLimitsEntryCountAndSize() {
        val many = zipOf(*(1..30).map { "f$it.txt" to "x".toByteArray() }.toTypedArray())
        assertFailsWith<SecurityViolation> { SafeZipImporter(ImportLimits(maxEntries = 10)).import({ many.inputStream() }, tempDir()) }
        val big = zipOf("big.txt" to ByteArray(5000) { (it % 251).toByte() })
        val e = assertFailsWith<SecurityViolation> { SafeZipImporter(ImportLimits(maxEntryBytes = 1000)).import({ big.inputStream() }, tempDir()) }
        assertEquals("zip.entry_too_large", e.code)
    }

    @Test fun zipCaseCollisionRejected() {
        val zip = zipOf("A.txt" to "1".toByteArray(), "a.txt" to "2".toByteArray())
        val e = assertFailsWith<SecurityViolation> { SafeZipImporter().import({ zip.inputStream() }, tempDir()) }
        assertEquals("zip.duplicate", e.code)
    }

    @Test fun zipStripsGithubTopFolderAndJunk() {
        val dest = tempDir()
        val zip = zipOf("repo-main/README.md" to "hi".toByteArray(), "repo-main/src/a.kt" to "fun a()".toByteArray(), "__MACOSX/x" to ByteArray(1))
        val r = SafeZipImporter().import({ zip.inputStream() }, dest)
        assertEquals("repo-main/", r.strippedPrefix)
        assertTrue(File(dest, "src/a.kt").isFile)
        assertEquals(2, r.filesWritten)
    }

    @Test fun notAZip() {
        assertFailsWith<PfException> { SafeZipImporter().import({ "hello".byteInputStream() }, tempDir()) }
    }

    @Test fun redactorMasksCommonSecrets() {
        val src = """
            val key = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123"
            aws = AKIAABCDEFGHIJKLMNOP
            storePassword=hunter2hunter2
            url = "https://bob:s3cr3tpass@example.com/repo"
            Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456
            -----BEGIN RSA PRIVATE KEY-----
            MIIEow
            -----END RSA PRIVATE KEY-----
            ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            path /data/user/0/dev.pocketforge.ide/files/x
        """.trimIndent()
        val r = SecretRedactor.redact(src, redactPaths = true)
        for (secret in listOf("abcdefghijklmnopqrstuvwxyz0123", "AKIAABCDEFGHIJKLMNOP", "hunter2hunter2", "s3cr3tpass", "MIIEow", "ghp_aaaa", "dev.pocketforge.ide")) {
            assertFalse(r.text.contains(secret), "leaked: $secret\n${r.text}")
        }
        assertTrue(r.text.contains("https://bob:"))
        assertTrue(r.findings.size >= 6)
    }

    @Test fun redactorLeavesOrdinaryCodeAlone() {
        val code = "fun tokenize(input: String): List<String> = input.split(' ')\nval password = getPasswordFromVault()"
        val r = SecretRedactor.redact(code)
        assertTrue(r.text.contains("tokenize"))
    }

    @Test fun exportManifestExcludesSecretsAndBuildOutputsByDefault() {
        val root = tempDir()
        fun w(p: String, s: String) = File(root, p).apply { parentFile.mkdirs(); writeText(s) }
        w("src/Main.kt", "fun main() {}")
        w("build/out.class", "x"); w("local.properties", "sdk.dir=/x"); w("release.jks", "k")
        w(".env", "TOKEN=1"); w("config.txt", "api_key = sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX")
        val m = ExportManifest.build(root).associateBy { it.path }
        assertTrue(m.getValue("src/Main.kt").include)
        for (p in listOf("build/out.class", "local.properties", "release.jks", ".env", "config.txt")) assertFalse(m.getValue(p).include, p)
    }
}
