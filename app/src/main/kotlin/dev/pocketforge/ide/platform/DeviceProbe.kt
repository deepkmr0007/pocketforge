package dev.pocketforge.ide.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import dev.pocketforge.engine.build.TermuxProtocol
import dev.pocketforge.engine.terminal.ShellRunner
import dev.pocketforge.engine.toolchain.DeviceFacts
import java.io.File
import java.util.concurrent.TimeUnit

/** Measures device facts for the capability matrix. Nothing here uses the network. */
class DeviceProbe(private val context: Context) {
    /** Result of the user-initiated executable probe; null until the user runs it. */
    @Volatile var execProbeResult: Boolean? = null

    fun facts(settings: AppSettings, aiConfigured: Boolean): DeviceFacts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.path)
        val pathDirs = (System.getenv("PATH") ?: "").split(':').filter { it.isNotBlank() }
        fun onPath(name: String) = pathDirs.map { File(it, name) }.firstOrNull { it.canExecute() }?.path
        return DeviceFacts(
            apiLevel = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "?",
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            freeStorageBytes = stat.availableBytes,
            targetSdk = context.applicationInfo.targetSdkVersion,
            // Android 10+ forbids exec() of app-writable files for apps targeting API 29+ (W^X).
            // Until the user runs the explicit probe we report the documented policy.
            execFromAppDataAllowed = execProbeResult ?: (context.applicationInfo.targetSdkVersion < 29),
            javaOnPath = onPath("java"),
            gradleOnPath = onPath("gradle"),
            androidSdkPath = System.getenv("ANDROID_HOME")?.takeIf { File(it).isDirectory },
            hardwareKeyboard = context.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY,
            networkAllowedBySettings = !settings.networkBlocked,
            remoteHostConfigured = settings.hostUrl.isNotBlank(),
            aiProviderConfigured = aiConfigured,
            localModelPresent = false,
            shellAvailable = ShellRunner.isAvailable(),
            termuxInstalled = termuxInstalled(),
            termuxPermissionGranted = context.checkSelfPermission(TermuxProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED,
            termuxWorkspaceConfigured = settings.termuxTreeUri.isNotBlank(),
        )
    }

    /**
     * User-initiated, local-only probe: copies the system `toybox` binary into app storage and tries
     * to run `toybox true`. Proves (rather than assumes) whether on-device toolchains could execute.
     */
    fun runExecProbe(): Pair<Boolean, String> {
        val src = listOf("/system/bin/toybox", "/system/bin/toolbox").map(::File).firstOrNull { it.isFile }
            ?: return false to "No system binary available to test with."
        val dir = File(context.cacheDir, "probe").apply { mkdirs() }
        val copy = File(dir, "probe-bin")
        return try {
            src.inputStream().use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
            copy.setExecutable(true, true)
            val p = ProcessBuilder(copy.path, "true").redirectErrorStream(true).start()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            if (!finished) p.destroy()
            val ok = finished && p.exitValue() == 0
            execProbeResult = ok
            ok to if (ok) "Executables in app storage can run on this device." else "Execution was blocked (exit ${if (finished) p.exitValue() else "timeout"})."
        } catch (e: Exception) {
            execProbeResult = false
            false to "Blocked by Android: ${e.message}"
        } finally {
            copy.delete()
        }
    }

    fun termuxInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo(TermuxProtocol.PACKAGE, 0); true }.getOrDefault(false)

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun freeStorageBytes(): Long = StatFs(context.filesDir.path).availableBytes
}
