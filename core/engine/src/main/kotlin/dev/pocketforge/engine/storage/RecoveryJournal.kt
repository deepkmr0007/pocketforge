package dev.pocketforge.engine.storage

import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.SystemClock
import java.io.File

/**
 * Autosave journal for *unsaved* editor buffers. Drafts live in app-private storage, are
 * written atomically and checksummed, and survive process death. On next launch the app
 * offers "Restore / Compare / Discard" for each draft — the real source file is never
 * touched by autosave unless the user enabled "autosave to file".
 */
class RecoveryJournal(private val dir: File, private val clock: Clock = SystemClock) {
    data class Draft(
        val projectId: String,
        val relativePath: String,
        val text: String,
        val baseSha256: String?,
        val savedAtMillis: Long,
    )

    init { dir.mkdirs() }

    private fun keyFile(projectId: String, rel: String) = File(dir, Hashing.sha256("$projectId\u0000$rel").take(32) + ".draft")

    fun saveDraft(projectId: String, relativePath: String, text: String, baseSha256: String?) {
        val header = Json.stringify(
            Json.obj(
                "v" to 1, "projectId" to projectId, "path" to relativePath, "base" to baseSha256,
                "savedAt" to clock.nowMillis(), "sha" to Hashing.sha256(text),
            ),
        )
        AtomicFileWriter.write(keyFile(projectId, relativePath), (header + "\n" + text).toByteArray(Charsets.UTF_8))
    }

    fun clear(projectId: String, relativePath: String) { keyFile(projectId, relativePath).delete() }

    fun clearProject(projectId: String) { drafts().filter { it.projectId == projectId }.forEach { clear(it.projectId, it.relativePath) } }

    /** All intact drafts. Corrupt drafts (checksum mismatch, truncated) are skipped, never half-restored. */
    fun drafts(): List<Draft> = (dir.listFiles { f -> f.name.endsWith(".draft") } ?: emptyArray()).mapNotNull { f ->
        try {
            val all = f.readText(Charsets.UTF_8)
            val nl = all.indexOf('\n')
            if (nl < 0) return@mapNotNull null
            val h = Json.parseObject(all.substring(0, nl))
            val text = all.substring(nl + 1)
            if (h.str("sha") != Hashing.sha256(text)) return@mapNotNull null
            Draft(h.str("projectId")!!, h.str("path")!!, text, h.str("base"), h.long("savedAt") ?: 0)
        } catch (e: Exception) { null }
    }.sortedByDescending { it.savedAtMillis }
}
