package dev.pocketforge.ide.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal read-only replacement for AndroidX FileProvider (no third-party code in the APK).
 * Exposes ONLY files inside cacheDir/shared and the build artifacts folder, never projects or
 * secrets. Not exported; other apps get access only through a one-off URI grant on a share/install intent.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun roots(ctx: Context) = mapOf(
        "shared" to File(ctx.cacheDir, "shared"),
        "artifacts" to File(ctx.filesDir, "builds/artifacts"),
    )

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val segs = uri.pathSegments
        if (segs.size != 2) throw FileNotFoundException("bad path")
        val root = roots(ctx)[segs[0]]?.canonicalFile ?: throw FileNotFoundException("unknown root")
        val f = File(root, segs[1]).canonicalFile
        if (f.parentFile != root || !f.isFile) throw FileNotFoundException("not shareable")
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col -> when (col) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.share"

        fun uriFor(ctx: Context, file: File): Uri {
            val c = file.canonicalFile
            val root = when (c.parentFile) {
                File(ctx.cacheDir, "shared").canonicalFile -> "shared"
                File(ctx.filesDir, "builds/artifacts").canonicalFile -> "artifacts"
                else -> throw IllegalArgumentException("File is not in a shareable folder")
            }
            return Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(root).appendPath(c.name).build()
        }
    }
}
