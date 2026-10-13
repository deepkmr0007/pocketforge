package dev.pocketforge.ide.platform

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import dev.pocketforge.engine.common.Hashing

/**
 * THE ONLY FILE that may call Android APIs newer than the app's minSdk (26).
 * The build compiles every other source file against the API 26 android.jar, so an unguarded call
 * to a newer API anywhere else fails the build instead of crashing on an older phone.
 * Every function here checks Build.VERSION.SDK_INT before touching a newer API.
 */
object Compat {
    /**
     * Draw behind the system bars on every API level, so the insets handling is uniform.
     *
     * The decor view is created FIRST: on some Android 15/16 builds Window.getInsetsController()
     * dereferences the decor without creating it, which crashed every screen in onCreate (1.0.0).
     * The controller is then taken from the decor view (which hands out a pending controller until
     * it is attached), and any vendor failure falls back to the pre-API-30 flags instead of crashing.
     */
    @Suppress("DEPRECATION") // statusBarColor/systemUiVisibility are the only way below API 30; documented in ADR-0007.
    fun edgeToEdge(window: Window, lightBars: Boolean) {
        val decor = window.decorView
        runCatching {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val applied = runCatching {
                window.setDecorFitsSystemWindows(false)
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                val controller = decor.windowInsetsController ?: window.insetsController
                controller?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
                controller != null
            }.getOrDefault(false)
            if (applied) return
        }
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (lightBars) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 27) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        decor.systemUiVisibility = flags
    }

    /** left, top, right, bottom padding needed for system bars, display cutout and the keyboard. */
    @Suppress("DEPRECATION")
    fun safeInsets(insets: WindowInsets): IntArray = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        intArrayOf(i.left, i.top, i.right, i.bottom)
    } else {
        // Below API 30 with adjustResize, systemWindowInset* already includes the keyboard.
        intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

    @Suppress("DEPRECATION")
    fun consumed(insets: WindowInsets): WindowInsets = if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()

    /** Android's own parse of an APK, with SHA-256 digests of its signing certificates. */
    @Suppress("DEPRECATION")
    fun archiveInfo(pm: PackageManager, path: String): Pair<PackageInfo, List<String>>? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(path, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> emptyList()
                si.hasMultipleSigners() -> si.apkContentsSigners.toList()
                else -> si.signingCertificateHistory.toList()
            }
        } else info.signatures?.toList() ?: emptyList()
        return info to sigs.map { Hashing.sha256(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** PendingIntents that the system (installer, Termux) must fill with result extras have to be mutable on API 31+. They are always explicit. */
    fun mutableFlag(): Int = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun immutableFlag(): Int = PendingIntent.FLAG_IMMUTABLE

    @Suppress("DEPRECATION")
    fun intentExtra(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java) else intent.getParcelableExtra(key)
}
