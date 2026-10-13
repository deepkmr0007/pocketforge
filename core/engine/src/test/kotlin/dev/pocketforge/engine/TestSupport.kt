package dev.pocketforge.engine

import dev.pocketforge.engine.common.Clock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun tempDir(prefix: String = "pf"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

class FakeClock(var now: Long = 1_700_000_000_000) : Clock {
    override fun nowMillis() = now
}

fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        for ((n, b) in entries) {
            z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
        }
    }
    return bos.toByteArray()
}
