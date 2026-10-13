package dev.pocketforge.ide

import android.app.Application
import java.io.File

class PocketForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashRecorder()
        container.log.info("app", "started v${BuildInfo.VERSION_NAME}")
    }

    /**
     * Crash traces are written locally (redacted) so the next launch can show a recovery notice.
     * Nothing is uploaded. Unsaved editor text is already protected by the recovery journal, and
     * we make one last attempt to journal dirty buffers here.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { container.openEditors().forEach { it.journalAllNow() } }
            runCatching {
                container.log.error("crash", "Uncaught on ${t.name}: ${e.stackTraceToString().take(6000)}")
                File(container.dataRoot, "last-crash.marker").writeText(System.currentTimeMillis().toString())
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun of(ctx: android.content.Context): AppContainer = (ctx.applicationContext as PocketForgeApp).container
    }
}

/** Build metadata. Written by tools/build-apk.sh and the Gradle build from the same values. */
object BuildInfo {
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1
}
