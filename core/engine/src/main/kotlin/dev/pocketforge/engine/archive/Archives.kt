package dev.pocketforge.engine.archive

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SecurityViolation
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.security.SecretRedactor
import dev.pocketforge.engine.storage.TextCodec
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportLimits(
    val maxEntries: Int = 20_000,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxEntryBytes: Long = 128L * 1024 * 1024,
    /** Global expansion ratio allowed once more than [ratioGraceBytes] have been written. */
    val maxCompressionRatio: Int = 100,
    val ratioGraceBytes: Long = 8L * 1024 * 1024,
)

data class ImportReport(
    val filesWritten: Int,
    val bytesWritten: Long,
    val skipped: List<String>,
    val strippedPrefix: String?,
)

/**
 * Streaming ZIP importer hardened against path traversal ("zip slip"), absolute paths,
 * archive bombs (entry count, per-entry size, total size, expansion ratio), and duplicate or
 * case-colliding entries. It never creates symbolic links: every entry becomes a regular file.
 * Two passes: pass 1 validates names only; pass 2 extracts.
 */
class SafeZipImporter(
    private val limits: ImportLimits = ImportLimits(),
    /** Seam for tests that reproduce platform ZIP behaviour (e.g. Android 14's own path check). */
    internal val newZip: (InputStream) -> ZipInputStream = { ZipInputStream(it) },
) {

    fun import(open: () -> InputStream, destination: File): ImportReport {
        val names = scanNames(open())
        val prefix = commonTopFolder(names)
        val guard = PathGuard(destination)
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        var files = 0
        var total = 0L
        val counting = CountingInputStream(open())
        newZip(counting).use { zin ->
            var entries = 0
            while (true) {
                val e: ZipEntry = nextEntry(zin) ?: break
                if (++entries > limits.maxEntries) throw SecurityViolation("zip.too_many_entries", "Archive has more than ${limits.maxEntries} entries.")
                var name = e.name.replace('\\', '/')
                if (prefix != null) name = name.removePrefix(prefix)
                if (name.isEmpty() || isJunk(name)) { if (name.isNotEmpty()) skipped += e.name; continue }
                val rel = guard.normalize(name) // throws on traversal/absolute/control chars
                val key = rel.lowercase()
                if (e.isDirectory) { guard.resolve(rel).mkdirs(); continue }
                if (!seen.add(key)) throw SecurityViolation("zip.duplicate", "Archive contains duplicate or case-colliding entries: $rel")
                val out = guard.resolve(rel)
                if (out.exists()) throw SecurityViolation("zip.overwrite", "Refusing to overwrite existing file $rel.")
                out.parentFile?.mkdirs()
                var entryBytes = 0L
                out.outputStream().buffered().use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        entryBytes += n; total += n
                        if (entryBytes > limits.maxEntryBytes) throw SecurityViolation("zip.entry_too_large", "Entry $rel exceeds ${humanBytes(limits.maxEntryBytes)}.")
                        if (total > limits.maxTotalBytes) throw SecurityViolation("zip.too_large", "Archive expands beyond ${humanBytes(limits.maxTotalBytes)}.")
                        if (total > limits.ratioGraceBytes && counting.count > 0 && total / counting.count > limits.maxCompressionRatio) {
                            throw SecurityViolation("zip.bomb", "Archive expands suspiciously (over ${limits.maxCompressionRatio}:1). Import stopped.")
                        }
                        os.write(buf, 0, n)
                    }
                }
                files++
            }
        }
        if (files == 0) throw PfException("zip.empty", "The archive contains no importable files.")
        return ImportReport(files, total, skipped, prefix)
    }

    /** Validates entry names without extracting anything. */
    fun scanNames(input: InputStream): List<String> {
        val names = ArrayList<String>()
        newZip(input).use { zin ->
            while (true) {
                val e = nextEntry(zin) ?: break
                if (names.size >= limits.maxEntries) throw SecurityViolation("zip.too_many_entries", "Archive has more than ${limits.maxEntries} entries.")
                names += e.name.replace('\\', '/')
            }
        }
        if (names.isEmpty()) throw PfException("zip.invalid", "This file is not a ZIP archive or it is empty.")
        return names
    }

    /**
     * Android 14+ rejects "../" and absolute entry names inside ZipInputStream itself (ZipException
     * "Invalid zip entry path"), before our own PathGuard sees them. Same outcome, but it must reach
     * the user as the same clear security message on every Android version, not a raw ZipException.
     */
    private fun nextEntry(zin: ZipInputStream): ZipEntry? = try {
        zin.nextEntry
    } catch (e: java.util.zip.ZipException) {
        if (e.message?.contains("Invalid zip entry path", ignoreCase = true) == true) {
            throw SecurityViolation("zip.path_traversal", "Archive contains an entry that points outside the project folder; nothing was imported.")
        }
        throw PfException("zip.invalid", "This ZIP archive is damaged or not supported: ${e.message}")
    }

    private fun isJunk(name: String) = name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name == ".DS_Store" || name.endsWith("Thumbs.db")

    /** GitHub-style archives wrap everything in one folder ("repo-main/"); strip it. */
    internal fun commonTopFolder(names: List<String>): String? {
        val relevant = names.filterNot { isJunk(it) }
        val first = relevant.firstOrNull() ?: return null
        val top = first.substringBefore('/', "")
        if (top.isEmpty() || top == "..") return null
        val prefix = "$top/"
        return if (relevant.all { it == prefix || it.startsWith(prefix) } && relevant.any { it.length > prefix.length }) prefix else null
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}

