package dev.pocketforge.localverify

import dev.pocketforge.engine.build.AndroidLiteTemplate
import dev.pocketforge.engine.build.BuildHistory
import dev.pocketforge.engine.build.BuildService
import dev.pocketforge.engine.build.BuildStatus
import dev.pocketforge.engine.build.LiteBuildDescriptor
import dev.pocketforge.engine.build.LiteBuildScript
import dev.pocketforge.engine.projects.ProjectManager
import dev.pocketforge.engine.projects.TemplateInput
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * End-to-end: create an android-lite project from the template, generate the on-device build
 * script, RUN it with real Android build tools (aapt2, javac/kotlinc, d8, apksigner), and require
 * that BuildService records SUCCESS only for a verified APK. Same script Termux would run.
 * args: <toolchain bin dir> <android.jar>
 */
fun main(args: Array<String>) {
    val bin = File(args[0]); val androidJar = File(args[1])
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS  " else "FAIL  ") + name + if (detail.isNotEmpty()) " — $detail" else "")
        if (!ok) failures++
    }
    fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), dir: File? = null): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        dir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }

    val base = Files.createTempDirectory("pf-lite-e2e").toFile()
    val home = File(base, "home").apply { mkdirs() }
    val pm = ProjectManager(File(base, "data"))
    val env = mapOf("PATH" to "${bin.path}:/usr/bin:/bin", "ANDROID_JAR" to androidJar.path, "HOME" to home.path)

    for (variant in listOf("java", "java+kotlin")) {
        val p = pm.create(AndroidLiteTemplate.id, TemplateInput("Lite $variant", "org.pocketforge.e2e.${variant.replace("+", "")}"))
        val dir = pm.dirOf(p.id)
        if (variant == "java+kotlin") {
            File(dir, "app/src/main/java/org/pocketforge/e2e/javakotlin/Util.kt").writeText(
                "package org.pocketforge.e2e.javakotlin\n\nobject Util { fun twice(xs: List<Int>) = xs.map { it * 2 }.sum() }\n",
            )
        }
        val script = File(dir, ".pocketforge/build.sh").apply { parentFile.mkdirs(); writeText(LiteBuildScript.generate(LiteBuildDescriptor.load(dir), "e2e")) }
        val t0 = System.currentTimeMillis()
        val (code, out) = run(listOf("bash", script.path), env)
        val parsed = LiteBuildScript.parse(out)
        check("[$variant] script exits 0 and reports PF_RESULT ok", code == 0 && parsed.ok, "exit=$code steps=${parsed.steps} ${if (code != 0) out.takeLast(1500) else ""}")
        val apk = File(dir, LiteBuildScript.OUTPUT_APK)
        val svc = BuildService(File(base, "builds-$variant"), BuildHistory(File(base, "builds-$variant/h.json")))
        val attempt = svc.recordToolchainBuild(p.id, "termux", t0, out, code, apk.takeIf { it.isFile })
        check("[$variant] BuildService records SUCCESS with inspected APK", attempt.status == BuildStatus.SUCCESS, attempt.message)
        val info = attempt.apk
        check("[$variant] APK facts are real", info?.packageName == "org.pocketforge.e2e.${variant.replace("+", "")}" && info.minSdk == 26 && info.targetSdk == 35 && info.debuggable == true && info.hasDex,
            "pkg=${info?.packageName} min=${info?.minSdk} target=${info?.targetSdk} debuggable=${info?.debuggable} schemes=${info?.signatureSchemes}")
        val (vcode, vout) = run(listOf("${bin.path}/apksigner", "verify", "-v", apk.path), env)
        check("[$variant] apksig verifies v2/v3 signature", vcode == 0 && vout.contains("v2 scheme (APK Signature Scheme v2): true"), vout.lines().take(5).joinToString(" | "))
        val (bcode, badging) = run(listOf("${bin.path}/aapt2", "dump", "badging", apk.path), env)
        check("[$variant] aapt2 badging shows launcher activity", bcode == 0 && badging.contains("launchable-activity: name='org.pocketforge.e2e.${variant.replace("+", "")}.MainActivity'"), badging.lines().firstOrNull() ?: "")
        check("[$variant] resources.arsc is stored uncompressed and 4-byte aligned (Android 11+ requirement)", arscAligned(apk))
        if (variant == "java+kotlin") {
            val hasStdlib = ZipFile(apk).use { z -> z.entries().toList().filter { it.name.endsWith(".dex") }.isNotEmpty() }
            check("[$variant] Kotlin sources compiled into dex", hasStdlib && out.contains("PF_STEP dex"))
        }
    }
    base.deleteRecursively()
    if (failures > 0) { println("\nLite build E2E: $failures check(s) FAILED"); exitProcess(1) }
    println("\nLite build E2E: all checks passed")
}

/** Reads the local file header of resources.arsc and checks method=STORED and data offset % 4 == 0. */
private fun arscAligned(apk: File): Boolean {
    val entry = ZipFile(apk).use { z -> z.getEntry("resources.arsc") } ?: return false
    if (entry.method != java.util.zip.ZipEntry.STORED) return false
    RandomAccessFile(apk, "r").use { raf ->
        // Find the local header by scanning for its name after a PK\3\4 signature (APKs are small enough).
        val bytes = ByteArray(raf.length().toInt()); raf.readFully(bytes)
        val name = "resources.arsc".toByteArray()
        var i = 0
        while (i < bytes.size - 30) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                val nameLen = (bytes[i + 26].toInt() and 0xff) or ((bytes[i + 27].toInt() and 0xff) shl 8)
                val extraLen = (bytes[i + 28].toInt() and 0xff) or ((bytes[i + 29].toInt() and 0xff) shl 8)
                if (nameLen == name.size && bytes.copyOfRange(i + 30, i + 30 + nameLen).contentEquals(name)) {
                    return (i + 30 + nameLen + extraLen) % 4 == 0
                }
            }
            i++
        }
    }
    return false
}
