package dev.pocketforge.engine.build

/**
 * Constants for Termux's documented RUN_COMMAND intent (github.com/termux/termux-app wiki,
 * "RUN_COMMAND Intent"). PocketForge never installs Termux or its packages; the user does,
 * and must also set `allow-external-apps=true` in ~/.termux/termux.properties.
 * Kept in the engine so the values are reviewed and unit-tested in one place.
 */
object TermuxProtocol {
    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Keys of the result Bundle Termux attaches to our PendingIntent. */
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** Packages the user installs inside Termux (shown in Help; never installed by PocketForge). */
    const val SETUP_COMMANDS = "pkg install aapt2 d8 apksigner openjdk-17 zip\n" +
        "termux-setup-storage\n" +
        "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties"

    private val allowedRoots = listOf("/storage/emulated/0/", "/sdcard/", "/data/data/com.termux/files/home/")

    /**
     * Validates the folder path *as Termux sees it* that the user typed for the shared build folder.
     * Returns null when OK, otherwise an actionable message.
     */
    fun validateWorkspacePath(path: String): String? {
        val p = path.trim()
        if (p.isEmpty()) return "Enter the folder path as Termux sees it, e.g. /storage/emulated/0/PocketForge."
        if (!p.startsWith("/")) return "Use an absolute path starting with /."
        if (p.any { it.code < 32 || it == '"' || it == '\'' || it == '`' || it == '$' || it == '\\' }) return "Path contains characters that are not allowed."
        if (p.split('/').any { it == ".." || it == "." }) return "Path must not contain . or .. segments."
        if (allowedRoots.none { (p.trimEnd('/') + "/").startsWith(it) }) return "Use a folder in shared storage (/storage/emulated/0/…) or Termux home (/data/data/com.termux/files/home/…)."
        return null
    }

    /** Folder name for one project inside the shared workspace: readable and collision-free. */
    fun projectFolderName(projectName: String, projectId: String): String {
        val slug = projectName.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(32).ifEmpty { "project" }
        return "$slug-${projectId.take(8)}"
    }
}