data class ManifestEntry(val path: String, val sizeBytes: Long, val include: Boolean, val reason: String?)

/**
 * Builds the editable export manifest. Build outputs, caches, IDE state, signing material,
 * env files and files that *look* like they contain secrets are excluded by default and the
 * reason is shown, so the user can override deliberately.
 */
object ExportManifest {
    private val excludedDirs = listOf("build/", ".gradle/", ".idea/", ".cxx/", ".externalNativeBuild/", "node_modules/", "__pycache__/", ".venv/", ".pocketforge/")
    private val excludedNames = mapOf(
        "local.properties" to "machine-specific SDK path",
        "keystore.properties" to "signing credentials",
        "google-services.json" to "may contain API keys",
        ".DS_Store" to "OS metadata",
    )
    private val excludedExt = mapOf(
        "jks" to "signing keystore", "keystore" to "signing keystore", "p12" to "certificate/private key",
        "pem" to "certificate/private key", "key" to "private key", "apk" to "build artifact", "aab" to "build artifact",
        "class" to "compiled output", "pyc" to "compiled output",
    )

    fun build(root: File, scanSecrets: Boolean = true, maxScanBytes: Long = 1024 * 1024): List<ManifestEntry> {
        val guard = PathGuard(root)
        return guard.walkFiles().map { f ->
            val rel = guard.relativize(f)
            val name = f.name
            val reason = when {
                excludedDirs.any { rel.startsWith(it) || rel.contains("/$it") } -> "build output / cache"
                name.startsWith(".env") -> "environment secrets"
                excludedNames.containsKey(name) -> excludedNames[name]
                excludedExt.containsKey(f.extension.lowercase()) -> excludedExt[f.extension.lowercase()]
                scanSecrets && f.length() <= maxScanBytes && looksLikeSecretFile(f) -> "contains something that looks like a secret"
                else -> null
            }
            ManifestEntry(rel, f.length(), reason == null, reason)
        }.toList()
    }

    private fun looksLikeSecretFile(f: File): Boolean {
        val bytes = f.readBytes()
        if (TextCodec.looksBinary(bytes)) return false
        return SecretRedactor.containsLikelySecret(TextCodec.decode(bytes).text)
    }

    /** Writes only the entries marked include=true. Returns the number of files written. */
    fun writeZip(root: File, entries: List<ManifestEntry>, out: OutputStream, extras: Map<String, ByteArray> = emptyMap()): Int {
        val guard = PathGuard(root)
        var n = 0
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in extras) {
                zos.putNextEntry(ZipEntry(guard.normalize(name)))
                zos.write(bytes)
                zos.closeEntry()
                n++
            }
            for (e in entries.filter { it.include }.sortedBy { it.path }) {
                val f = guard.resolve(e.path)
                if (!f.isFile) continue
                val ze = ZipEntry(e.path).apply { time = f.lastModified() }
                zos.putNextEntry(ze)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                n++
            }
        }
        return n
    }
}
