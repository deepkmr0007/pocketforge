package dev.pocketforge.engine.projects

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.ConflictDetector
import dev.pocketforge.engine.storage.DecodedText
import dev.pocketforge.engine.storage.ExternalChange
import dev.pocketforge.engine.storage.FileStamp
import dev.pocketforge.engine.storage.LargeFilePolicy
import dev.pocketforge.engine.storage.TextCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class TreeNode(val path: String, val name: String, val isDir: Boolean, val sizeBytes: Long, val modified: Long, val depth: Int)

enum class SortOrder { NAME, TYPE, MODIFIED, SIZE }

/** File operations inside one project, all routed through [PathGuard]. */
class Workspace(val root: File, private val policy: LargeFilePolicy = LargeFilePolicy()) {
    val guard = PathGuard(root)

    /**
     * Flattened tree for a virtualised list: only children of [expanded] folders are listed,
     * so very large projects stay fast.
     */
    fun tree(expanded: Set<String>, sort: SortOrder = SortOrder.NAME, filter: String = "", showHidden: Boolean = false): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        fun visit(dir: File, rel: String, depth: Int) {
            val kids = (dir.listFiles() ?: emptyArray())
                .filter { !Files.isSymbolicLink(it.toPath()) }
                .filter { showHidden || !it.name.startsWith(".") }
            val sorted = when (sort) {
                SortOrder.NAME -> kids.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                SortOrder.TYPE -> kids.sortedWith(compareBy({ !it.isDirectory }, { it.extension.lowercase() }, { it.name.lowercase() }))
                SortOrder.MODIFIED -> kids.sortedWith(compareBy<File>({ !it.isDirectory }).thenByDescending { it.lastModified() })
                SortOrder.SIZE -> kids.sortedWith(compareBy<File>({ !it.isDirectory }).thenByDescending { it.length() })
            }
            for (k in sorted) {
                val p = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                val isDir = Files.isDirectory(k.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (filter.isNotEmpty() && !isDir && !k.name.contains(filter, ignoreCase = true)) continue
                out += TreeNode(p, k.name, isDir, if (isDir) 0 else k.length(), k.lastModified(), depth)
                if (isDir && (p in expanded || filter.isNotEmpty())) visit(k, p, depth + 1)
            }
        }
        visit(guard.root, "", 0)
        if (filter.isNotEmpty()) {
            // Drop folders that ended up with no matching descendants.
            val keepDirs = out.filter { !it.isDir }.flatMap { n -> n.path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet()
            return out.filter { !it.isDir || it.path in keepDirs }
        }
        return out
    }

    fun createFile(rel: String, content: String = ""): File {
        val f = guard.resolve(rel)
        if (f.exists()) throw PfException("file.exists", "${guard.normalize(rel)} already exists.")
        AtomicFileWriter.write(f, content.toByteArray())
        return f
    }

    fun createFolder(rel: String): File {
        val f = guard.resolve(rel)
        if (f.exists()) throw PfException("file.exists", "${guard.normalize(rel)} already exists.")
        if (!f.mkdirs()) throw PfException("io.mkdir", "Could not create folder.")
        return f
    }

    fun rename(rel: String, newName: String): String {
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            throw PfException("file.bad_name", "Use a plain name without slashes.")
        }
        val src = guard.resolve(rel)
        if (!src.exists()) throw PfException("file.missing", "$rel no longer exists.")
        val parentRel = guard.normalize(rel).substringBeforeLast('/', "")
        val destRel = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
        val dest = guard.resolve(destRel)
        if (dest.exists() && !dest.canonicalPath.equals(src.canonicalPath, ignoreCase = false)) throw PfException("file.exists", "$destRel already exists.")
        if (!src.renameTo(dest)) throw PfException("io.rename", "Rename failed.")
        return destRel
    }

    data class OpenedDocument(
        val path: String,
        val decoded: DecodedText,
        val stamp: FileStamp?,
        val mode: LargeFilePolicy.Mode,
        val sizeBytes: Long,
    ) {
        val readOnly get() = mode == LargeFilePolicy.Mode.READ_ONLY_PREVIEW
    }

    fun open(rel: String): OpenedDocument {
        val f = guard.resolve(rel)
        if (!f.isFile) throw PfException("file.missing", "$rel does not exist.")
        val size = f.length()
        val head = policy.readHead(f)
        val mode = policy.modeFor(size, TextCodec.looksBinary(head))
        return when (mode) {
            LargeFilePolicy.Mode.BINARY -> throw PfException("file.binary", "${f.name} is a binary file and cannot be edited as text.")
            LargeFilePolicy.Mode.TOO_LARGE -> throw PfException("file.too_large", "${f.name} is too large to open (${size / 1024 / 1024} MB). Use search to inspect it.")
            LargeFilePolicy.Mode.READ_ONLY_PREVIEW -> OpenedDocument(guard.normalize(rel), TextCodec.decode(head), FileStamp.of(f), mode, size)
            else -> OpenedDocument(guard.normalize(rel), TextCodec.decode(f.readBytes()), FileStamp.of(f), mode, size)
        }
    }

    sealed interface SaveResult {
        data class Saved(val stamp: FileStamp) : SaveResult
        data class Conflict(val change: ExternalChange, val diskText: String?) : SaveResult
    }

    /** Saves with the original encoding/line endings. Unless [force], refuses to overwrite external edits. */
    fun save(doc: OpenedDocument, text: String, force: Boolean = false): SaveResult {
        if (doc.readOnly) throw PfException("file.read_only", "This large file is open read-only.")
        val f = guard.resolve(doc.path)
        if (!force) {
            val change = ConflictDetector.check(f, doc.stamp)
            if (change != ExternalChange.NONE) {
                return SaveResult.Conflict(change, if (f.isFile) TextCodec.decode(f.readBytes()).text else null)
            }
        }
        AtomicFileWriter.write(f, TextCodec.encode(text, doc.decoded.encoding, doc.decoded.lineEnding), minFreeBytes = 64 * 1024)
        return SaveResult.Saved(FileStamp.of(f)!!)
    }

    fun checkExternal(doc: OpenedDocument): ExternalChange = ConflictDetector.check(guard.resolve(doc.path), doc.stamp)
}
