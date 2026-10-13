package dev.pocketforge.engine.build

import dev.pocketforge.engine.archive.ExportManifest
import dev.pocketforge.engine.archive.ManifestEntry
import dev.pocketforge.engine.common.Clock
import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.JsonObject
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.SystemClock
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.consent.ConsentGateway
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.engine.consent.DataItem
import dev.pocketforge.engine.consent.GatedTransport
import dev.pocketforge.engine.consent.HttpRequest
import dev.pocketforge.engine.security.SecretRedactor
import dev.pocketforge.engine.storage.AtomicFileWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

enum class BuildStatus { SUCCESS, FAILED, CANCELLED, ERROR, EXPORTED }

data class BuildAttempt(
    val id: String,
    val projectId: String,
    val backend: String,
    val task: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: BuildStatus,
    val exitCode: Int?,
    val message: String,
    val logFile: String?,
    val artifactPath: String?,
    val artifactSha256: String?,
    val artifactBytes: Long?,
    val apk: ApkInfo?,
    val toolchain: Map<String, String>,
)

/**
 * Build/test history (versioned JSON, newest first, bounded). A SUCCESS entry is only ever
 * written by [BuildService] after the artifact was received, hashed and parsed as an APK.
 */
class BuildHistory(private val file: File, private val maxEntries: Int = 200) {
    @Synchronized fun list(): List<BuildAttempt> {
        if (!file.exists()) return emptyList()
        return Json.parseObject(file.readText()).arr("attempts").mapNotNull { runCatching { decode(it as JsonObject) }.getOrNull() }
    }

    @Synchronized fun add(a: BuildAttempt) {
        val all = (listOf(a) + list()).take(maxEntries)
        AtomicFileWriter.write(file, Json.stringify(Json.obj("schemaVersion" to 1, "attempts" to all.map { encode(it) })).toByteArray())
    }

    private fun encode(a: BuildAttempt) = Json.obj(
        "id" to a.id, "projectId" to a.projectId, "backend" to a.backend, "task" to a.task, "startedAt" to a.startedAt,
        "finishedAt" to a.finishedAt, "status" to a.status.name, "exitCode" to a.exitCode, "message" to a.message, "log" to a.logFile,
        "artifact" to a.artifactPath, "sha256" to a.artifactSha256, "bytes" to a.artifactBytes, "toolchain" to a.toolchain,
        "apk" to a.apk?.let {
            Json.obj(
                "sha256" to it.sha256, "size" to it.sizeBytes, "pkg" to it.packageName, "vc" to it.versionCode, "vn" to it.versionName,
                "min" to it.minSdk, "target" to it.targetSdk, "debuggable" to it.debuggable, "schemes" to it.signatureSchemes,
                "entries" to it.entryCount, "dex" to it.hasDex,
            )
        },
    )

    private fun decode(o: JsonObject) = BuildAttempt(
        o.str("id")!!, o.str("projectId")!!, o.str("backend") ?: "", o.str("task") ?: "", o.long("startedAt") ?: 0, o.long("finishedAt") ?: 0,
        BuildStatus.valueOf(o.str("status")!!), o.int("exitCode"), o.str("message") ?: "", o.str("log"), o.str("artifact"),
        o.str("sha256"), o.long("bytes"),
        o.obj("apk")?.let {
            ApkInfo(
                it.str("sha256") ?: "", it.long("size") ?: 0, it.str("pkg"), it.long("vc"), it.str("vn"), it.int("min"), it.int("target"),
                it.bool("debuggable"), it.arr("schemes").mapNotNull { s -> (s as? dev.pocketforge.engine.common.JsonString)?.value },
                it.int("entries") ?: 0, it.bool("dex") ?: false,
            )
        },
        o.obj("toolchain")?.fields?.mapValues { (it.value as? dev.pocketforge.engine.common.JsonString)?.value ?: "" } ?: emptyMap(),
    )
}

/** A user-paired build host (see tools/build-host). The token lives in the Keystore-backed vault, not here. */
data class BuildHostConfig(val name: String, val baseUrl: String, val certSha256: String?)

class BuildCancelled : PfException("build.cancelled", "Build cancelled.")

