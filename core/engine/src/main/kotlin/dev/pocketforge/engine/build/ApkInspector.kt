package dev.pocketforge.engine.build

import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class ApkInfo(
    val sha256: String,
    val sizeBytes: Long,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val debuggable: Boolean?,
    val signatureSchemes: List<String>,
    val entryCount: Int,
    val hasDex: Boolean,
) {
    /** "Signed" here means a signature block is *present*. Cryptographic verification is done by Android. */
    val signaturePresent get() = signatureSchemes.isNotEmpty()
    val kind get() = when (debuggable) { true -> "debug"; false -> "release"; null -> "unknown" }
}

/**
 * Offline APK inspection: verifies it is a structurally valid ZIP with a manifest and DEX code,
 * decodes the binary AndroidManifest.xml (AXML) and detects which signature schemes are present
 * (v1 JAR, v2/v3/v3.1 APK Signing Block). Used to validate build artifacts before reporting success.
 */
object ApkInspector {
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_TARGET_SDK = 0x01010270
    private const val ATTR_DEBUGGABLE = 0x0101000f

    fun inspect(file: File, maxManifestBytes: Int = 4 * 1024 * 1024): ApkInfo {
        if (!file.isFile || file.length() < 22) throw PfException("apk.invalid", "Not an APK: file is missing or too small.")
        val zip = try { ZipFile(file) } catch (e: Exception) { throw PfException("apk.invalid", "Not an APK: ${e.message}") }
        zip.use { z ->
            val manifest = z.getEntry("AndroidManifest.xml") ?: throw PfException("apk.no_manifest", "APK has no AndroidManifest.xml.")
            if (manifest.size > maxManifestBytes) throw PfException("apk.manifest_too_large", "Manifest is unexpectedly large.")
            val bytes = z.getInputStream(manifest).use { it.readBytes() }
            val attrs = try { AxmlReader(bytes).read() } catch (e: IllegalStateException) {
                throw PfException("apk.bad_manifest", "AndroidManifest.xml is truncated or corrupt.")
            } catch (e: IndexOutOfBoundsException) {
                throw PfException("apk.bad_manifest", "AndroidManifest.xml is truncated or corrupt.")
            }
            val names = z.entries().toList().map { it.name }
            val schemes = ArrayList<String>()
            if (names.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }) schemes += "v1"
            schemes += signingBlockSchemes(file)
            if (attrs.none { it.element == "manifest" && it.name == "package" && !it.stringValue.isNullOrBlank() }) {
                throw PfException("apk.bad_manifest", "AndroidManifest.xml has no package name; this is not a valid APK.")
            }
            fun a(elem: String, name: String, id: Int) = attrs.firstOrNull { it.element == elem && (it.name == name || it.resId == id) }
            return ApkInfo(
                sha256 = Hashing.sha256(file),
                sizeBytes = file.length(),
                packageName = attrs.firstOrNull { it.element == "manifest" && it.name == "package" }?.stringValue,
                versionCode = a("manifest", "versionCode", ATTR_VERSION_CODE)?.intValue?.toLong(),
                versionName = a("manifest", "versionName", ATTR_VERSION_NAME)?.stringValue,
                minSdk = a("uses-sdk", "minSdkVersion", ATTR_MIN_SDK)?.let { it.intValue ?: it.stringValue?.toIntOrNull() },
                targetSdk = a("uses-sdk", "targetSdkVersion", ATTR_TARGET_SDK)?.let { it.intValue ?: it.stringValue?.toIntOrNull() },
                debuggable = a("application", "debuggable", ATTR_DEBUGGABLE)?.boolValue ?: false,
                signatureSchemes = schemes,
                entryCount = names.size,
                hasDex = names.any { it.matches(Regex("classes\\d*\\.dex")) },
            )
        }
    }

    /** Locates the APK Signing Block (just before the central directory) and lists scheme IDs inside it. */
    fun signingBlockSchemes(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val tailLen = minOf(len, 65_557L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen); raf.readFully(tail)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) { eocd = i; break }
        }
        if (eocd < 0) return emptyList()
        val cdOffset = ByteBuffer.wrap(tail, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (cdOffset < 32 || cdOffset > len) return emptyList()
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24); raf.readFully(footer)
        if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return emptyList()
        val blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (blockSize < 24 || blockSize > cdOffset - 8 || blockSize > 64L * 1024 * 1024) return emptyList()
        val start = cdOffset - blockSize - 8
        val pairsLen = (blockSize - 24).toInt()
        val pairs = ByteArray(pairsLen)
        raf.seek(start + 8); raf.readFully(pairs)
        val bb = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<String>()
        while (bb.remaining() >= 12) {
            val pairLen = bb.long
            if (pairLen < 4 || pairLen > bb.remaining()) break
            val id = bb.int
            when (id) {
                0x7109871a -> out += "v2"
                0xf05368c0.toInt() -> out += "v3"
                0x1b93ad61 -> out += "v3.1"
            }
            bb.position(bb.position() + (pairLen - 4).toInt())
        }
        out
    }
}

