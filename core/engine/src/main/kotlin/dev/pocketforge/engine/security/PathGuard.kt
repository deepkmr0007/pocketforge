package dev.pocketforge.engine.security

import dev.pocketforge.engine.common.SecurityViolation
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The single place where untrusted relative paths (from archives, AI diffs, remote servers,
 * search/replace targets, user input) are turned into real files. Every resolution is
 * confined to [root]; absolute paths, `..` segments, NUL bytes, drive letters and
 * symbolic links (anywhere along the path) are rejected.
 */
class PathGuard(root: File) {
    val root: File = root.canonicalFile

    /** Normalises an untrusted relative path to a safe, `/`-separated form, or throws. */
    fun normalize(untrusted: String): String {
        if (untrusted.isEmpty()) throw SecurityViolation("path.empty", "Empty path is not allowed.")
        if (untrusted.length > MAX_PATH_CHARS) throw SecurityViolation("path.too_long", "Path is longer than $MAX_PATH_CHARS characters.")
        if (untrusted.any { it == '\u0000' || it.code in 1..31 }) throw SecurityViolation("path.control_char", "Path contains control characters.")
        val p = untrusted.replace('\\', '/')
        if (p.startsWith("/")) throw SecurityViolation("path.absolute", "Absolute paths are not allowed: $untrusted")
        if (Regex("^[A-Za-z]:").containsMatchIn(p)) throw SecurityViolation("path.drive", "Drive-letter paths are not allowed: $untrusted")
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) throw SecurityViolation("path.empty", "Path resolves to the workspace root.")
        for (seg in parts) {
            if (seg == "..") throw SecurityViolation("path.traversal", "Path escapes the workspace: $untrusted")
            if (seg.length > 255) throw SecurityViolation("path.segment_too_long", "A path segment is longer than 255 characters.")
        }
        return parts.joinToString("/")
    }

    /** Resolves [untrusted] inside the root, refusing anything that escapes or passes through a symlink. */
    fun resolve(untrusted: String): File {
        val rel = normalize(untrusted)
        var cur = root
        for (seg in rel.split('/')) {
            cur = File(cur, seg)
            if (Files.isSymbolicLink(cur.toPath())) {
                throw SecurityViolation("path.symlink", "Refusing to follow a symbolic link: $rel")
            }
        }
        val canonical = cur.canonicalFile
        if (!isWithin(canonical)) throw SecurityViolation("path.escape", "Resolved path escapes the workspace: $rel")
        return cur
    }

    fun isWithin(file: File): Boolean {
        val c = file.canonicalPath
        return c == root.path || c.startsWith(root.path + File.separator)
    }

    fun relativize(file: File): String {
        val c = file.canonicalFile
        if (!isWithin(c)) throw SecurityViolation("path.escape", "File is outside the workspace.")
        return c.path.removePrefix(root.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    /** Lists regular files under [dir] without following symlinks. */
    fun walkFiles(dir: File = root, maxFiles: Int = 200_000): Sequence<File> = sequence {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var count = 0
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles()?.sortedBy { it.name } ?: continue
            for (c in children) {
                if (Files.isSymbolicLink(c.toPath())) continue
                if (Files.isDirectory(c.toPath(), LinkOption.NOFOLLOW_LINKS)) stack.addLast(c)
                else if (Files.isRegularFile(c.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    if (++count > maxFiles) return@sequence
                    yield(c)
                }
            }
        }
    }

    companion object { const val MAX_PATH_CHARS = 1024 }
}