/**
 * Orchestrates builds. Backends:
 *  - [exportForDesktop]: offline, always available.
 *  - [buildRemote]: uploads a manifest-filtered ZIP to the user's own Build Host in ONE consented
 *    request; the response carries the log and the APK, which is hashed and parsed before SUCCESS.
 * There is deliberately no on-device backend in v1 (see ADR-0002).
 */
class BuildService(
    private val workDir: File,
    private val history: BuildHistory,
    private val clock: Clock = SystemClock,
) {
    private val logsDir = File(workDir, "logs").apply { mkdirs() }
    private val artifactsDir = File(workDir, "artifacts").apply { mkdirs() }

    fun exportForDesktop(projectId: String, projectRoot: File, entries: List<ManifestEntry>, projectName: String, out: java.io.OutputStream): BuildAttempt {
        val start = clock.nowMillis()
        val extras = if (entries.any { it.path == "BUILD_INSTRUCTIONS.md" }) emptyMap()
        else mapOf("BUILD_INSTRUCTIONS.md" to buildInstructions(projectName).toByteArray())
        val n = ExportManifest.writeZip(projectRoot, entries, out, extras)
        val a = BuildAttempt(
            UUID.randomUUID().toString(), projectId, "export", "export", start, clock.nowMillis(), BuildStatus.EXPORTED, null,
            "Exported $n files. Build on a computer: see BUILD_INSTRUCTIONS.md.", null, null, null, null, null, emptyMap(),
        )
        history.add(a)
        return a
    }

    fun buildInstructions(projectName: String) = """# Building "$projectName" on a computer

1. Install Android Studio (includes JDK and Android SDK) or JDK 17+ and the Android command-line tools.
2. Unzip this archive and open the folder in Android Studio, or in a terminal run:
       gradle wrapper
       ./gradlew assembleDebug
3. The APK is written to app/build/outputs/apk/debug/app-debug.apk.
4. Copy it back to your phone and open it in PocketForge → Build Center → Inspect APK before installing.

Signing keys are never included in exports. Release builds must be signed on the build machine.
"""

    /** Packs included files into a ZIP in memory (bounded). */
    fun packProject(projectRoot: File, entries: List<ManifestEntry>, maxBytes: Long = 64L * 1024 * 1024): ByteArray {
        val total = entries.filter { it.include }.sumOf { it.sizeBytes }
        if (total > maxBytes) throw PfException("build.too_large", "Project upload would be ${humanBytes(total)} (limit ${humanBytes(maxBytes)}). Exclude large files in the manifest.")
        val bos = ByteArrayOutputStream()
        ExportManifest.writeZip(projectRoot, entries, bos)
        return bos.toByteArray()
    }

    fun remoteConsentRequest(host: BuildHostConfig, token: String, task: String, zip: ByteArray, entries: List<ManifestEntry>): ConsentRequest {
        require(task in ALLOWED_TASKS) { "task not allowed" }
        val included = entries.filter { it.include }
        val req = HttpRequest(
            "POST", host.baseUrl.trimEnd('/') + "/v1/build",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/zip", "X-PocketForge-Task" to task),
            zip,
        )
        return ConsentRequest(
            feature = "Remote build",
            provider = "Your Build Host \"${host.name}\"",
            purpose = "Upload the project so your Build Host can run `gradle $task` and send back the log and APK.",
            risks = listOf(
                "Your source code (${included.size} files, ${humanBytes(zip.size.toLong())} zipped) leaves this phone.",
                "Build scripts in the project run with the Build Host user's permissions on that computer.",
                "The Build Host keeps a temporary copy until the build finishes (it deletes it afterwards unless configured otherwise).",
                if (host.certSha256 != null) "Connection is pinned to certificate ${host.certSha256.take(16)}…" else "Connection uses the platform's trusted certificates.",
            ),
            dataItems = included.take(500).map { DataItem(it.path, it.sizeBytes, "") } +
                (if (included.size > 500) listOf(DataItem("…and ${included.size - 500} more files", 0, "")) else emptyList()),
            request = req,
            sendsSourceCode = true,
        )
    }

    /**
     * Performs a remote build. The caller already obtained [consent] (one approval = this one upload).
     * Success is declared only if the host returned an artifact whose SHA-256 matches and which parses as an APK.
     */
    fun buildRemote(
        projectId: String,
        host: BuildHostConfig,
        gateway: ConsentGateway,
        transport: GatedTransport,
        consent: ConsentRequest,
        task: String,
    ): BuildAttempt {
        val start = clock.nowMillis()
        val id = UUID.randomUUID().toString()
        val logFile = File(logsDir, "$id.log")
        fun finish(status: BuildStatus, msg: String, exit: Int? = null, artifact: File? = null, apk: ApkInfo? = null, tool: Map<String, String> = emptyMap()): BuildAttempt {
            val a = BuildAttempt(id, projectId, "remote:${host.name}", task, start, clock.nowMillis(), status, exit, msg,
                logFile.takeIf { it.exists() }?.name, artifact?.name, apk?.sha256, artifact?.length(), apk, tool)
            history.add(a); return a
        }
        val ticket = try { gateway.request(consent) } catch (e: PfException) { return finish(BuildStatus.CANCELLED, e.message ?: "Not approved") }
        val resp = try { transport.send(ticket, consent.request) } catch (e: PfException) { return finish(BuildStatus.ERROR, e.message ?: "Network error") }
        if (resp.status == 401 || resp.status == 403) return finish(BuildStatus.ERROR, "Build Host rejected the pairing token (HTTP ${resp.status}). Pair again.")
        val body = try { Json.parseObject(resp.text()) } catch (e: PfException) { return finish(BuildStatus.ERROR, "Build Host sent an unreadable response (HTTP ${resp.status}).") }
        val log = SecretRedactor.redact(body.str("log") ?: "", redactPaths = true).text
        AtomicFileWriter.write(logFile, log.toByteArray())
        val tool = body.obj("toolchain")?.fields?.mapValues { (it.value as? dev.pocketforge.engine.common.JsonString)?.value ?: "" } ?: emptyMap()
        val exit = body.int("exitCode")
        if (resp.status != 200 || body.bool("ok") != true) {
            return finish(BuildStatus.FAILED, body.str("error") ?: "Build failed with exit code ${exit ?: "?"}. Open the log for details.", exit, tool = tool)
        }
        if (!task.startsWith("assemble")) {
            // Test/lint tasks produce no APK; success means the host really ran Gradle and it exited 0.
            return if (exit == 0) finish(BuildStatus.SUCCESS, "`$task` passed on ${host.name}.", exit, tool = tool)
            else finish(BuildStatus.FAILED, "`$task` did not report exit code 0.", exit, tool = tool)
        }
        val art = body.obj("artifact") ?: return finish(BuildStatus.FAILED, "Build reported success but returned no APK.", exit, tool = tool)
        val bytes = try { Base64.getDecoder().decode(art.str("base64") ?: "") } catch (e: IllegalArgumentException) {
            return finish(BuildStatus.ERROR, "APK payload was corrupted in transit.", exit, tool = tool)
        }
        val claimed = art.str("sha256")?.lowercase()
        val actual = Hashing.sha256(bytes)
        if (claimed == null || claimed != actual) return finish(BuildStatus.ERROR, "APK checksum mismatch (expected $claimed, got $actual). Not saved.", exit, tool = tool)
        val safeName = (art.str("name") ?: "app.apk").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).let { if (it.endsWith(".apk")) it else "$it.apk" }
        val out = File(artifactsDir, "${id.take(8)}-$safeName")
        AtomicFileWriter.write(out, bytes)
        val apk = try { ApkInspector.inspect(out) } catch (e: PfException) {
            out.delete(); return finish(BuildStatus.ERROR, "Returned file is not a valid APK: ${e.message}", exit, tool = tool)
        }
        if (!apk.hasDex) { return finish(BuildStatus.ERROR, "Returned APK contains no code (classes.dex).", exit, out, apk, tool) }
        return finish(BuildStatus.SUCCESS, "Built ${apk.packageName ?: "APK"} ${apk.versionName ?: ""} (${apk.kind}, ${if (apk.signaturePresent) "signed " + apk.signatureSchemes.joinToString("+") else "unsigned"}).", exit, out, apk, tool)
    }

    /**
     * Records a build that ran a local toolchain script (e.g. in Termux). [output] is the combined
     * script output; [apk] is the artifact copied back (or null). SUCCESS requires the script's
     * PF_RESULT ok line, a matching SHA-256 and a structurally valid APK with code — otherwise the
     * attempt is recorded as FAILED/ERROR with the reason.
     */
    fun recordToolchainBuild(projectId: String, backend: String, startedAt: Long, output: String, exitCode: Int?, apk: File?): BuildAttempt {
        val id = UUID.randomUUID().toString()
        val logFile = File(logsDir, "$id.log")
        AtomicFileWriter.write(logFile, SecretRedactor.redact(output, redactPaths = true).text.toByteArray())
        val parsed = LiteBuildScript.parse(output)
        fun finish(status: BuildStatus, msg: String, artifact: File? = null, info: ApkInfo? = null): BuildAttempt {
            val a = BuildAttempt(id, projectId, backend, "assembleDebug", startedAt, clock.nowMillis(), status, exitCode, msg,
                logFile.name, artifact?.name, info?.sha256, artifact?.length(), info, mapOf("script" to "pocketforge-lite"))
            history.add(a); return a
        }
        if (parsed.missing.isNotEmpty()) return finish(BuildStatus.FAILED, "Missing tools: ${parsed.missing.joinToString("; ")}. Install them in Termux (see Help → On-device builds).")
        if (!parsed.ok || exitCode != 0) return finish(BuildStatus.FAILED, "Build failed${parsed.reason?.let { " ($it)" } ?: ""}, exit code ${exitCode ?: "unknown"}. Open the log for details.")
        if (apk == null || !apk.isFile) return finish(BuildStatus.ERROR, "The script reported success but the APK could not be read back.")
        val out = File(artifactsDir, "${id.take(8)}-app-debug.apk")
        apk.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        val actual = Hashing.sha256(out)
        if (parsed.sha256 == null || parsed.sha256 != actual) {
            out.delete(); return finish(BuildStatus.ERROR, "APK checksum mismatch (script said ${parsed.sha256}, file is $actual). Not saved.")
        }
        val info = try { ApkInspector.inspect(out) } catch (e: PfException) {
            out.delete(); return finish(BuildStatus.ERROR, "Output is not a valid APK: ${e.message}")
        }
        if (!info.hasDex) return finish(BuildStatus.ERROR, "APK contains no code (classes.dex).", out, info)
        return finish(BuildStatus.SUCCESS, "Built ${info.packageName} ${info.versionName ?: ""} on device (${info.kind}, ${if (info.signaturePresent) "signed " + info.signatureSchemes.joinToString("+") else "unsigned"}).", out, info)
    }

    /** Records a test run (Build Host or Termux). SUCCESS strictly means the runner reported exit code 0. */
    fun recordTestRun(projectId: String, backend: String, command: String, startedAt: Long, output: String, exitCode: Int?): BuildAttempt {
        val id = UUID.randomUUID().toString()
        val logFile = File(logsDir, "$id.log")
        AtomicFileWriter.write(logFile, SecretRedactor.redact(output, redactPaths = true).text.toByteArray())
        val status = when (exitCode) { 0 -> BuildStatus.SUCCESS; null -> BuildStatus.ERROR; else -> BuildStatus.FAILED }
        val msg = when (status) {
            BuildStatus.SUCCESS -> "Tests passed (`$command` exited 0)."
            BuildStatus.FAILED -> "Tests failed (`$command` exited $exitCode). Open the log for details."
            else -> "Test run did not complete; no exit code was reported."
        }
        val a = BuildAttempt(id, projectId, backend, "test: $command".take(120), startedAt, clock.nowMillis(), status, exitCode, msg, logFile.name, null, null, null, null, emptyMap())
        history.add(a)
        return a
    }

    fun logText(attempt: BuildAttempt): String? = attempt.logFile?.let { File(logsDir, it).takeIf { f -> f.isFile }?.readText() }
    fun artifactFile(attempt: BuildAttempt): File? = attempt.artifactPath?.let { File(artifactsDir, it).takeIf { f -> f.isFile } }

    companion object {
        /** The Build Host only accepts these tasks; arbitrary commands are never sent. */
        val ALLOWED_TASKS = listOf("assembleDebug", "testDebugUnitTest", "lintDebug", "assembleRelease")
    }
}
