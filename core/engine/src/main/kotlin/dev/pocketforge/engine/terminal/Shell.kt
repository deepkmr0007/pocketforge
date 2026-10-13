package dev.pocketforge.engine.terminal

import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.security.SecretRedactor
import dev.pocketforge.engine.toolchain.CommandClassifier
import dev.pocketforge.engine.toolchain.CommandRisk
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the terminal knows about a command BEFORE it runs. The UI shows this verbatim and asks for
 * confirmation whenever [needsConfirmation] is true; [blockedReason] means it will not run at all.
 */
data class CommandAnalysis(
    val command: String,
    val risks: Set<CommandRisk>,
    val outsidePaths: List<String>,
    val reasons: List<String>,
    val needsConfirmation: Boolean,
    val blockedReason: String?,
)

/** Policy knobs that come from Settings / project trust. */
data class TerminalPolicy(
    val allowNetworkCommands: Boolean = false,
    val projectTrusted: Boolean = true,
    val maxCommandChars: Int = 4_000,
)

object CommandAnalyzer {
    /**
     * Combines the risk classifier with a workspace-confinement check: any argument that is an
     * absolute path outside [workspace], starts with `~`, or climbs out with `..` is reported.
     */
    fun analyze(command: String, workspace: File, policy: TerminalPolicy): CommandAnalysis {
        val c = command.trim()
        val reasons = ArrayList<String>()
        if (c.isEmpty()) return CommandAnalysis(c, emptySet(), emptyList(), listOf("Empty command."), false, "Type a command first.")
        if (c.length > policy.maxCommandChars) {
            return CommandAnalysis(c, emptySet(), emptyList(), emptyList(), false, "Command is longer than ${policy.maxCommandChars} characters.")
        }
        if (c.any { it == '\u0000' }) return CommandAnalysis(c, emptySet(), emptyList(), emptyList(), false, "Command contains a NUL byte.")
        val risks = CommandClassifier.classify(c)
        val root = workspace.canonicalFile
        val outside = LinkedHashSet<String>()
        for (tok in tokens(c)) {
            val t = tok.trim('"', '\'')
            if (t.isEmpty()) continue
            val candidate = when {
                t.startsWith("~") -> { outside += t; null }
                t.startsWith("/") -> File(t)
                t == ".." || t.startsWith("../") || t.contains("/../") || t.endsWith("/..") -> File(root, t)
                else -> null
            } ?: continue
            val canon = runCatching { candidate.canonicalFile }.getOrNull()
            if (canon == null || !(canon.path == root.path || canon.path.startsWith(root.path + File.separator))) outside += t
        }
        if (CommandRisk.PRIVILEGED in risks) reasons += "Tries to change device settings or gain privileges (this app has no root and cannot grant itself permissions)."
        if (CommandRisk.DESTRUCTIVE in risks) reasons += "Can delete or overwrite data. Deleted files do NOT go to the PocketForge trash."
        if (CommandRisk.NETWORK in risks) reasons += "Uses the network. Shell commands run with this app's internet permission, outside the consent gateway's byte-level checks."
        if (CommandRisk.MODIFIES_FILES in risks) reasons += "May create or change files (unknown commands are treated as modifying)."
        if (outside.isNotEmpty()) reasons += "Touches paths outside this project: ${outside.joinToString(", ").take(300)}"
        if (!policy.projectTrusted) reasons += "This project was imported and is not marked as trusted."
        val blocked = when {
            CommandRisk.NETWORK in risks && !policy.allowNetworkCommands ->
                "Network commands are blocked (Settings → Terminal → Allow network commands). Nothing was run."
            else -> null
        }
        val needs = blocked == null && (risks != setOf(CommandRisk.READ_ONLY) || outside.isNotEmpty() || !policy.projectTrusted)
        return CommandAnalysis(c, risks, outside.toList(), reasons, needs, blocked)
    }

    /** Whitespace/operator tokenizer that keeps quoted strings together. Good enough for path checks, not a full shell parser. */
    internal fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var i = 0
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) } }
        while (i < s.length) {
            val ch = s[i]
            when {
                quote != null -> { if (ch == quote) quote = null else sb.append(ch) }
                ch == '"' || ch == '\'' -> quote = ch
                ch == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i++ }
                ch.isWhitespace() || ch in ";|&<>()`" -> flush()
                ch == '=' && sb.isNotEmpty() && !sb.contains('/') -> { flush() } // FOO=/path → check the value
                else -> sb.append(ch)
            }
            i++
        }
        flush()
        return out
    }
}

