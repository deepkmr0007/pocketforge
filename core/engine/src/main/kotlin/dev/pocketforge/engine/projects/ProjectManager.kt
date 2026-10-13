package dev.pocketforge.engine.projects

import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.JsonObject
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SystemClock
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.storage.AtomicFileWriter
import java.io.File
import java.util.UUID

data class Project(
    val id: String,
    val name: String,
    val templateId: String,
    val language: String,
    val packageId: String?,
    val createdAt: Long,
    val lastOpenedAt: Long,
)

data class TrashEntry(val id: String, val originalProjectId: String, val originalPath: String, val deletedAt: Long, val label: String)

/**
 * Versioned JSON metadata registry (see ADR-0003 for why this replaces Room in v1).
 * Writes are atomic; unknown future versions are refused rather than silently downgraded.
 */
class ProjectRegistry(private val file: File, private val clock: Clock = SystemClock) {
    companion object {
        const val SCHEMA_VERSION = 2
    }

    @Synchronized fun load(): List<Project> {
        if (!file.exists()) return emptyList()
        val root = migrate(Json.parseObject(file.readText()))
        return root.arr("projects").mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            Project(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: "Untitled",
                templateId = o.str("templateId") ?: "empty",
                language = o.str("language") ?: "Text",
                packageId = o.str("packageId"),
                createdAt = o.long("createdAt") ?: 0,
                lastOpenedAt = o.long("lastOpenedAt") ?: 0,
            )
        }
    }

    @Synchronized fun save(projects: List<Project>) {
        val json = Json.obj(
            "schemaVersion" to SCHEMA_VERSION,
            "projects" to projects.map {
                Json.obj(
                    "id" to it.id, "name" to it.name, "templateId" to it.templateId, "language" to it.language,
                    "packageId" to it.packageId, "createdAt" to it.createdAt, "lastOpenedAt" to it.lastOpenedAt,
                )
            },
        )
        AtomicFileWriter.write(file, Json.stringify(json, pretty = true).toByteArray())
    }

    /** v1 stored `opened` instead of `lastOpenedAt` and had no language. */
    internal fun migrate(root: JsonObject): JsonObject {
        val v = root.int("schemaVersion") ?: 1
        if (v > SCHEMA_VERSION) throw PfException("meta.future_version", "Project list was written by a newer app version ($v). Update PocketForge; nothing was changed.")
        if (v == SCHEMA_VERSION) return root
        val projects = root.arr("projects").map { item ->
            val o = item as JsonObject
            val m = LinkedHashMap(o.fields)
            m["lastOpenedAt"] = o["opened"] ?: Json.wrap(0)
            m.remove("opened")
            if (!m.containsKey("language")) m["language"] = Json.wrap("Text")
            JsonObject(m)
        }
        return Json.obj("schemaVersion" to SCHEMA_VERSION, "projects" to projects)
    }
}

/**
 * Creates, lists, opens and safely deletes projects in app-private storage. Deletion moves to
 * an in-app trash so it can be undone; purge is a separate, explicitly confirmed step.
 */
