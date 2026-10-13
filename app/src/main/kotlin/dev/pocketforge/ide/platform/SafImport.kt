package dev.pocketforge.ide.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.pocketforge.engine.archive.SafeZipImporter
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.projects.Project
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.security.PathGuard
import java.io.File

/**
 * Imports from Storage Access Framework locations into an app-private project copy. SAF URIs are
 * never assumed to be filesystem paths; every name goes through [PathGuard]; limits are enforced.
 */
object SafImport {
    fun displayName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun importZip(ctx: Context, pm: ProjectManager, uri: Uri): Project {
        val name = (displayName(ctx, uri) ?: "Imported").removeSuffix(".zip").removeSuffix(".ZIP").take(60).ifBlank { "Imported" }
        val open = { ctx.contentResolver.openInputStream(uri) ?: throw PfException("saf.unreadable", "Could not read the selected file. Its permission may have been revoked.") }
        return pm.registerImported(name.replace(Regex("[/\\\\:*?\"<>|]"), "_")) { dir -> SafeZipImporter().import(open, dir) }
    }

    fun importTree(ctx: Context, pm: ProjectManager, treeUri: Uri, maxFiles: Int = 20_000, maxBytes: Long = 512L * 1024 * 1024): Project {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId).let { displayName(ctx, it) } ?: "Imported folder"
        return pm.registerImported(rootName.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(60)) { dest ->
            val guard = PathGuard(dest)
            var files = 0; var bytes = 0L
            fun walk(docId: String, rel: String, depth: Int) {
                if (depth > 32) throw PfException("saf.too_deep", "Folder nesting is deeper than 32 levels.")
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                        val path = if (rel.isEmpty()) name else "$rel/$name"
                        if (name == ".git" || name == "build" || name == ".gradle") continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            guard.resolve(path).mkdirs(); walk(id, path, depth + 1)
                        } else {
                            if (++files > maxFiles) throw PfException("saf.too_many", "Folder has more than $maxFiles files.")
                            val out = guard.resolve(path)
                            out.parentFile?.mkdirs()
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            ctx.contentResolver.openInputStream(docUri)?.use { input ->
                                out.outputStream().use { o ->
                                    val buf = ByteArray(32 * 1024)
                                    while (true) {
                                        val n = input.read(buf); if (n < 0) break
                                        bytes += n
                                        if (bytes > maxBytes) throw PfException("saf.too_large", "Folder is larger than ${humanBytes(maxBytes)}.")
                                        o.write(buf, 0, n)
                                    }
                                }
                            }
                        }
                    }
                } ?: throw PfException("saf.unreadable", "Could not list the folder. Its permission may have been revoked.")
            }
            walk(rootDocId, "", 0)
            if (files == 0) throw PfException("saf.empty", "The selected folder has no files.")
        }
    }

    /** Writes bytes to a user-chosen SAF document (CreateDocument result). */
    fun writeTo(ctx: Context, uri: Uri, write: (java.io.OutputStream) -> Unit) {
        val os = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw PfException("saf.unwritable", "Could not write to the chosen location.")
        os.use(write)
    }

    fun copyToCache(ctx: Context, uri: Uri, name: String, maxBytes: Long = 512L * 1024 * 1024): File {
        val dir = File(ctx.cacheDir, "inspect").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { o ->
                var total = 0L; val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > maxBytes) throw PfException("saf.too_large", "File is too large."); o.write(buf, 0, n) }
            }
        } ?: throw PfException("saf.unreadable", "Could not read the selected file.")
        return f
    }
}
