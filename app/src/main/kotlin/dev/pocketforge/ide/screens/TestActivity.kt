package dev.pocketforge.ide.screens

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.build.TermuxProtocol
import dev.pocketforge.engine.selftest.SelfTest
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.ide.platform.TermuxBridge
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Test center. States plainly where tests can run: JVM/Gradle unit tests need a JDK, which Android
 * does not provide — so they run on your Build Host or in Termux. The app's own security self-test
 * runs right here on this phone.
 */
class TestActivity : BaseActivity() {
    private lateinit var pid: String
    private lateinit var root: File
    private lateinit var col: LinearLayout
    private val listener: (dev.pocketforge.engine.build.BuildAttempt) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        root = c.projects.dirOf(pid)
        val (page, column) = ui.page(ui.topBar("Test center", runCatching { c.projects.get(pid).name }.getOrDefault(""), { finish() }))
        col = column
        setScreen(page)
        c.termux.addListener(listener)
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { c.termux.removeListener(listener); super.onDestroy() }

    private val testName = Regex("(^|/)(test_[^/]+\\.py|[^/]+_test\\.py|[^/]+Tests?\\.(kt|java)|[^/]+\\.(test|spec)\\.[jt]s)$")

    private fun refresh() {
        bg({
            val guard = PathGuard(root)
            val tests = guard.walkFiles().map { guard.relativize(it) }.filter { !it.startsWith("build/") && testName.containsMatchIn(it) }.take(500).toList()
            tests to c.buildHistory.list().filter { it.projectId == pid && (it.task.startsWith("test") || it.task == "testDebugUnitTest") }.take(20)
        }) { r -> r.onSuccess { (tests, runs) -> render(tests, runs) }.onFailure { showError(it) } }
    }

    private fun render(tests: List<String>, runs: List<dev.pocketforge.engine.build.BuildAttempt>) {
        col.removeAllViews()
        col.addView(ui.section("Tests found in this project (${tests.size})"))
        col.addView(ui.card {
            if (tests.isEmpty()) addView(ui.caption("No test files found (looked for *Test.kt/java, test_*.py, *_test.py, *.test.js)."))
            tests.take(60).forEach { t -> addView(ui.button(t, Btn.TEXT) { Nav.project(this@TestActivity, pid, t) }.apply { gravity = android.view.Gravity.START }) }
            if (tests.size > 60) addView(ui.caption("…and ${tests.size - 60} more"))
        })

        col.addView(ui.section("Where they can run"))
        col.addView(ui.card {
            addView(ui.text("On your Build Host", 15f, bold = true))
            addView(ui.caption("Gradle unit tests (`testDebugUnitTest`) run on your computer after you approve the upload. Results come back with the log."))
            addView(ui.button("Open Build center", Btn.TEXT) { Nav.build(this@TestActivity, pid) })
            addView(ui.space(8))
            addView(ui.text("In Termux on this phone (experimental)", 15f, bold = true))
            addView(ui.caption("Runs a command you type (e.g. python -m unittest -v) inside Termux, in a copy of the project in your shared build folder. Termux must have the tools installed."))
            addView(ui.button("Run a test command in Termux…", Btn.SECONDARY, enabled = c.termux.pending == null) { termuxTests() })
            addView(ui.space(8))
            addView(ui.text("Inside PocketForge", 15f, bold = true))
            addView(ui.caption("Not possible: Android has no JDK or Python runtime for apps, and PocketForge does not pretend otherwise."))
        })

        col.addView(ui.section("PocketForge self-test (runs here, offline)"))
        col.addView(ui.card {
            addView(ui.caption("Checks on THIS phone that the consent gate sends nothing without approval, ZIP imports cannot escape, secrets are masked, saves are atomic, AI output is never applied automatically, and risky terminal commands need confirmation. Uses a scratch folder; touches no projects."))
            addView(ui.button("Run self-test", Btn.PRIMARY) { selfTest() })
        })

        col.addView(ui.section("Recent test runs"))
        if (runs.isEmpty()) col.addView(ui.caption("None yet."))
        runs.forEach { a ->
            col.addView(ui.card(12) {
                addView(ui.text("${a.status.name} · ${a.task} · ${a.backend}", 14f, if (a.status == BuildStatus.SUCCESS) p.ok else p.danger, bold = true))
                addView(ui.caption("${DateFormat.getDateTimeInstance().format(Date(a.startedAt))} · ${a.message}"))
                if (a.logFile != null) addView(ui.button("Log", Btn.TEXT) { bg({ c.builds.logText(a) ?: "" }) { r -> r.onSuccess { Dialogs.text(this@TestActivity, "Test log (redacted)", it) } } })
            })
        }
        col.addView(ui.space(24))
    }

    private fun termuxTests() {
        if (!c.termux.isInstalled()) { Dialogs.info(this, "Termux not installed", "See Build center → Setup guide."); return }
        if (checkSelfPermission(TermuxProtocol.PERMISSION) != PackageManager.PERMISSION_GRANTED || c.termux.treeUri() == null) {
            Dialogs.info(this, "Set up Termux first", "Start one on-device build from Build center to grant the permission and choose the shared folder.")
            return
        }
        val suggestion = if (File(root, "test_main.py").exists() || File(root, "main.py").exists()) "python -m unittest -v" else ""
        Dialogs.input(this, "Test command for Termux", "python -m unittest -v", suggestion, "Review",
            help = "Runs inside Termux, in the project's copy. One line; it is shown again before anything runs.",
            validate = { if (it.isBlank() || it.contains('\n') || it.length > 1000) "Enter one command line (max 1000 characters)." else null }) { cmd ->
            val script = c.termux.testScriptFor(cmd.trim())
            Dialogs.text(this, "Script Termux will run", script, "Run" to {
                val name = runCatching { c.projects.get(pid).name }.getOrDefault("project")
                bg({ c.termux.start(pid, name, TermuxBridge.Kind.TEST, script, cmd.trim()) }) { r ->
                    r.onSuccess { toast("Started in Termux") }.onFailure { showError(it, "Could not start") }
                    refresh()
                }
            })
        }
    }

    private fun selfTest() {
        bg({ SelfTest.run(File(cacheDir, "selftest")) }) { r ->
            r.onSuccess { results ->
                val failed = results.count { !it.passed }
                val text = results.joinToString("\n\n") { (if (!it.passed) "✗ FAIL  " else if (it.skipped) "○ SKIP  " else "✓ PASS  ") + it.name + "\n      " + it.detail }
                c.log.info("selftest", "${results.size - failed}/${results.size} passed")
                Dialogs.text(this, if (failed == 0) "Self-test: all ${results.size} checks passed" else "Self-test: $failed FAILED", text)
            }.onFailure { showError(it, "Self-test could not run") }
        }
    }
}
