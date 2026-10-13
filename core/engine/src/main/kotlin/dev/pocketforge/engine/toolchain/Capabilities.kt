package dev.pocketforge.engine.toolchain

/** Raw facts gathered by the platform layer. All fields are measured, never assumed. */
data class DeviceFacts(
    val apiLevel: Int,
    val release: String,
    val abis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val freeStorageBytes: Long,
    val targetSdk: Int,
    /** Result of actually trying to exec a copied binary from app-writable storage (W^X probe). */
    val execFromAppDataAllowed: Boolean,
    val javaOnPath: String?,
    val gradleOnPath: String?,
    val androidSdkPath: String?,
    val hardwareKeyboard: Boolean,
    val networkAllowedBySettings: Boolean,
    val remoteHostConfigured: Boolean,
    val aiProviderConfigured: Boolean,
    val localModelPresent: Boolean,
    /** A real shell exists at /system/bin/sh and could be started (measured by the platform layer). */
    val shellAvailable: Boolean = false,
    /** Termux (com.termux) is installed — needed for the experimental on-device toolchain bridge. */
    val termuxInstalled: Boolean = false,
    /** The user granted com.termux.permission.RUN_COMMAND to PocketForge. */
    val termuxPermissionGranted: Boolean = false,
    /** A shared build folder was chosen for the Termux bridge. */
    val termuxWorkspaceConfigured: Boolean = false,
)

enum class CapStatus(val label: String) {
    SUPPORTED("Works on this device"),
    DEVICE_DEPENDENT("Depends on device"),
    EXPERIMENTAL("Experimental"),
    PERMISSION_REQUIRED("Needs your approval"),
    NOT_CONFIGURED("Not set up yet"),
    UNAVAILABLE("Not available"),
}

data class Capability(val id: String, val title: String, val status: CapStatus, val detail: String)

/** Turns measured [DeviceFacts] into the honest capability matrix shown on the Home and Help screens. */
object CapabilityEvaluator {
    fun evaluate(f: DeviceFacts): List<Capability> {
        val c = ArrayList<Capability>()
        c += Capability("edit", "Project editing, search, snapshots", CapStatus.SUPPORTED, "Fully offline. Files stay in app-private storage.")
        c += Capability("highlight", "Syntax highlighting & diagnostics", CapStatus.SUPPORTED, "Offline lexers for Kotlin, Java, XML/HTML, CSS, JS, JSON, Python, Markdown, Gradle.")
        c += Capability("zip", "ZIP import/export", CapStatus.SUPPORTED, "Through Android's document picker; hardened against unsafe archives.")
        c += Capability(
            "hwkeys", "Hardware keyboard shortcuts",
            if (f.hardwareKeyboard) CapStatus.SUPPORTED else CapStatus.DEVICE_DEPENDENT,
            if (f.hardwareKeyboard) "Keyboard detected." else "Connect a keyboard for Ctrl+S / Ctrl+Z / Ctrl+F.",
        )

        val onDeviceReason = buildList {
            if (!f.execFromAppDataAllowed) add("Android blocks running downloaded executables from app storage for apps targeting API ${f.targetSdk} (W^X policy)")
            if (f.javaOnPath == null) add("no JDK found")
            if (f.gradleOnPath == null) add("no Gradle found")
            if (f.androidSdkPath == null) add("no Android SDK found")
            if (f.totalRamBytes in 1 until 6L * 1024 * 1024 * 1024) add("Gradle + Kotlin compiler typically need 6 GB+ RAM")
        }
        c += Capability(
            "build.local", "On-device Gradle build (inside PocketForge)",
            if (onDeviceReason.isEmpty()) CapStatus.EXPERIMENTAL else CapStatus.UNAVAILABLE,
            if (onDeviceReason.isEmpty()) "Toolchain detected; builds are experimental." else onDeviceReason.joinToString("; ") + ". Use the Termux bridge, Export to computer, or a Build Host.",
        )
        c += Capability(
            "build.termux", "On-device build via Termux (android-lite projects)",
            when {
                !f.termuxInstalled -> CapStatus.UNAVAILABLE
                !f.termuxPermissionGranted -> CapStatus.PERMISSION_REQUIRED
                !f.termuxWorkspaceConfigured -> CapStatus.NOT_CONFIGURED
                else -> CapStatus.EXPERIMENTAL
            },
            when {
                !f.termuxInstalled -> "Install Termux (F-Droid or GitHub build) and its build packages yourself; PocketForge never installs software."
                !f.termuxPermissionGranted -> "Grant PocketForge the Termux \"Run commands\" permission when you start the first on-device build."
                !f.termuxWorkspaceConfigured -> "Choose a shared build folder in Build Center → On-device (Termux)."
                else -> "Runs aapt2 → javac → d8 → apksigner inside Termux. Only the generated script runs; the APK is re-hashed and inspected before success is shown."
            },
        )
        c += Capability(
            "build.remote", "Build on your own Build Host (LAN/remote)",
            when {
                !f.networkAllowedBySettings -> CapStatus.UNAVAILABLE
                !f.remoteHostConfigured -> CapStatus.NOT_CONFIGURED
                else -> CapStatus.PERMISSION_REQUIRED
            },
            when {
                !f.networkAllowedBySettings -> "Network is blocked in Settings."
                !f.remoteHostConfigured -> "Run tools/build-host on your computer and pair it in Build Center."
                else -> "Each build shows exactly which files will be uploaded and asks you first."
            },
        )
        c += Capability("build.export", "Export project to build on a computer", CapStatus.SUPPORTED, "Creates a clean ZIP plus BUILD_INSTRUCTIONS.md.")
        c += Capability("apk.inspect", "Inspect & install APKs", CapStatus.SUPPORTED, "Reads package, version, SDK levels and signature scheme. Install uses Android's own installer and always asks.")
        c += Capability(
            "terminal", "Terminal (system shell)",
            if (f.shellAvailable) CapStatus.SUPPORTED else CapStatus.UNAVAILABLE,
            if (f.shellAvailable) "Runs Android's /system/bin/sh (toybox) in the project folder with a clean environment, timeout and output limit. Risky commands need confirmation; it is not a Linux distribution and has no compilers."
            else "No usable shell was found on this device, so the terminal is disabled.",
        )
        c += Capability("emulator", "Android emulator", CapStatus.UNAVAILABLE, "Not possible on a phone without hardware virtualization; test on this device or a second phone.")
        c += Capability(
            "ai.online", "AI assistant (online provider)",
            when {
                !f.networkAllowedBySettings -> CapStatus.UNAVAILABLE
                !f.aiProviderConfigured -> CapStatus.NOT_CONFIGURED
                else -> CapStatus.PERMISSION_REQUIRED
            },
            "Optional. Every request shows provider, files and the exact redacted payload, and needs your approval.",
        )
        c += Capability(
            "ai.local", "AI assistant (on-device model)",
            if (f.localModelPresent) CapStatus.EXPERIMENTAL else CapStatus.UNAVAILABLE,
            if (f.localModelPresent) "Local model detected." else "No on-device model is bundled. The offline Error Explainer (rule-based, not AI) is available.",
        )
        c += Capability("ai.explainer", "Offline error explainer", CapStatus.SUPPORTED, "Rule-based explanations for common Gradle/Kotlin/Android errors. Not a language model.")
        return c
    }
}