data class ShellRequest(
    val command: String,
    val workDir: File,
    val shellPath: String = "/system/bin/sh",
    val timeoutMillis: Long = 60_000,
    val maxOutputBytes: Int = 1 shl 20,
    val tmpDir: File? = null,
    /** PATH for the child. The app's own environment is NOT inherited (it may contain private paths). */
    val path: String = "/system/bin:/system/xbin:/vendor/bin",
)

data class ShellResult(
    val command: String,
    val workDir: String,
    val exitCode: Int?,
    val durationMillis: Long,
    val output: String,
    val truncated: Boolean,
    val timedOut: Boolean,
    val cancelled: Boolean,
) {
    val succeeded get() = exitCode == 0 && !timedOut && !cancelled
    val statusLabel get() = when {
        cancelled -> "cancelled"
        timedOut -> "timed out"
        exitCode == null -> "did not start"
        else -> "exit $exitCode"
    }
}

class CancelSignal {
    private val flag = AtomicBoolean(false)
    fun cancel() = flag.set(true)
    val isCancelled get() = flag.get()
}

/**
 * Runs one command through a real shell, confined to [ShellRequest.workDir] as working directory
 * and HOME, with a clean environment, a hard timeout, an output cap and cancellation.
 * Note: Android cannot sandbox a child process beyond the app's own sandbox; see docs/THREAT_MODEL.md.
 */
object ShellRunner {
    fun isAvailable(shellPath: String = "/system/bin/sh"): Boolean = File(shellPath).let { it.isFile && it.canExecute() }

    fun run(req: ShellRequest, cancel: CancelSignal = CancelSignal(), onOutput: (String) -> Unit = {}): ShellResult {
        val wd = req.workDir.canonicalFile
        if (!wd.isDirectory) throw PfException("terminal.no_dir", "Working folder ${wd.name} does not exist.")
        if (!isAvailable(req.shellPath)) throw PfException("terminal.no_shell", "No shell at ${req.shellPath}. The terminal is unavailable on this device.")
        val pb = ProcessBuilder(req.shellPath, "-c", req.command).directory(wd).redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env["PATH"] = req.path
        env["HOME"] = wd.path
        env["PWD"] = wd.path
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "dumb"
        req.tmpDir?.let { it.mkdirs(); env["TMPDIR"] = it.path }
        val start = System.nanoTime()
        val proc = try { pb.start() } catch (e: Exception) {
            throw PfException("terminal.start_failed", "Could not start the shell: ${e.message}")
        }
        proc.outputStream.close() // no interactive stdin: prevents commands from hanging on input
        val buf = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val reader = Thread {
            try {
                proc.inputStream.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(buf) {
                            val room = req.maxOutputBytes - buf.size()
                            if (room > 0) {
                                val take = minOf(room, n)
                                buf.write(chunk, 0, take)
                                runCatching { onOutput(String(chunk, 0, take, Charsets.UTF_8)) }
                                if (take < n) truncated.set(true)
                            } else truncated.set(true) // keep draining so the child never blocks on a full pipe
                        }
                    }
                }
            } catch (_: Exception) { /* stream closed on kill */ }
        }.apply { isDaemon = true; name = "pf-shell-reader"; start() }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(req.timeoutMillis)
        var timedOut = false
        var cancelled = false
        while (true) {
            if (proc.waitFor(50, TimeUnit.MILLISECONDS)) break
            if (cancel.isCancelled) { cancelled = true; break }
            if (System.nanoTime() > deadline) { timedOut = true; break }
        }
        if (timedOut || cancelled) {
            proc.destroy()
            if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            proc.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(1_000)
        val exit = if (timedOut || cancelled) null else proc.exitValue()
        val text = synchronized(buf) { buf.toString("UTF-8") }
        return ShellResult(
            command = req.command,
            workDir = wd.path,
            exitCode = exit,
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            output = text,
            truncated = truncated.get(),
            timedOut = timedOut,
            cancelled = cancelled,
        )
    }

    /** History entry text for the local log: command and status only, redacted. Output is never logged. */
    fun logLine(r: ShellResult): String = SecretRedactor.redact("\$ ${r.command} → ${r.statusLabel} in ${r.durationMillis} ms", redactPaths = true).text
}
