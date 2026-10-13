package dev.pocketforge.localverify

import dev.pocketforge.engine.FakeApk
import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildHostConfig
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.InMemoryConsentLog
import dev.pocketforge.engine.consent.UrlConnectionTransport
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.TemplateInput
import java.io.File
import java.nio.file.Files

/**
 * End-to-end: real Python Build Host over real TLS, real certificate pinning, real consent gateway.
 * Usage: BuildHostE2EKt <path/to/pocketforge_build_host.py>
 */
fun main(args: Array<String>) {
    val script = File(args[0]).absoluteFile
    val tmp = Files.createTempDirectory("pf-e2e").toFile()
    val apk = File(tmp, "fixture.apk").apply { writeBytes(FakeApk.apk("com.example.e2e")) }
    val home = File(tmp, "host-home").apply { mkdirs() }
    val port = 18000 + (System.nanoTime() % 1000).toInt()
    val env = mapOf("PF_HOST_HOME" to home.path, "PF_TEST_APK" to apk.path)

    fun pairing(): Pair<String, String> {
        val p = ProcessBuilder("python3", script.path, "--print-pairing").apply { environment().putAll(env) }.redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        val fp = Regex("Fingerprint:\\s+(\\w+)").find(out)!!.groupValues[1]
        val tok = Regex("Pair token:\\s+(\\S+)").find(out)!!.groupValues[1]
        return fp to tok
    }
    val (fp, token) = pairing()
    val server = ProcessBuilder(
        "python3", script.path, "--host", "127.0.0.1", "--port", "$port",
        "--build-cmd", "mkdir -p app/build/outputs/apk/debug && cp \"\$PF_TEST_APK\" app/build/outputs/apk/debug/app-debug.apk && echo BUILD SUCCESSFUL password=hunter22",
    ).apply { environment().putAll(env) }.redirectErrorStream(true).redirectOutput(File(tmp, "host.log")).start()
    Thread.sleep(1500)
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "PASS  " else "FAIL  ") + name + if (detail.isNotEmpty()) " — $detail" else ""); if (!ok) failures++ }
    try {
        val pm = ProjectManager(File(tmp, "app"))
        val project = pm.create("android-compose", TemplateInput("E2E", "com.example.e2e"))
        File(pm.dirOf(project.id), "local.properties").writeText("sdk.dir=/secret/path")
        val history = BuildHistory(File(tmp, "history.json"))
        val svc = BuildService(File(tmp, "work"), history)
        val entries = ExportManifest.build(pm.dirOf(project.id))
        val zip = svc.packProject(pm.dirOf(project.id), entries)

        fun attempt(host: BuildHostConfig, tok: String, decision: ConsentDecision): dev.pocketforge.engine.build.BuildAttempt {
            val gw = ConsentGateway({ decision }, InMemoryConsentLog())
            val transport = GatedTransport(gw, UrlConnectionTransport(pinnedSha256 = host.certSha256, readTimeoutMs = 60_000))
            return svc.buildRemote(project.id, host, gw, transport, svc.remoteConsentRequest(host, tok, "assembleDebug", zip, entries), "assembleDebug")
        }
        val good = BuildHostConfig("e2e", "https://127.0.0.1:$port", fp)

        val ok = attempt(good, token, ConsentDecision.APPROVE_ONCE)
        check("pinned TLS build succeeds with verified APK", ok.status == BuildStatus.SUCCESS && ok.apk?.packageName == "com.example.e2e", "${ok.status}: ${ok.message}")
        val log = svc.logText(ok) ?: ""
        check("build log came back redacted", log.contains("BUILD SUCCESSFUL") && !log.contains("hunter22"), log.trim().take(120))

        val wrongPin = attempt(good.copy(certSha256 = "00".repeat(32)), token, ConsentDecision.APPROVE_ONCE)
        check("wrong certificate fingerprint is refused", wrongPin.status == BuildStatus.ERROR && (wrongPin.message.contains("Certificate", ignoreCase = true) || wrongPin.message.contains("SSL", true)), wrongPin.message.take(140))

        val badToken = attempt(good, "not-the-token", ConsentDecision.APPROVE_ONCE)
        check("wrong pairing token is rejected (401)", badToken.status == BuildStatus.ERROR && badToken.message.contains("401"), badToken.message)

        val hostLogBefore = File(tmp, "host.log").readText().lines().count { it.contains("POST /v1/build") }
        val denied = attempt(good, token, ConsentDecision.DENY)
        Thread.sleep(300)
        val hostLogAfter = File(tmp, "host.log").readText().lines().count { it.contains("POST /v1/build") }
        check("denied consent sends nothing to the host", denied.status == BuildStatus.CANCELLED && hostLogBefore == hostLogAfter, "requests before=$hostLogBefore after=$hostLogAfter")
        check("local.properties was not uploaded", entries.first { it.path == "local.properties" }.include.not())
        check("history recorded all attempts", history.list().size == 4, "${history.list().size}")
    } finally {
        server.destroy()
    }
    println(if (failures == 0) "\nE2E: all checks passed" else "\nE2E: $failures check(s) failed")
    if (failures > 0) { println(File(tmp, "host.log").readText()); System.exit(1) }
}
