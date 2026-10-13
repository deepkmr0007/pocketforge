package dev.pocketforge.engine.storage

import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.humanBytes
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class LineEnding(val seq: String, val label: String) { LF("\n", "LF"), CRLF("\r\n", "CRLF"), CR("\r", "CR") }

enum class TextEncoding(val charset: Charset, val bom: ByteArray, val label: String) {
    UTF8(Charsets.UTF_8, ByteArray(0), "UTF-8"),
    UTF8_BOM(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "UTF-8 BOM"),
    UTF16LE(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16 LE"),
    UTF16BE(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()), "UTF-16 BE"),
    /** Lossless fallback for non-UTF-8 single-byte files: every byte maps to one char and back. */
    LATIN1(Charsets.ISO_8859_1, ByteArray(0), "ISO-8859-1"),
}

/** Editor-facing text: always `\n`-normalised, plus everything needed to write it back faithfully. */
data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val mixedLineEndings: Boolean,
)

object TextCodec {
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        if (n >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return false
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    fun decode(bytes: ByteArray): DecodedText {
        val enc = when {
            startsWith(bytes, TextEncoding.UTF8_BOM.bom) -> TextEncoding.UTF8_BOM
            startsWith(bytes, TextEncoding.UTF16LE.bom) -> TextEncoding.UTF16LE
            startsWith(bytes, TextEncoding.UTF16BE.bom) -> TextEncoding.UTF16BE
            isStrictUtf8(bytes) -> TextEncoding.UTF8
            else -> TextEncoding.LATIN1
        }
        val raw = String(bytes, enc.bom.size, bytes.size - enc.bom.size, enc.charset)
        var crlf = 0; var lf = 0; var cr = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\r') { if (i + 1 < raw.length && raw[i + 1] == '\n') { crlf++; i++ } else cr++ }
            else if (c == '\n') lf++
            i++
        }
        val dominant = when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf && cr > crlf -> LineEnding.CR
            else -> LineEnding.LF
        }
        val kinds = listOf(crlf, lf, cr).count { it > 0 }
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        return DecodedText(normalized, enc, dominant, kinds > 1)
    }

    fun encode(text: String, encoding: TextEncoding, lineEnding: LineEnding): ByteArray {
        val withEndings = if (lineEnding == LineEnding.LF) text else text.replace("\n", lineEnding.seq)
        if (encoding == TextEncoding.LATIN1 && withEndings.any { it.code > 0xFF }) {
            throw PfException("encoding.unmappable", "Text contains characters that ISO-8859-1 cannot store. Convert the file to UTF-8 first.")
        }
        return encoding.bom + withEndings.toByteArray(encoding.charset)
    }

    private fun startsWith(b: ByteArray, prefix: ByteArray) =
        prefix.isNotEmpty() && b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it] }

    private fun isStrictUtf8(b: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)); true
    } catch (e: CharacterCodingException) { false }
}

/** What we knew about a file when it was opened — used to detect edits made by other apps. */
data class FileStamp(val size: Long, val lastModified: Long, val sha256: String) {
    companion object {
        fun of(file: File): FileStamp? =
            if (!file.isFile) null else FileStamp(file.length(), file.lastModified(), Hashing.sha256(file))
    }
}

enum class ExternalChange { NONE, MODIFIED, DELETED }

object ConflictDetector {
    fun check(file: File, opened: FileStamp?): ExternalChange {
        if (opened == null) return if (file.exists()) ExternalChange.MODIFIED else ExternalChange.NONE
        if (!file.exists()) return ExternalChange.DELETED
        // Cheap check first; hash only when metadata differs (timestamps can be coarse on some filesystems).
        if (file.length() == opened.size && file.lastModified() == opened.lastModified) return ExternalChange.NONE
        return if (Hashing.sha256(file) == opened.sha256) ExternalChange.NONE else ExternalChange.MODIFIED
    }
}

/**
 * Crash-safe writes: data goes to a sibling temp file, is fsync'd, then atomically renamed
 * over the target. A crash leaves either the old file or the new one — never a half-written file.
 */
object AtomicFileWriter {
    fun write(target: File, bytes: ByteArray, minFreeBytes: Long = 0) {
        val dir = target.absoluteFile.parentFile ?: throw PfException("io.no_parent", "File has no parent folder.")
        if (!dir.exists() && !dir.mkdirs()) throw PfException("io.mkdir", "Could not create folder ${dir.name}.")
        val needed = bytes.size.toLong() + minFreeBytes
        if (dir.usableSpace in 1 until needed) {
            throw PfException("io.no_space", "Not enough free storage to save (${humanBytes(needed)} needed, ${humanBytes(dir.usableSpace)} free). Free some space; your edits are still in memory.")
        }
        val tmp = File(dir, ".${target.name}.pf-tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: PfException) {
            throw e
        } catch (e: Exception) {
            throw PfException("io.write_failed", "Saving ${target.name} failed: ${e.message}. The previous version is untouched.", e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

/** Thresholds that keep huge files from exhausting memory or freezing the editor. */
data class LargeFilePolicy(
    val fullEditMaxBytes: Long = 2L * 1024 * 1024,
    val highlightMaxBytes: Long = 512L * 1024,
    val readOnlyPreviewMaxBytes: Long = 20L * 1024 * 1024,
    val previewWindowBytes: Int = 256 * 1024,
) {
    enum class Mode { FULL_EDIT, EDIT_NO_HIGHLIGHT, READ_ONLY_PREVIEW, TOO_LARGE, BINARY }

    fun modeFor(sizeBytes: Long, binary: Boolean): Mode = when {
        binary -> Mode.BINARY
        sizeBytes <= highlightMaxBytes -> Mode.FULL_EDIT
        sizeBytes <= fullEditMaxBytes -> Mode.EDIT_NO_HIGHLIGHT
        sizeBytes <= readOnlyPreviewMaxBytes -> Mode.READ_ONLY_PREVIEW
        else -> Mode.TOO_LARGE
    }

    /** Reads at most [previewWindowBytes] from the start, without loading the full file. */
    fun readHead(file: File): ByteArray = file.inputStream().use { s ->
        val buf = ByteArray(minOf(previewWindowBytes.toLong(), file.length()).toInt())
        var off = 0
        while (off < buf.size) { val n = s.read(buf, off, buf.size - off); if (n < 0) break; off += n }
        buf.copyOf(off)
    }
}
