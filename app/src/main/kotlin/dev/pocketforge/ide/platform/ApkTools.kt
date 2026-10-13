package dev.pocketforge.ide.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.pocketforge.engine.common.Hashing
import java.io.File

/** Android's own view of an APK (parsed by the platform package parser). */
data class PlatformApkInfo(val packageName: String?, val versionName: String?, val signerSha256: List<String>, val installedVersion: String?)

object ApkTools {
    private fun authority(ctx: Context) = "${ctx.packageName}.files"

    @Suppress("DEPRECATION")
    fun platformInfo(ctx: Context, apk: File): PlatformApkInfo? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> emptyList()
                si.hasMultipleSigners() -> si.apkContentsSigners.toList()
                else -> si.signingCertificateHistory.toList()
            }
        } else info.signatures?.toList() ?: emptyList()
        val installed = runCatching { pm.getPackageInfo(info.packageName, 0).versionName }.getOrNull()
        return PlatformApkInfo(info.packageName, info.versionName, sigs.map { Hashing.sha256(it.toByteArray()) }, installed)
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Hands the APK to Android's package installer. Android shows its own confirmation screen. */
    fun install(ctx: Context, apk: File): String? {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); null } catch (e: ActivityNotFoundException) { "No package installer is available on this device." }
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a file into the shareable cache folder (exposed by FileProvider) and returns it. */
    fun stageForShare(ctx: Context, name: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest shared file is ever exposed
        return File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeBytes(bytes) }
    }
}
