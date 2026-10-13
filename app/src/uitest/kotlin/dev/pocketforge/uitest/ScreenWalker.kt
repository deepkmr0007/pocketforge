package dev.pocketforge.uitest

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import dev.pocketforge.engine.projects.Project
import dev.pocketforge.engine.projects.TemplateInput
import dev.pocketforge.engine.selftest.SelfTest
import dev.pocketforge.ide.PocketForgeApp
import dev.pocketforge.ide.editor.CodeEditText
import dev.pocketforge.ide.screens.AiActivity
import dev.pocketforge.ide.screens.BuildActivity
import dev.pocketforge.ide.screens.ExportActivity
import dev.pocketforge.ide.screens.HelpActivity
import dev.pocketforge.ide.screens.HomeActivity
import dev.pocketforge.ide.screens.Nav
import dev.pocketforge.ide.screens.NewProjectActivity
import dev.pocketforge.ide.screens.PreviewActivity
import dev.pocketforge.ide.screens.PrivacyActivity
import dev.pocketforge.ide.screens.ProjectActivity
import dev.pocketforge.ide.screens.SearchActivity
import dev.pocketforge.ide.screens.SettingsActivity
import dev.pocketforge.ide.screens.SnapshotsActivity
import dev.pocketforge.ide.screens.TerminalActivity
import dev.pocketforge.ide.screens.TestActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Opens every PocketForge screen on a real Android system, the way a person would reach it,
 * and takes a screenshot of each. Any crash kills the process and fails the run; the walker
 * also types into the editor, rotates it, and runs the on-device security self-test.
 *
 *   adb shell am instrument -w -r -e pass light dev.pocketforge.uitest/.ScreenWalker
 *
 * Runs inside the app's process (instrumentation), so it needs no extra permission in the app
 * and nothing in the shipped APK exists only for testing.
 */
class ScreenWalker : Instrumentation() {
    private var pass = "default"
    private var shotIndex = 0
    private lateinit var out: File
    private val failures = ArrayList<String>()
    private val notes = ArrayList<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        pass = arguments?.getString("pass") ?: "default"
        start()
    }

    override fun onStart() {
        super.onStart()
        waitForIdleSync() // Application.onCreate has run once the main thread is idle
        try {
            walk()
        } catch (t: Throwable) {
            failures += "walker stopped: ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString().take(3000)}"
        }
        val summary = buildString {
            append("pass=").append(pass).append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" api=").append(Build.VERSION.SDK_INT).append(" screenshots=").append(shotIndex)
            if (notes.isNotEmpty()) append("\nnotes:\n").append(notes.joinToString("\n"))
            if (failures.isNotEmpty()) append("\nFAILURES:\n").append(failures.joinToString("\n"))
        }
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("summary", summary) })
    }

    private fun status(key: String, value: String) = sendStatus(1, Bundle().apply { putString(key, value) })

    private fun walk() {
        val ctx = targetContext
        out = File(ctx.getExternalFilesDir(null), "uitest/$pass").apply { deleteRecursively(); mkdirs() }
        val c = PocketForgeApp.of(ctx)

        fun ensure(name: String, template: String, pkg: String): Project =
            c.projects.list().firstOrNull { it.name == name } ?: c.projects.create(template, TemplateInput(name, pkg))
        val lite = ensure("Hello Lite", "android-lite", "dev.example.hellolite")
        val web = ensure("Hello Web", "web", "dev.example.helloweb")
        val liteDir = c.projects.dirOf(lite.id)
        val source = liteDir.walkTopDown().firstOrNull { it.isFile && (it.extension == "java" || it.extension == "kt") }
            ?.relativeTo(liteDir)?.path

        fun intent(cls: Class<*>) = Intent(ctx, cls)

        screen("home", intent(HomeActivity::class.java))
        screen("new-project", intent(NewProjectActivity::class.java))
        screen("project-explorer", intent(ProjectActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        if (source != null) editorChecks(intent(ProjectActivity::class.java).putExtra(Nav.PROJECT, lite.id).putExtra(Nav.PATH, source))
        else failures += "android-lite template has no source file"
        screen("search", intent(SearchActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("snapshots", intent(SnapshotsActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("export", intent(ExportActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("build-project", intent(BuildActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("build-all", intent(BuildActivity::class.java))
        screen("terminal", intent(TerminalActivity::class.java).putExtra(Nav.PROJECT, lite.id).putExtra(Nav.COMMAND, "ls -la"))
        screen("tests", intent(TestActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("ai", intent(AiActivity::class.java).putExtra(Nav.PROJECT, lite.id))
        screen("privacy", intent(PrivacyActivity::class.java))
        screen("settings", intent(SettingsActivity::class.java))
        screen("help", intent(HelpActivity::class.java))
        screen("help-capabilities", intent(HelpActivity::class.java).putExtra(Nav.PATH, "capabilities"))
        screen("preview-web", intent(PreviewActivity::class.java).putExtra(Nav.PROJECT, web.id).putExtra(Nav.PATH, "index.html"))

        status("step", "self-test")
        val checks = SelfTest.run(File(ctx.cacheDir, "uitest-selftest"))
        for (k in checks) {
            val mark = if (!k.passed) "FAIL" else if (k.skipped) "skip" else "ok"
            notes += "self-test $mark: ${k.name} — ${k.detail}"
            if (!k.passed) failures += "self-test failed: ${k.name} — ${k.detail}"
        }

        // Errors the app caught and logged (shown to the user as dialogs) are worth seeing too.
        c.log.tail(400).filter { "\"l\":\"ERROR\"" in it }.forEach { notes += "app log error: ${it.take(600)}" }
    }

    /** Starts a screen as the app's own navigation would, waits for it to settle, screenshots it, closes it. */
    private fun screen(name: String, intent: Intent, settle: Long = 1500, whileOpen: (Activity) -> Unit = {}) {
        status("screen", name)
        val activity = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        settle(settle)
        if (activity.isFinishing) failures += "$name closed itself immediately"
        shot(name)
        whileOpen(activity)
        runOnMainSync { if (!activity.isFinishing) activity.finish() }
        settle(400)
    }

    private fun editorChecks(intent: Intent) = screen("project-editor", intent) { activity ->
        val editor = runOnMain { find(activity.window.decorView, CodeEditText::class.java) }
        if (editor == null) { failures += "editor: no CodeEditText on screen"; return@screen }
        val before = runOnMain { editor.text.length }
        runOnMainSync { editor.requestFocus(); editor.setSelection(0) }
        sendStringSync("// typed on device\n")
        settle(800)
        val after = runOnMain { editor.text.length }
        if (after <= before) failures += "editor: typing did not change the text ($before -> $after)"
        shot("project-editor-typed")

        // Rotation recreates the screen: the typed text must survive.
        val auto = uiAutomation
        auto.setRotation(UiAutomation.ROTATION_FREEZE_90)
        settle(2500)
        shot("project-editor-landscape")
        auto.setRotation(UiAutomation.ROTATION_FREEZE_0)
        settle(2000)
        auto.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun settle(ms: Long) { waitForIdleSync(); SystemClock.sleep(ms); waitForIdleSync() }

    private fun shot(name: String) {
        val bmp: Bitmap = uiAutomation.takeScreenshot() ?: run { notes += "screenshot unavailable for $name"; return }
        shotIndex++
        FileOutputStream(File(out, "%02d-%s.png".format(shotIndex, name))).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun <T> runOnMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : View> find(v: View, cls: Class<T>): T? {
        if (cls.isInstance(v) && v.isShown) return cls.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }
}