enum class CommandRisk(val label: String) { READ_ONLY("Read-only"), MODIFIES_FILES("Modifies files"), NETWORK("Uses network"), DESTRUCTIVE("Potentially destructive"), PRIVILEGED("Privileged / device-changing") }

/**
 * Conservative classifier for shell commands (shown next to any command an AI suggests or a
 * README contains). PocketForge never runs these automatically; unknown commands are treated
 * as modifying.
 */
object CommandClassifier {
    private val privileged = Regex("(^|[\\s;&|])(su|sudo|doas|setprop|pm\\s+(grant|install|uninstall)|am\\s+|settings\\s+put|mount|chmod\\s+[0-7]*[67][0-7]{2}\\s+/|adb\\s+(root|remount|shell|install))\\b")
    private val destructive = Regex("(^|[\\s;&|])(rm\\s+-[a-zA-Z]*[rf]|rm\\s+|rmdir|dd\\s+|mkfs|shred|truncate|git\\s+(reset\\s+--hard|clean\\s+-[a-z]*f|push\\s+.*--force)|find\\s+.*-delete|>\\s*/)")
    private val network = Regex("(^|[\\s;&|])(curl|wget|ssh|scp|rsync|nc|ncat|telnet|ftp|git\\s+(clone|fetch|pull|push)|pip\\s+install|npm\\s+(install|i|publish)|gradle|\\./gradlew|apt|apt-get|pkg\\s+install|sdkmanager)\\b")
    private val readOnly = Regex("^(ls|cat|head|tail|pwd|echo|grep|rg|find(?!.*-(delete|exec))|wc|stat|file|which|git\\s+(status|log|diff|show|branch)|java\\s+-version|python3?\\s+--version)\\b")

    fun classify(command: String): Set<CommandRisk> {
        val c = command.trim()
        val out = linkedSetOf<CommandRisk>()
        if (privileged.containsMatchIn(c)) out += CommandRisk.PRIVILEGED
        if (destructive.containsMatchIn(c)) out += CommandRisk.DESTRUCTIVE
        if (network.containsMatchIn(c)) out += CommandRisk.NETWORK
        val pipesOrRedirects = c.contains(">") || c.contains("|") || c.contains(";") || c.contains("&&") || c.contains("$(") || c.contains("`")
        if (out.isEmpty()) {
            if (readOnly.containsMatchIn(c) && !pipesOrRedirects) out += CommandRisk.READ_ONLY
            else out += CommandRisk.MODIFIES_FILES
        }
        return out
    }
}