/** Minimal, bounds-checked reader for Android binary XML (AXML). */
internal class AxmlReader(private val data: ByteArray) {
    data class Attr(val element: String, val name: String, val resId: Int?, val stringValue: String?, val intValue: Int?, val boolValue: Boolean?)

    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()
    private var resIds: IntArray = IntArray(0)

    fun read(): List<Attr> {
        if (data.size < 8 || u16(0) != 0x0003) throw PfException("apk.bad_manifest", "AndroidManifest.xml is not binary XML.")
        if (i32(4) > data.size || u16(2) < 8) throw PfException("apk.bad_manifest", "AndroidManifest.xml is truncated.")
        val out = ArrayList<Attr>()
        var p = u16(2)
        while (p + 8 <= data.size) {
            val type = u16(p); val hsz = u16(p + 2); val size = i32(p + 4)
            if (size < 8 || p.toLong() + size > data.size) throw PfException("apk.bad_manifest", "Corrupt chunk in manifest.")
            when (type) {
                0x0001 -> strings = readPool(p)
                0x0180 -> resIds = IntArray((size - hsz) / 4) { i32(p + hsz + it * 4) }
                0x0102 -> {
                    val ext = p + hsz
                    val name = str(i32(ext + 4))
                    val attrStart = u16(ext + 8); val attrSize = u16(ext + 10); val count = u16(ext + 12)
                    for (k in 0 until count) {
                        val a = ext + attrStart + k * attrSize
                        if (a + 20 > data.size) break
                        val nameIdx = i32(a + 4)
                        val raw = i32(a + 8)
                        val dataType = data[a + 15].toInt() and 0xFF
                        val d = i32(a + 16)
                        val aname = str(nameIdx)
                        val rid = if (nameIdx in resIds.indices) resIds[nameIdx] else null
                        out += Attr(
                            element = name, name = aname, resId = rid,
                            stringValue = if (dataType == 0x03) str(d) else if (raw >= 0) str(raw) else null,
                            intValue = if (dataType in 0x10..0x11) d else null,
                            boolValue = if (dataType == 0x12) d != 0 else null,
                        )
                    }
                }
            }
            p += size
        }
        return out
    }

    private fun readPool(p: Int): List<String> {
        val count = i32(p + 8); val flags = i32(p + 16); val strStart = i32(p + 20)
        if (count < 0 || count > 200_000) throw PfException("apk.bad_manifest", "Bad string pool.")
        val utf8 = flags and 0x100 != 0
        val base = p + strStart
        return List(count) { i ->
            val off = base + i32(p + 28 + i * 4)
            if (off !in data.indices) return@List ""
            if (utf8) {
                var q = off
                q += if (data[q].toInt() and 0x80 != 0) 2 else 1 // char length
                var blen = data[q].toInt() and 0xFF
                if (blen and 0x80 != 0) { blen = ((blen and 0x7F) shl 8) or (data[q + 1].toInt() and 0xFF); q += 2 } else q += 1
                if (q + blen > data.size) "" else String(data, q, blen, Charsets.UTF_8)
            } else {
                var q = off
                var clen = u16(q)
                if (clen and 0x8000 != 0) { clen = ((clen and 0x7FFF) shl 16) or u16(q + 2); q += 4 } else q += 2
                if (q + clen * 2 > data.size) "" else String(data, q, clen * 2, Charsets.UTF_16LE)
            }
        }
    }

    private fun str(i: Int) = if (i in strings.indices) strings[i] else ""
    private fun u16(o: Int): Int { check(o + 2 <= data.size); return bb.getShort(o).toInt() and 0xFFFF }
    private fun i32(o: Int): Int { check(o + 4 <= data.size); return bb.getInt(o) }
}
