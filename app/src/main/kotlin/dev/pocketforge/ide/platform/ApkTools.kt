package dev.pocketforge.ide.platform

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.ide.PocketForgeApp
import java.io.File

/** Android's own view of an APK (parsed by the platform package parser). */
data class PlatformApkInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: List<String>,
    val installedVersion: String?,
    val installedSignerMatches: Boolean?,
)

object ApkTools {
    fun platformInfo(ctx: Context, apk: File): PlatformApkInfo? {
        val pm = ctx.packageManager
        val (info, sigs) = Compat.archiveInfo(pm, apk.path) ?: return null
        val installed = runCatching { Compat.archiveInfo(pm, pm.getApplicationInfo(info.packageName, 0).sourceDir) }.getOrNull()
        val installedVersion = runCatching { pm.getPackageInfo(info.packageName, 0).versionName }.getOrNull()
        return PlatformApkInfo(
            info.packageName, info.versionName, Compat.versionCode(info), sigs, installedVersion,
            installed?.second?.let { it.toSet() == sigs.toSet() },
        )
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        ctx.startActivity(i)
    }

    /**
     * Streams the APK into a PackageInstaller session. Android then shows ITS OWN confirmation
     * screen (via STATUS_PENDING_USER_ACTION); nothing is ever installed silently.
     */
    fun install(ctx: Context, apk: File) {
        val app = ctx.applicationContext
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                val cb = Intent(app, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(app, id, cb, PendingIntent.FLAG_UPDATE_CURRENT or Compat.mutableFlag())
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw PfException("install.failed", "Could not hand the APK to Android's installer: ${e.message}")
        }
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = ShareProvider.uriFor(ctx, file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(Intent.createChooser(send, "Share ${file.name}")) } catch (e: ActivityNotFoundException) {
            throw PfException("share.none", "No app on this phone can receive shared files.")
        }
    }

    /** Copies bytes into the shareable cache folder. Only the latest shared file is ever exposed. */
    fun stageForShare(ctx: Context, name: String, write: (java.io.OutputStream) -> Unit): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100))
        f.outputStream().use(write)
        return f
    }
}

/** Receives PackageInstaller status. Not exported; only the system can deliver our PendingIntent. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val app = context.applicationContext as? PocketForgeApp
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = Compat.intentExtra(intent, Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (e: Exception) { toast(context, "Android could not show the install confirmation: ${e.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                app?.container?.log?.info("install", "installed ${intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)}")
                toast(context, "Installed.")
            }
            else -> {
                app?.container?.log?.warn("install", "status=$status $msg")
                val hint = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app — usually a different signing key. Uninstall the old app first (this deletes its data)."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this phone (minSdk or CPU architecture)."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or unsigned."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the device or a policy."
                    else -> "Install failed."
                }
                toast(context, "$hint ${msg ?: ""}".trim())
            }
        }
    }

    private fun toast(ctx: Context, m: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() }

    companion object { const val ACTION = "dev.pocketforge.ide.INSTALL_RESULT" }
}