class ProjectManager(
    private val baseDir: File,
    private val clock: Clock = SystemClock,
) {
    val projectsDir = File(baseDir, "projects").apply { mkdirs() }
    private val trashDir = File(baseDir, "trash").apply { mkdirs() }
    private val registry = ProjectRegistry(File(baseDir, "meta/projects.json"), clock)

    fun list(): List<Project> = registry.load().sortedByDescending { it.lastOpenedAt }
    fun get(id: String): Project = list().firstOrNull { it.id == id } ?: throw PfException("project.missing", "Project not found. It may have been deleted.")
    fun dirOf(id: String): File {
        require(id.matches(Regex("[a-f0-9\\-]{36}"))) { "bad project id" }
        return File(projectsDir, id)
    }
    fun guardFor(id: String) = PathGuard(dirOf(id))

    fun create(templateId: String, input: TemplateInput): Project {
        ProjectNames.validate(input.name)?.let { throw PfException("project.bad_name", it) }
        val template = Templates.byId(templateId)
        if (template.needsPackageId) PackageNames.validate(input.packageId)?.let { throw PfException("project.bad_package", it) }
        if (input.minSdk !in 21..36) throw PfException("project.bad_minsdk", "Minimum SDK must be between 21 and 36.")
        val existing = registry.load()
        if (existing.any { it.name.equals(input.name.trim(), ignoreCase = true) }) {
            throw PfException("project.duplicate", "A project named '${input.name.trim()}' already exists. Choose another name.")
        }
        val id = UUID.randomUUID().toString()
        val dir = dirOf(id)
        if (!dir.mkdirs()) throw PfException("io.mkdir", "Could not create the project folder.")
        try {
            val guard = PathGuard(dir)
            for ((rel, content) in template.files(input.copy(name = input.name.trim()))) {
                AtomicFileWriter.write(guard.resolve(rel), content.toByteArray())
            }
        } catch (e: Exception) {
            dir.deleteRecursively() // no half-created projects
            throw e
        }
        val now = clock.nowMillis()
        val p = Project(id, input.name.trim(), template.id, template.language, input.packageId.takeIf { template.needsPackageId }, now, now)
        registry.save(existing + p)
        return p
    }

    /** Registers a folder that was already populated (e.g. by the archive importer). */
    fun registerImported(name: String, populate: (File) -> Unit): Project {
        ProjectNames.validate(name)?.let { throw PfException("project.bad_name", it) }
        val existing = registry.load()
        var finalName = name.trim()
        var n = 2
        while (existing.any { it.name.equals(finalName, ignoreCase = true) }) finalName = "${name.trim()} ($n)".also { n++ }
        val id = UUID.randomUUID().toString()
        val dir = dirOf(id)
        dir.mkdirs()
        try { populate(dir) } catch (e: Exception) { dir.deleteRecursively(); throw e }
        val now = clock.nowMillis()
        val p = Project(id, finalName, "imported", detectLanguage(dir), null, now, now)
        registry.save(existing + p)
        return p
    }

    fun touch(id: String) {
        registry.save(registry.load().map { if (it.id == id) it.copy(lastOpenedAt = clock.nowMillis()) else it })
    }

    fun rename(id: String, newName: String) {
        ProjectNames.validate(newName)?.let { throw PfException("project.bad_name", it) }
        val all = registry.load()
        if (all.any { it.id != id && it.name.equals(newName.trim(), true) }) throw PfException("project.duplicate", "Name already used.")
        registry.save(all.map { if (it.id == id) it.copy(name = newName.trim()) else it })
    }

    /** Moves the whole project to trash (recoverable). */
    fun moveProjectToTrash(id: String): TrashEntry {
        val p = get(id)
        val entry = TrashEntry(UUID.randomUUID().toString(), id, "", clock.nowMillis(), "Project: ${p.name}")
        val target = File(trashDir, entry.id)
        if (!dirOf(id).renameTo(target)) throw PfException("io.trash", "Could not move project to trash.")
        writeTrashMeta(entry, p)
        registry.save(registry.load().filterNot { it.id == id })
        return entry
    }

    /** Moves a file or folder inside a project to trash (recoverable). */
    fun moveToTrash(projectId: String, relativePath: String): TrashEntry {
        val guard = guardFor(projectId)
        val f = guard.resolve(relativePath)
        if (!f.exists()) throw PfException("file.missing", "Nothing to delete at $relativePath.")
        val entry = TrashEntry(UUID.randomUUID().toString(), projectId, guard.normalize(relativePath), clock.nowMillis(), relativePath)
        val holder = File(trashDir, entry.id).apply { mkdirs() }
        if (!f.renameTo(File(holder, f.name))) throw PfException("io.trash", "Could not move $relativePath to trash.")
        writeTrashMeta(entry, null)
        return entry
    }

    fun trash(): List<TrashEntry> = (trashDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull {
        runCatching {
            val o = Json.parseObject(it.readText())
            TrashEntry(o.str("id")!!, o.str("projectId")!!, o.str("path") ?: "", o.long("deletedAt") ?: 0, o.str("label") ?: "")
        }.getOrNull()
    }.sortedByDescending { it.deletedAt }

    fun restore(entryId: String) {
        val meta = File(trashDir, "$entryId.json")
        val o = Json.parseObject(meta.readText())
        val holder = File(trashDir, entryId)
        val pid = o.str("projectId")!!
        val path = o.str("path") ?: ""
        if (path.isEmpty()) {
            val pObj = o.obj("project") ?: throw PfException("trash.corrupt", "Trash entry is missing project metadata.")
            if (dirOf(pid).exists()) throw PfException("trash.conflict", "A project with this id already exists.")
            if (!holder.renameTo(dirOf(pid))) throw PfException("io.restore", "Could not restore project.")
            val restored = Project(pid, pObj.str("name")!!, pObj.str("templateId") ?: "empty", pObj.str("language") ?: "Text", pObj.str("packageId"), pObj.long("createdAt") ?: 0, clock.nowMillis())
            var name = restored.name
            val all = registry.load()
            if (all.any { it.name.equals(name, true) }) name = "$name (restored)"
            registry.save(all + restored.copy(name = name))
        } else {
            val guard = guardFor(pid)
            val dest = guard.resolve(path)
            if (dest.exists()) throw PfException("trash.conflict", "$path exists again. Rename it first, then restore.")
            dest.parentFile?.mkdirs()
            val item = holder.listFiles()?.singleOrNull() ?: throw PfException("trash.corrupt", "Trash entry is empty.")
            if (!item.renameTo(dest)) throw PfException("io.restore", "Could not restore $path.")
            holder.delete()
        }
        meta.delete()
    }

    fun purge(entryId: String) {
        File(trashDir, entryId).deleteRecursively()
        File(trashDir, "$entryId.json").delete()
    }

    private fun writeTrashMeta(e: TrashEntry, p: Project?) {
        val o = Json.obj(
            "id" to e.id, "projectId" to e.originalProjectId, "path" to e.originalPath, "deletedAt" to e.deletedAt, "label" to e.label,
            "project" to p?.let { Json.obj("name" to it.name, "templateId" to it.templateId, "language" to it.language, "packageId" to it.packageId, "createdAt" to it.createdAt) },
        )
        AtomicFileWriter.write(File(trashDir, "${e.id}.json"), Json.stringify(o).toByteArray())
    }

    companion object {
        fun detectLanguage(dir: File): String {
            val counts = HashMap<String, Int>()
            dir.walkTopDown().maxDepth(6).filter { it.isFile }.take(2000).forEach {
                val lang = when (it.extension.lowercase()) {
                    "kt", "kts" -> "Kotlin"; "java" -> "Java"; "py" -> "Python"
                    "html", "htm", "css", "js", "ts" -> "HTML"; else -> null
                }
                if (lang != null) counts[lang] = (counts[lang] ?: 0) + 1
            }
            return counts.maxByOrNull { it.value }?.key ?: "Text"
        }
    }
}
