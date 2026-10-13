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
        container.log.info("app", "started v${BuildConfig.VERSION_NAME}")
    }

    /**
     * Crash traces are written locally (redacted) so the next launch can show a recovery screen.
     * Nothing is uploaded. Unsaved editor text is already protected by the recovery journal.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                container.log.error("crash", "Uncaught on ${t.name}: ${e.stackTraceToString().take(6000)}")
                File(container.dataRoot, "last-crash.marker").writeText(System.currentTimeMillis().toString())
            }
            previous?.uncaughtException(t, e)
        }
    }
}
