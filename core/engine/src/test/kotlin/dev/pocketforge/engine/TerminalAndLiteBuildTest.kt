package dev.pocketforge.engine

import dev.pocketforge.engine.build.AndroidLiteTemplate
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.build.LiteBuildDescriptor
import dev.pocketforge.engine.build.LiteBuildScript
import dev.pocketforge.engine.build.TermuxProtocol
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.TemplateInput
import dev.pocketforge.engine.selftest.SelfTest
import dev.pocketforge.engine.terminal.CancelSignal
import dev.pocketforge.engine.terminal.CommandAnalyzer
import dev.pocketforge.engine.terminal.ShellRequest
import dev.pocketforge.engine.terminal.ShellRunner
import dev.pocketforge.engine.terminal.TerminalPolicy
import dev.pocketforge.engine.toolchain.CapStatus
import dev.pocketforge.engine.toolchain.CapabilityEvaluator
import dev.pocketforge.engine.toolchain.CommandRisk
import dev.pocketforge.engine.toolchain.DeviceFacts
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalAndLiteBuildTest {
    private val sh = "/bin/sh"

    @Test fun shellRunsInWorkspaceWithCleanEnvironmentAndReportsExitCode() {
        val ws = tempDir()
        File(ws, "hello.txt").writeText("hi there\n")
        val r = ShellRunner.run(ShellRequest("cat hello.txt; echo HOME=\$HOME; echo SECRET=\${PF_TEST_SECRET:-unset}; pwd; exit 3", ws, shellPath = sh, path = "/usr/bin:/bin"))
        assertEquals(3, r.exitCode)
        assertTrue(r.output.contains("hi there"))
        assertTrue(r.output.contains("HOME=${ws.canonicalPath}"))
        assertTrue(r.output.contains("SECRET=unset"), "parent environment must not leak")
        assertTrue(r.output.trimEnd().endsWith(ws.canonicalPath))
        assertFalse(r.succeeded)
    }

    @Test fun shellTimesOutAndCancels() {
        val ws = tempDir()
        val t = ShellRunner.run(ShellRequest("sleep 5", ws, shellPath = sh, path = "/usr/bin:/bin", timeoutMillis = 300))
        assertTrue(t.timedOut); assertNull(t.exitCode)
        assertTrue(t.durationMillis < 4_000, "took ${t.durationMillis}")
        val cancel = CancelSignal()
        Thread { Thread.sleep(200); cancel.cancel() }.start()
        val c = ShellRunner.run(ShellRequest("sleep 5", ws, shellPath = sh, path = "/usr/bin:/bin"), cancel)
        assertTrue(c.cancelled)
        assertEquals("cancelled", c.statusLabel)
    }

    @Test fun shellOutputIsCappedButProcessStillFinishes() {
        val ws = tempDir()
        val r = ShellRunner.run(ShellRequest("i=0; while [ \$i -lt 4000 ]; do echo 0123456789012345678901234567890123456789; i=\$((i+1)); done; echo done", ws, shellPath = sh, path = "/usr/bin:/bin", maxOutputBytes = 10_000))
        assertEquals(0, r.exitCode)
        assertTrue(r.truncated)
        assertTrue(r.output.length <= 10_000)
    }

    @Test fun missingShellOrFolderGivesActionableErrors() {
        assertEquals("terminal.no_shell", assertFailsWith<PfException> { ShellRunner.run(ShellRequest("ls", tempDir(), shellPath = "/nonexistent/sh")) }.code)
        assertEquals("terminal.no_dir", assertFailsWith<PfException> { ShellRunner.run(ShellRequest("ls", File("/nonexistent-dir-pf"), shellPath = sh)) }.code)
    }

    @Test fun analyzerConfinesPathsAndGatesRisk() {
        val ws = tempDir(); File(ws, "src").mkdirs()
        val p = TerminalPolicy()
        assertFalse(CommandAnalyzer.analyze("ls -la src", ws, p).needsConfirmation)
        assertTrue(CommandAnalyzer.analyze("ls ../", ws, p).outsidePaths.isNotEmpty())
        assertTrue(CommandAnalyzer.analyze("cat ~/x", ws, p).needsConfirmation)
        assertTrue(CommandAnalyzer.analyze("ls ${ws.canonicalPath}/src", ws, p).outsidePaths.isEmpty())
        assertEquals(listOf("/etc/passwd"), CommandAnalyzer.analyze("cat \"/etc/passwd\"", ws, p).outsidePaths)
        assertTrue(CommandAnalyzer.analyze("FOO=/etc/x ls", ws, p).outsidePaths.contains("/etc/x"))
        val net = CommandAnalyzer.analyze("wget https://x", ws, p)
        assertNotNull(net.blockedReason)
        val netAllowed = CommandAnalyzer.analyze("wget https://x", ws, p.copy(allowNetworkCommands = true))
        assertNull(netAllowed.blockedReason); assertTrue(netAllowed.needsConfirmation)
        assertTrue(CommandRisk.DESTRUCTIVE in CommandAnalyzer.analyze("rm -rf build", ws, p).risks)
        assertTrue(CommandAnalyzer.analyze("ls", ws, p.copy(projectTrusted = false)).needsConfirmation)
        assertNotNull(CommandAnalyzer.analyze("   ", ws, p).blockedReason)
    }

    @Test fun capabilityMatrixReportsShellAndTermuxHonestly() {
        val base = DeviceFacts(35, "15", listOf("arm64-v8a"), 8L shl 30, 4L shl 30, 10L shl 30, 35, false, null, null, null, false, true, false, false, false)
        val withShell = CapabilityEvaluator.evaluate(base.copy(shellAvailable = true)).associateBy { it.id }
        assertEquals(CapStatus.SUPPORTED, withShell.getValue("terminal").status)
        assertEquals(CapStatus.UNAVAILABLE, withShell.getValue("build.termux").status)
        val termux = CapabilityEvaluator.evaluate(base.copy(termuxInstalled = true)).associateBy { it.id }
        assertEquals(CapStatus.PERMISSION_REQUIRED, termux.getValue("build.termux").status)
        val ready = CapabilityEvaluator.evaluate(base.copy(termuxInstalled = true, termuxPermissionGranted = true, termuxWorkspaceConfigured = true)).associateBy { it.id }
        assertEquals(CapStatus.EXPERIMENTAL, ready.getValue("build.termux").status)
    }

    @Test fun liteTemplateHasDescriptorAndValidJavaProject() {
        val pm = ProjectManager(tempDir(), FakeClock())
        val p = pm.create(AndroidLiteTemplate.id, TemplateInput("Tap & Co's Counter", "org.demo.tap", minSdk = 24))
        val dir = pm.dirOf(p.id)
        val d = LiteBuildDescriptor.load(dir)
        assertEquals("org.demo.tap", d.packageId)
        assertEquals(24, d.minSdk)
        assertEquals(listOf("app/src/main/java"), d.sources)
        assertTrue(File(dir, "app/src/main/java/org/demo/tap/MainActivity.java").readText().startsWith("package org.demo.tap;"))
        val strings = File(dir, "app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("Hello from Tap &amp; Co\\'s Counter!"), strings)
        assertTrue(File(dir, "app/build.gradle.kts").readText().contains("applicationId = \"org.demo.tap\""))
    }

    @Test fun descriptorRejectsEscapingPathsAndBadValues() {
        val dir = tempDir()
        fun write(json: String) = File(dir, LiteBuildDescriptor.FILE).writeText(json)
        write("""{"type":"android-lite","packageId":"a.b","manifest":"../../etc/x","sources":["src"]}""")
        assertEquals("path.traversal", assertFailsWith<PfException> { LiteBuildDescriptor.load(dir) }.code)
        write("""{"type":"android-lite","packageId":"bad","manifest":"m.xml","sources":["src"]}""")
        assertEquals("lite.bad_package", assertFailsWith<PfException> { LiteBuildDescriptor.load(dir) }.code)
        write("""{"type":"android-lite","packageId":"a.b","manifest":"m.xml","sources":["src"],"versionName":"1.0; rm -rf /"}""")
        assertEquals("lite.bad_version", assertFailsWith<PfException> { LiteBuildDescriptor.load(dir) }.code)
        write("""{"type":"android-lite","packageId":"a.b","manifest":"m.xml","sources":[]}""")
        assertEquals("lite.no_sources", assertFailsWith<PfException> { LiteBuildDescriptor.load(dir) }.code)
        File(dir, LiteBuildDescriptor.FILE).delete()
        assertEquals("lite.no_descriptor", assertFailsWith<PfException> { LiteBuildDescriptor.load(dir) }.code)
    }

    @Test fun generatedScriptIsSyntacticallyValidBashAndQuotesValues() {
        val d = LiteBuildDescriptor("a.b", 26, 35, 3, "1.0 beta", "app/src/main/AndroidManifest.xml", "app/src/main/res", null, listOf("app/src/main/java", "it's here"))
        val s = LiteBuildScript.generate(d, "test")
        assertTrue(s.contains("VNAME='1.0 beta'"))
        assertTrue(s.contains("'it'\\''s here'"))
        assertFalse(s.contains("\$OUTPUT_APK"))
        val f = File(tempDir(), "build.sh").apply { writeText(s) }
        val p = ProcessBuilder("bash", "-n", f.path).redirectErrorStream(true).start()
        val msg = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), msg)
    }

    @Test fun scriptReportsMissingToolsWithoutRunningAnything() {
        val pm = ProjectManager(tempDir(), FakeClock())
        val p = pm.create(AndroidLiteTemplate.id, TemplateInput("Lite", "org.demo.lite"))
        val dir = pm.dirOf(p.id)
        val script = File(dir, ".pocketforge/build.sh").apply { parentFile.mkdirs(); writeText(LiteBuildScript.generate(LiteBuildDescriptor.load(dir), "test")) }
        // A PATH with ordinary shell utilities but none of the Android build tools.
        val bin = tempDir()
        for (tool in listOf("bash", "dirname", "find", "wc", "grep", "sed", "cp", "rm", "mkdir", "cut", "readlink", "cat")) {
            val real = listOf("/usr/bin/$tool", "/bin/$tool").map(::File).firstOrNull { it.canExecute() } ?: continue
            java.nio.file.Files.createSymbolicLink(File(bin, tool).toPath(), real.toPath())
        }
        val pb = ProcessBuilder("/bin/bash", script.path).redirectErrorStream(true)
        pb.environment()["PATH"] = bin.path
        pb.environment().remove("ANDROID_JAR")
        val proc = pb.start()
        val outText = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        val parsed = LiteBuildScript.parse(outText)
        assertEquals(3, code, outText)
        assertFalse(parsed.ok)
        assertEquals("missing-tools", parsed.reason)
        assertTrue(parsed.missing.any { it.startsWith("aapt2") }, outText)
        assertFalse(File(dir, "build/pocketforge").exists(), "nothing should be created when tools are missing")
    }

    @Test fun toolchainBuildRecordingNeverReportsUnverifiedSuccess() {
        val work = tempDir()
        val svc = BuildService(work, BuildHistory(File(work, "h.json")), FakeClock())
        val missing = svc.recordToolchainBuild("p", "termux", 0, "PF_MISSING aapt2\nPF_RESULT failed reason=missing-tools\n", 3, null)
        assertEquals(BuildStatus.FAILED, missing.status); assertTrue(missing.message.contains("aapt2"))
        val noApk = svc.recordToolchainBuild("p", "termux", 0, "PF_RESULT ok apk=x sha256=${"a".repeat(64)}\n", 0, null)
        assertEquals(BuildStatus.ERROR, noApk.status)
        val fake = File(tempDir(), "f.apk").apply { writeBytes(zipOf("x.txt" to "x".toByteArray())) }
        val sha = dev.pocketforge.engine.common.Hashing.sha256(fake)
        val wrongSha = svc.recordToolchainBuild("p", "termux", 0, "PF_RESULT ok apk=x sha256=${"b".repeat(64)}\n", 0, fake)
        assertEquals(BuildStatus.ERROR, wrongSha.status); assertTrue(wrongSha.message.contains("checksum"))
        val notApk = svc.recordToolchainBuild("p", "termux", 0, "PF_RESULT ok apk=x sha256=$sha\n", 0, fake)
        assertEquals(BuildStatus.ERROR, notApk.status)
        val okApk = File(tempDir(), "ok.apk").apply { writeBytes(FakeApk.apk("com.example.ok")) }
        val good = svc.recordToolchainBuild("p", "termux", 0, "PF_STEP sign\nPF_RESULT ok apk=x sha256=${dev.pocketforge.engine.common.Hashing.sha256(okApk)}\n", 0, okApk)
        assertEquals(BuildStatus.SUCCESS, good.status, good.message)
        assertEquals("com.example.ok", good.apk?.packageName)
        assertNotNull(svc.artifactFile(good))
        assertEquals(5, BuildHistory(File(work, "h.json")).list().size)
    }

    @Test fun termuxWorkspaceValidation() {
        assertNull(TermuxProtocol.validateWorkspacePath("/storage/emulated/0/PocketForge"))
        assertNull(TermuxProtocol.validateWorkspacePath("/data/data/com.termux/files/home/pf"))
        assertNotNull(TermuxProtocol.validateWorkspacePath("relative/path"))
        assertNotNull(TermuxProtocol.validateWorkspacePath("/storage/emulated/0/../../data"))
        assertNotNull(TermuxProtocol.validateWorkspacePath("/storage/emulated/0/x\$(reboot)"))
        assertNotNull(TermuxProtocol.validateWorkspacePath("/data/data/dev.pocketforge.ide/files"))
        assertEquals("my-app-12345678", TermuxProtocol.projectFolderName("My App!", "12345678-aaaa"))
    }

    @Test fun onDeviceSelfTestPassesOnJvm() {
        val results = SelfTest.run(File(tempDir(), "selftest"))
        val failed = results.filter { !it.passed }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.name}: ${it.detail}" })
        assertTrue(results.size >= 15)
    }
}
