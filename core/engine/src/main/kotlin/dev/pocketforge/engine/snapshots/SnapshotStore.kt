package dev.pocketforge.engine.snapshots

import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.JsonObject
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SystemClock
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.storage.AtomicFileWriter
import java.io.File
import java.util.UUID

data class SnapshotFile(val path: String, val sha256: String, val size: Long)
data class Snapshot(val id: String, val label: String, val createdAt: Long, val files: List<SnapshotFile>) {
    val totalBytes get() = files.sumOf { it.size }
}

data class SnapshotDiff(val added: List<String>, val removed: List<String>, val modified: List<String>) {
    val isEmpty get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty()
}

/**
 * Local version history. Files are stored content-addressed (SHA-256), so unchanged files cost
 * nothing in later snapshots. Restoring first takes an automatic "Before restore" snapshot,
 * which makes every restore itself undoable.
 */
class SnapshotStore(private val storeDir: File, private val clock: Clock = SystemClock) {
    private val objects = File(storeDir, "objects")
    private val manifests = File(storeDir, "manifests")
    private val skipDirs = listOf("build/", ".gradle/", ".idea/", ".cxx/", "node_modules/", "__pycache__/", ".venv/")

    init { objects.mkdirs(); manifests.mkdirs() }

    fun create(projectRoot: File, label: String): Snapshot {
        val guard = PathGuard(projectRoot)
        val files = guard.walkFiles().mapNotNull { f ->
            val rel = guard.relativize(f)
            if (skipDirs.any { rel.startsWith(it) || rel.contains("/$it") }) return@mapNotNull null
            val bytes = f.readBytes()
            val sha = Hashing.sha256(bytes)
            val obj = objectFile(sha)
            if (!obj.exists()) AtomicFileWriter.write(obj, bytes)
            SnapshotFile(rel, sha, bytes.size.toLong())
        }.toList()
        val snap = Snapshot(UUID.randomUUID().toString(), label.ifBlank { "Snapshot" }, clock.nowMillis(), files)
        val json = Json.obj(
            "v" to 1, "id" to snap.id, "label" to snap.label, "createdAt" to snap.createdAt,
            "files" to files.map { Json.obj("p" to it.path, "h" to it.sha256, "s" to it.size) },
        )
        AtomicFileWriter.write(File(manifests, "${snap.id}.json"), Json.stringify(json).toByteArray())
        return snap
    }

    fun list(): List<Snapshot> = (manifests.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { runCatching { read(it) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun get(id: String): Snapshot {
        require(id.matches(Regex("[a-f0-9\\-]{36}"))) { "bad snapshot id" }
        val f = File(manifests, "$id.json")
        if (!f.exists()) throw PfException("snapshot.missing", "Snapshot not found.")
        return read(f)
    }

    fun contentOf(file: SnapshotFile): ByteArray {
        val bytes = objectFile(file.sha256).readBytes()
        if (Hashing.sha256(bytes) != file.sha256) throw PfException("snapshot.corrupt", "Stored copy of ${file.path} is corrupted.")
        return bytes
    }

    fun diff(snapshotId: String, projectRoot: File): SnapshotDiff {
        val snap = get(snapshotId)
        val guard = PathGuard(projectRoot)
        val current = guard.walkFiles().map { guard.relativize(it) to it }
            .filterNot { (rel, _) -> skipDirs.any { rel.startsWith(it) || rel.contains("/$it") } }.toMap()
        val old = snap.files.associateBy { it.path }
        val added = current.keys.filter { it !in old }.sorted()
        val removed = old.keys.filter { it !in current }.sorted()
        val modified = old.keys.filter { it in current && Hashing.sha256(current.getValue(it)) != old.getValue(it).sha256 }.sorted()
        return SnapshotDiff(added, removed, modified)
    }

    /** Restores the project to [snapshotId]. Returns the id of the automatic safety snapshot. */
    fun restore(snapshotId: String, projectRoot: File): String {
        val target = get(snapshotId)
        // Verify every object before touching the working tree.
        target.files.forEach { contentOf(it) }
        val safety = create(projectRoot, "Before restore of '${target.label}'")
        val d = diff(snapshotId, projectRoot)
        val guard = PathGuard(projectRoot)
        for (rel in d.added) guard.resolve(rel).delete()
        for (sf in target.files.filter { it.path in d.removed || it.path in d.modified }) {
            AtomicFileWriter.write(guard.resolve(sf.path), contentOf(sf))
        }
        return safety.id
    }

    fun delete(snapshotId: String) {
        get(snapshotId)
        File(manifests, "$snapshotId.json").delete()
        gc()
    }

    /** Removes objects no manifest references. */
    fun gc(): Int {
        val live = list().flatMap { s -> s.files.map { it.sha256 } }.toHashSet()
        var removed = 0
        objects.walkTopDown().filter { it.isFile }.forEach { if (it.name !in live && it.delete()) removed++ }
        return removed
    }

    private fun objectFile(sha: String) = File(File(objects, sha.take(2)), sha)

    private fun read(f: File): Snapshot {
        val o = Json.parseObject(f.readText())
        return Snapshot(
            o.str("id")!!, o.str("label") ?: "", o.long("createdAt") ?: 0,
            o.arr("files").map { v -> (v as JsonObject).let { SnapshotFile(it.str("p")!!, it.str("h")!!, it.long("s") ?: 0) } },
        )
    }
}
